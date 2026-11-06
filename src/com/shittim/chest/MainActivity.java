package com.shittim.chest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 什厅之匣服务 · 主界面
 *
 * 说明：本类为反编译还原 + 缺陷修复版本。
 * 修复的核心问题在 {@link HttpServer}：原实现用字符数读取请求体，导致含中文的
 * AI 请求体永远读不满而超时。详见 HttpServer 类注释。
 */
public class MainActivity extends Activity {

    private WebView web;
    private boolean modal = false;
    private HttpServer httpd;
    /** 桌面小组件点击后待打开的面板（map / arona / term）。 */
    private String pendingWidgetOpen = null;
    /** 当前存活的主界面，供桌面小组件按钮直接派发动作；进程被回收时为 null。 */
    static volatile MainActivity active = null;
    private volatile boolean pageReady = false;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        active = this;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                Intent i = new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION");
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) { }
        }

        Intent launch = getIntent();
        if (launch != null) pendingWidgetOpen = launch.getStringExtra("widget_open");

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setDefaultTextEncodingName("utf-8");
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(false);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String u = req.getUrl() == null ? "" : req.getUrl().toString();
                if (!u.startsWith("file:///android_asset/")) return null;
                String p = u.substring("file:///android_asset/".length());
                int q = p.indexOf('?');
                if (q >= 0) p = p.substring(0, q);
                try {
                    String mime = mime(p);
                    java.util.Map<String, String> hs = new java.util.HashMap<String, String>();
                    hs.put("Access-Control-Allow-Origin", "*");
                    InputStream in = getAssets().open(p);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    byte[] data = bo.toByteArray();
                    hs.put("Content-Length", String.valueOf(data.length));
                    hs.put("Accept-Ranges", "bytes");
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK", hs,
                            new java.io.ByteArrayInputStream(data));
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                active = MainActivity.this;
                flushWidgetOpen();
                flushWidgetActions();
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.addJavascriptInterface(new FSBridge(this, this, getFilesDir()), "AndroidFS");

        int port = startHttpd();
        if (port > 0) {
            web.loadUrl("http://127.0.0.1:" + port + "/index.html");
        } else {
            web.loadUrl("file:///android_asset/www/index.html");
        }
    }

    /* ---- 桌面小组件按钮：把动作直接送到 WebView 执行 ---- */

    /** 由 WidgetHelper.handleAction() 调用；界面不可用返回 false，由调用方入队补执行。 */
    static boolean dispatchWidgetAction(final String kind, final String act) {
        final MainActivity a = active;
        if (a == null || a.web == null || !a.pageReady) return false;
        try {
            a.web.post(new Runnable() {
                @Override public void run() { a.evalWidgetAction(kind, act); }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void evalWidgetAction(String k, String a) {
        if (web == null || k == null || a == null) return;
        try {
            web.evaluateJavascript(
                "(function(){ if(window.__widgetAction) window.__widgetAction(" +
                JSONObject.quote(k) + "," + JSONObject.quote(a) + "); })()", null);
        } catch (Exception ignored) { }
    }

    /** 应用进程曾被回收时，补执行桌面排队的操作（先清空队列，避免重复执行）。 */
    private void flushWidgetActions() {
        if (!pageReady || web == null) return;
        try {
            JSONArray q = WidgetHelper.queue(this);
            if (q.length() == 0) return;
            WidgetHelper.clearQueue(this);
            for (int i = 0; i < q.length(); i++) {
                JSONObject o = q.optJSONObject(i);
                if (o == null) continue;
                evalWidgetAction(o.optString("k", ""), o.optString("a", ""));
            }
        } catch (Exception ignored) { }
    }

    /** 桌面小组件点击入口：页面就绪后调用 JS 打开对应面板。 */
    private void flushWidgetOpen() {
        final String k = pendingWidgetOpen;
        if (k == null || web == null) return;
        if (!k.matches("[a-z]+")) { pendingWidgetOpen = null; return; }
        web.evaluateJavascript(
            "(function(){ if(!window.__widgetOpen) return 'pending'; window.__widgetOpen('" + k + "'); return 'ok'; })()",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (v != null && v.contains("ok")) pendingWidgetOpen = null;
                }
            });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String k = intent == null ? null : intent.getStringExtra("widget_open");
        if (k == null) return;
        pendingWidgetOpen = k;
        if (web != null) web.post(new Runnable() {
            @Override public void run() { flushWidgetOpen(); }
        });
    }

    private int startHttpd() {
        try {
            httpd = new HttpServer(this, this);
            httpd.start();
            return httpd.getPort();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = this;
        if (web != null) {
            web.onResume();
            if (pageReady) web.post(new Runnable() {
                @Override public void run() { flushWidgetActions(); }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (active == this) active = null;
    }

    @Override
    public void onBackPressed() {
        if (modal) {
            web.evaluateJavascript("(window.onBackPressed ? window.onBackPressed() : false)",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            if (v != null && "true".equals(v.trim())) return;
                            modal = false;
                        }
                    });
            return;
        }
        super.onBackPressed();
    }

    static String mime(String p) {
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".onnx")) return "application/octet-stream";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /* ============================ JS 桥 ============================ */

    class Bridge {
        @JavascriptInterface
        public void setModal(boolean m) {
            modal = m;
        }

        @JavascriptInterface
        public void setWidgetData(String json) {
            try { WidgetHelper.save(MainActivity.this, json); }
            catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void requestAllFilesPermission() {
            if (Build.VERSION.SDK_INT < 30) return;
            if (Environment.isExternalStorageManager()) return;
            try {
                Intent i = new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION");
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) { }
        }
    }

    /* ============================ 文件系统桥 ============================ */

    class FSBridge {
        private final Context ctx;
        private final File root;

        FSBridge(MainActivity act, Context c, File r) {
            this.ctx = c;
            this.root = r != null ? r : c.getFilesDir();
        }

        private String ok(JSONObject o) {
            try { o.put("ok", true); } catch (Exception ignored) { }
            return o.toString();
        }

        private String err(String msg) {
            if (msg == null) msg = "";
            return "{\"ok\":false,\"msg\":" + JSONObject.quote(msg) + "}";
        }

        private JSONObject item(File f) throws Exception {
            JSONObject o = new JSONObject();
            o.put("n", f.getName());
            o.put("t", f.isDirectory() ? "dir" : "file");
            o.put("s", f.length());
            return o;
        }

        /** 路径解析：相对路径基于 root；越界（跳出 root）返回 null。 */
        private File resolve(String p) {
            try {
                File f = new File(root, p == null ? "" : p);
                String fp = f.getCanonicalPath();
                String rp = root.getCanonicalPath();
                if (!fp.equals(rp) && !fp.startsWith(rp + File.separator)) return null;
                return f;
            } catch (Exception e) {
                return null;
            }
        }

        private byte[] readAll(File f) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        }

        private void copy(File src, File dst) throws IOException {
            if (src.isDirectory()) {
                dst.mkdirs();
                File[] kids = src.listFiles();
                if (kids != null) for (File k : kids) copy(k, new File(dst, k.getName()));
            } else {
                dst.getParentFile().mkdirs();
                FileInputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
            }
        }

        private void del(File f) {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) del(k);
            }
            f.delete();
        }

        @JavascriptInterface
        public String ls(String p) {
            try {
                File f = resolve(p);
                if (f == null || !f.exists()) return err("无此路径: " + p);
                JSONArray arr = new JSONArray();
                if (f.isFile()) {
                    arr.put(item(f));
                } else {
                    File[] kids = f.listFiles();
                    if (kids != null) for (File k : kids) arr.put(item(k));
                }
                JSONObject o = new JSONObject();
                o.put("items", arr);
                return ok(o);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String read(String p) {
            try {
                File f = resolve(p);
                if (f == null || !f.exists()) return err("无此文件: " + p);
                if (f.isDirectory()) return err("这是目录: " + p);
                if (f.length() > 1024 * 512) return err("文件过大，无法在终端查看");
                JSONObject o = new JSONObject();
                o.put("text", new String(readAll(f), "utf-8"));
                return ok(o);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String write(String p, String content) {
            try {
                File f = resolve(p);
                if (f == null) return err("路径不合法");
                File par = f.getParentFile();
                if (par != null) par.mkdirs();
                FileOutputStream out = new FileOutputStream(f);
                out.write((content == null ? "" : content).getBytes("utf-8"));
                out.close();
                return ok(new JSONObject());
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String mkdir(String p) {
            try {
                File f = resolve(p);
                if (f == null) return err("路径不合法");
                if (f.mkdirs()) return ok(new JSONObject());
                if (f.isDirectory()) return ok(new JSONObject());
                return err("创建失败");
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String touch(String p) {
            try {
                File f = resolve(p);
                if (f == null) return err("路径不合法");
                if (f.exists()) return ok(new JSONObject());
                File par = f.getParentFile();
                if (par != null) par.mkdirs();
                new FileOutputStream(f).close();
                return ok(new JSONObject());
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String rm(String p, boolean rec) {
            try {
                File f = resolve(p);
                if (f == null || !f.exists()) return err("无此路径: " + p);
                if (f.isDirectory()) {
                    File[] kids = f.listFiles();
                    if (!rec && kids != null && kids.length > 0) return err("目录非空，请用 rm -r");
                    del(f);
                } else {
                    f.delete();
                }
                return ok(new JSONObject());
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String mv(String a, String b) {
            try {
                File s = resolve(a), d = resolve(b);
                if (s == null || d == null || !s.exists()) return err("源路径无效");
                if (d.exists() && d.isDirectory()) d = new File(d, s.getName());
                if (s.renameTo(d)) return ok(new JSONObject());
                copy(s, d);
                del(s);
                return ok(new JSONObject());
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String cp(String a, String b) {
            try {
                File s = resolve(a), d = resolve(b);
                if (s == null || d == null || !s.exists()) return err("源路径无效");
                if (d.exists() && d.isDirectory()) d = new File(d, s.getName());
                copy(s, d);
                return ok(new JSONObject());
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String exists(String p) {
            try {
                File f = resolve(p);
                String t = "none";
                if (f != null && f.exists()) t = f.isDirectory() ? "dir" : "file";
                JSONObject o = new JSONObject();
                o.put("t", t);
                return ok(o);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String info() {
            try {
                String ver = "1.0";
                try {
                    PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                    if (pi != null && pi.versionName != null) ver = pi.versionName;
                } catch (Exception ignored) { }
                JSONObject o = new JSONObject();
                o.put("version", ver);
                o.put("root", root.getAbsolutePath());
                return ok(o);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }

        @JavascriptInterface
        public String launch(String kw) {
            try {
                if (kw == null || kw.length() == 0) return err("请指定应用关键字");
                PackageManager pm = ctx.getPackageManager();
                Intent q = new Intent("android.intent.action.MAIN", (Uri) null);
                q.addCategory("android.intent.category.LAUNCHER");
                List<ResolveInfo> all = pm.queryIntentActivities(q, 0);
                String target = null;
                for (ResolveInfo ri : all) {
                    if (ri.activityInfo == null) continue;
                    String pn = ri.activityInfo.packageName;
                    if (pn == null) continue;
                    if (pn.equalsIgnoreCase(kw)) { target = pn; break; }
                    if (pn.toLowerCase().contains(kw.toLowerCase())) { target = pn; break; }
                }
                if (target == null) return err("未找到匹配应用: " + kw);
                Intent li = pm.getLaunchIntentForPackage(target);
                if (li == null) return err("无法获取启动入口: " + target);
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(li);
                JSONObject o = new JSONObject();
                o.put("pkg", target);
                return ok(o);
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }
    }
}
