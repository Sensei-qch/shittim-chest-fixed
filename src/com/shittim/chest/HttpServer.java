package com.shittim.chest;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;

/**
 * 什亭之匣服务 · 内置本地 HTTP 服务
 *
 * 对外：托管 assets/www 静态资源（供 WebView 以 http://127.0.0.1:port 加载）
 * 对内：/api/chat 代理，转发到外部 OpenAI 兼容接口（解决 WebView CORS 限制）
 *
 * ============================ 缺陷修复说明 ============================
 * 【致命 · 请求体读取】
 *   原实现用 BufferedReader.read(char[], off, len) 读取请求体，len 取自
 *   Content-Length。但 Content-Length 是「字节数」，而 BufferedReader 读的是
 *   「字符数」。AI 请求体必然含中文（阿洛娜人设），UTF-8 下每个汉字 3 字节，
 *   因此字节数 > 字符数，循环永远读不满 → 阻塞至 soTimeout(120s) 超时 →
 *   代理返回 502 → 前端表现为「阿洛娜完全无法回应」。
 *   修复：改为按 Content-Length 精确读取「字节」，再以 UTF-8 解码。
 *
 * 【致命 · 请求头预读】
 *   原实现用同一个 BufferedReader 先读请求行与请求头，字符流会预读缓冲，
 *   导致后续字节读取错位。修复：请求行/请求头也改为按字节读取。
 *
 * 【流式失效】
 *   原实现在转发时未做流式处理，前端 SSE 解析拿不到增量数据，且响应头缺少
 *   正确的流式标识。修复：使用 chunked 编码边读边刷，逐块送达。
 *
 * 【端点重复拼接】
 *   原实现无条件在 Base URL 后拼接 "/chat/completions"，当用户直接填写完整
 *   端点（如 https://api.deepseek.com/chat/completions）时会拼成
 *   .../chat/completions/chat/completions 而 404。修复：智能判断。
 *
 * 【错误信息丢失】
 *   原实现丢弃上游 4xx/5xx 的错误体，只回一个空的 502，前端无法提示
 *   「ApiKey 错误」「额度不足」等真实原因。修复：完整透传上游状态码与错误体。
 * =====================================================================
 */
public class HttpServer implements Runnable {

    private ServerSocket ss;
    private int port = -1;
    private volatile boolean running = true;
    private final Context ctx;
    private final LinuxEnv linux;

    public HttpServer(MainActivity act, Context c) {
        this.ctx = c;
        this.linux = act != null ? act.linuxEnv() : null;
    }

    public int getPort() {
        return port;
    }

    /* ============================ 生命周期 ============================ */

    public void start() {
        for (int p = 8777; p <= 8787; p++) {
            try {
                ss = new ServerSocket(p, 32, InetAddress.getByName("127.0.0.1"));
                port = p;
                break;
            } catch (Exception e) {
                // 端口被占用，继续尝试下一个
            }
        }
        if (port < 0) throw new RuntimeException("no free port");
        Thread t = new Thread(this, "shittim-httpd");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        while (running) {
            try {
                final Socket c = ss.accept();
                Thread w = new Thread(new Runnable() {
                    @Override public void run() { handle(c); }
                }, "shittim-conn");
                w.setDaemon(true);
                w.start();
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    /* ============================ 请求处理 ============================ */

    private void handle(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(120000);
            InputStream raw = sock.getInputStream();

            String line = readLine(raw);
            if (line == null) { sock.close(); return; }

            boolean isPost = line.startsWith("POST");
            boolean isGet = line.startsWith("GET");
            if (!isPost && !isGet) { sock.close(); return; }

            String[] parts = line.split(" ");
            if (parts.length < 2) { sock.close(); return; }
            String path = parts[1];

            int contentLength = -1;
            long rangeStart = -1, rangeEnd = -1;
            String h;
            while ((h = readLine(raw)) != null && h.length() > 0) {
                String hl = h.toLowerCase();
                if (hl.startsWith("range:")) {
                    try {
                        String r = h.substring(6).trim();
                        if (r.startsWith("bytes=")) {
                            String seg = r.substring(6).split(",")[0].trim();
                            int dash = seg.indexOf('-');
                            if (dash > 0) {
                                rangeStart = Long.parseLong(seg.substring(0, dash));
                                rangeEnd = seg.endsWith("-") ? -1 : Long.parseLong(seg.split("-")[1]);
                            }
                        }
                    } catch (Exception ignored) { }
                } else if (hl.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(h.substring(15).trim());
                    } catch (Exception ignored) { }
                }
            }

            // 【核心修复】按字节精确读取请求体：Content-Length 是字节数，不是字符数
            String body = null;
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = raw.read(buf, read, contentLength - read);
                    if (n <= 0) break;
                    read += n;
                }
                if (read == contentLength) body = new String(buf, 0, read, "UTF-8");
            }

            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            try { path = URLDecoder.decode(path, "UTF-8"); } catch (Exception ignored) { }

            if ("/api/chat".equals(path) && body != null) {
                apiChat(sock, body);
                return;
            }
            if ("/api/linux/status".equals(path)) {
                sendJson(sock, linux == null ? "{\"ready\":false,\"error\":\"无环境实例\"}" : linux.status());
                return;
            }
            if ("/api/linux/init".equals(path)) {
                apiLinuxInit(sock);
                return;
            }
            if ("/api/linux/exec".equals(path) && body != null) {
                apiLinuxExec(sock, body);
                return;
            }
            serveAsset(sock, path, rangeStart, rangeEnd);
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 按字节读取一行（兼容 \r\n 与 \n）。返回不含行尾的字符串；流结束且无数据返回 null。
     * 不使用字符流，避免预读缓冲导致后续字节读取错位。
     */
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c = -1;
        while ((c = in.read()) != -1) {
            if (c == '\r') continue;
            if (c == '\n') break;
            bo.write(c);
        }
        if (c == -1 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), "UTF-8");
    }

    /* ============================ 内置 Linux 环境 ============================ */

    private void sendJson(Socket sock, String json) {
        try {
            byte[] b = json.getBytes("UTF-8");
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n");
            h.append("Content-Length: ").append(b.length).append("\r\n");
            h.append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
            OutputStream out = sock.getOutputStream();
            out.write(h.toString().getBytes("UTF-8"));
            out.write(b);
            out.flush();
            sock.close();
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /** 初始化环境：SSE 推送进度，结束时发一条 __done 事件。 */
    private void apiLinuxInit(final Socket sock) {
        try {
            writeSseHeaders(sock);
            final OutputStream out = sock.getOutputStream();
            if (linux == null) {
                sseLine(out, "data: " + JSONObject.quote("{\"type\":\"fail\",\"text\":\"无环境实例\"}") + "\n\n");
                out.flush(); sock.close(); return;
            }
            if (linux.isReady()) {
                sseLine(out, "data: " + JSONObject.quote("{\"type\":\"done\",\"text\":\"已就绪\"}") + "\n\n");
                out.flush(); sock.close(); return;
            }
            linux.init(new LinuxEnv.Progress() {
                @Override public void onProgress(String text, int percent) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", percent >= 100 ? "done" : "progress");
                        o.put("text", text == null ? "" : text);
                        o.put("percent", percent);
                        sseLine(out, "data: " + o.toString() + "\n\n");
                        out.flush();
                    } catch (Exception ignored) { }
                }
                @Override public void onFail(String reason) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", "fail");
                        o.put("text", reason == null ? "初始化失败" : reason);
                        sseLine(out, "data: " + o.toString() + "\n\n");
                        out.flush();
                    } catch (Exception ignored) { }
                }
            });
            // init 是异步的，SSE 通道需保持打开直到收到 done/fail。
            // 由于回调里已发 done/fail，这里延迟关闭，给浏览器时间接收。
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(1500); } catch (Exception ignored) { }
                    try { out.flush(); sock.close(); } catch (Exception ignored) { }
                }
            }).start();
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /** 执行命令：SSE 推送每行输出 + 最终退出码。 */
    private void apiLinuxExec(final Socket sock, String body) {
        try {
            JSONObject req = new JSONObject(body);
            String cmd = req.optString("cmd", "");
            writeSseHeaders(sock);
            final OutputStream out = sock.getOutputStream();
            if (linux == null || !linux.isReady()) {
                JSONObject o = new JSONObject();
                o.put("type", "line");
                o.put("text", "[错误] Linux 环境未就绪，请先初始化\n");
                sseLine(out, "data: " + o.toString() + "\n\n");
                out.flush(); sock.close(); return;
            }
            final int code = linux.exec(cmd, new LinuxEnv.ExecCb() {
                @Override public void onLine(String line) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", "line");
                        o.put("text", line);
                        sseLine(out, "data: " + o.toString() + "\n\n");
                        out.flush();
                    } catch (Exception ignored) { }
                }
            });
            JSONObject done = new JSONObject();
            done.put("type", "done");
            done.put("code", code);
            sseLine(out, "data: " + done.toString() + "\n\n");
            out.flush();
            sock.close();
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    private void writeSseHeaders(Socket sock) throws Exception {
        String h = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        sock.getOutputStream().write(h.getBytes("UTF-8"));
        sock.getOutputStream().flush();
    }

    private static void sseLine(OutputStream out, String s) throws Exception {
        out.write(s.getBytes("UTF-8"));
    }

    /* ============================ AI 代理 ============================ */

    private void apiChat(Socket sock, String body) {
        OutputStream rawOut = null;
        try {
            rawOut = sock.getOutputStream();
            BufferedOutputStream out = new BufferedOutputStream(rawOut);

            JSONObject req;
            try {
                req = new JSONObject(body);
            } catch (Exception e) {
                sendJson(out, 400, "Bad Request", "{\"error\":\"请求体不是合法 JSON\"}");
                return;
            }

            String url = req.optString("url", "");
            String key = req.optString("key", "");
            String model = req.optString("model", "");
            String proxy = req.optString("proxy", "").trim();
            JSONArray messages = req.optJSONArray("messages");
            if (messages == null) messages = new JSONArray();
            boolean stream = req.optBoolean("stream", true);
            int maxTokens = req.optInt("max_tokens", 0);

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                sendJson(out, 400, "Bad Request", "{\"error\":\"接口地址必须以 http(s):// 开头\"}");
                return;
            }
            if (model.length() == 0) {
                sendJson(out, 400, "Bad Request", "{\"error\":\"缺少模型名称 model\"}");
                return;
            }

            // 【修复】智能拼接端点，避免重复 /chat/completions
            String endpoint = buildEndpoint(url);

            Proxy px = null;
            if (proxy.length() > 0) {
                int i = proxy.lastIndexOf(':');
                if (i > 0) {
                    try {
                        String ph = proxy.substring(0, i).trim();
                        int pp = Integer.parseInt(proxy.substring(i + 1).trim());
                        px = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ph, pp));
                    } catch (Exception ignored) { }
                }
            }

            URL target = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection)
                    (px != null ? target.openConnection(px) : target.openConnection());
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }

            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("stream", stream);
            if (maxTokens > 0) payload.put("max_tokens", maxTokens);

            byte[] bodyBytes = payload.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            OutputStream connOut = conn.getOutputStream();
            connOut.write(bodyBytes);
            connOut.close();

            int code = conn.getResponseCode();
            String respCt = conn.getHeaderField("Content-Type");
            if (respCt == null) respCt = stream ? "text/event-stream" : "application/json";

            if (code >= 400) {
                // 【修复】完整透传上游错误体，前端才能看到「ApiKey 错误」等真实原因
                byte[] eb = readAll(conn.getErrorStream());
                String errBody = eb.length > 0 ? new String(eb, "UTF-8")
                        : ("{\"error\":{\"message\":\"上游返回 HTTP " + code + "\"}}");
                String reason = conn.getResponseMessage() == null ? "Error" : conn.getResponseMessage();
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP/1.1 ").append(code).append(' ').append(reason)
                  .append("\r\nContent-Type: application/json\r\nContent-Length: ")
                  .append(errBody.getBytes("UTF-8").length)
                  .append("\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                  .append(errBody);
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
                return;
            }

            if (stream) {
                // 【修复】真正的流式转发：chunked + 边读边刷，逐块送达前端
                StringBuilder head = new StringBuilder();
                head.append("HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: ").append(respCt).append("\r\n")
                    .append("Transfer-Encoding: chunked\r\n")
                    .append("Cache-Control: no-cache\r\n")
                    .append("Access-Control-Allow-Origin: *\r\n")
                    .append("Connection: close\r\n\r\n");
                out.write(head.toString().getBytes("UTF-8"));
                out.flush();

                InputStream is = conn.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) {
                    out.write(Integer.toHexString(n).getBytes("UTF-8"));
                    out.write('\r');
                    out.write('\n');
                    out.write(buf, 0, n);
                    out.write('\r');
                    out.write('\n');
                    out.flush();
                }
                out.write("0\r\n\r\n".getBytes("UTF-8"));
                out.flush();
                try { is.close(); } catch (Exception ignored) { }
            } else {
                byte[] rb = readAll(conn.getInputStream());
                StringBuilder head = new StringBuilder();
                head.append("HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: ").append(respCt).append("\r\n")
                    .append("Content-Length: ").append(rb.length)
                    .append("\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
                out.write(head.toString().getBytes("UTF-8"));
                out.write(rb);
                out.flush();
            }
        } catch (Exception e) {
            try {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP/1.1 502 Bad Gateway\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n")
                  .append("{\"error\":").append(JSONObject.quote(msg)).append("}");
                rawOut.write(sb.toString().getBytes("UTF-8"));
                rawOut.flush();
            } catch (Exception ignored) { }
        } finally {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 【修复】智能构造 chat/completions 端点：
     *  - Base URL（如 https://api.deepseek.com/v1）      → 追加 /chat/completions
     *  - 完整端点（如 .../chat/completions）             → 原样使用
     *  - 裸 Host（如 https://api.chatanywhere.tech）     → 补 /v1/chat/completions
     */
    private static String buildEndpoint(String rawUrl) {
        String u = rawUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat/completions") || u.endsWith("/completions")) return u;
        try {
            URL x = new URL(u);
            String path = x.getPath();
            if (path == null || path.length() == 0 || path.equals("/")) {
                return u + "/v1/chat/completions";
            }
        } catch (Exception ignored) { }
        return u + "/chat/completions";
    }

    private static byte[] readAll(InputStream is) {
        if (is == null) return new byte[0];
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void sendJson(BufferedOutputStream out, int code, String reason, String json) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason)
          .append("\r\nContent-Type: application/json\r\nContent-Length: ")
          .append(json.getBytes("UTF-8").length)
          .append("\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
          .append(json);
        out.write(sb.toString().getBytes("UTF-8"));
        out.flush();
    }

    /* ============================ 静态资源 ============================ */

    private void serveAsset(Socket sock, String path, long rangeStart, long rangeEnd) {
        try {
            String assetPath = path.equals("/") ? "www/index.html" : ("www" + path);
            String mime = MainActivity.mime(assetPath);

            byte[] data;
            try {
                data = readAll(ctx.getAssets().open(assetPath));
            } catch (Exception e) {
                String n = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                sock.getOutputStream().write(n.getBytes("UTF-8"));
                sock.getOutputStream().flush();
                sock.close();
                return;
            }

            long total = data.length;
            int from = 0, to = (int) total - 1;
            boolean partial = false;
            if (rangeStart >= 0 && rangeStart < total) {
                from = (int) rangeStart;
                to = (rangeEnd >= 0 && rangeEnd < total) ? (int) rangeEnd : (int) total - 1;
                if (to < from) to = from;
                partial = true;
            }
            int len = to - from + 1;

            StringBuilder sb = new StringBuilder();
            sb.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(mime).append("\r\n");
            sb.append("Content-Length: ").append(len).append("\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            if (partial) {
                sb.append("Content-Range: bytes ").append(from).append('-').append(to)
                  .append('/').append(total).append("\r\n");
            }
            sb.append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");

            OutputStream out = sock.getOutputStream();
            out.write(sb.toString().getBytes("UTF-8"));
            out.write(data, from, len);
            out.flush();
            sock.close();
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }
}
