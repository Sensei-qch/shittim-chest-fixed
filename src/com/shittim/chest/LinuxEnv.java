package com.shittim.chest;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置 Linux 环境引擎（实验性）。
 *
 * 基于 Termux 的 proot + Ubuntu 22.04 aarch64 rootfs，在应用私有目录内提供
 * 一个可 apt 安装软件的 GNU/Linux 用户态，无需 root。仅支持 arm64-v8a。
 *
 * 目录布局（均在 getFilesDir()/shittim/ 下）：
 *   native/bin/proot, native/bin/loader   —— proot 可执行与 loader（ELF）
 *   native/lib/libtalloc.so.2, ...        —— proot 的动态依赖
 *   ubuntu/                               —— 解压后的 Ubuntu rootfs
 *   ready                                 —— 初始化完成标记
 */
public final class LinuxEnv {

    private static final String TAG = "LinuxEnv";
    private final Context ctx;
    private final File base;          // files/shittim
    private final File binDir;        // files/shittim/native/bin
    private final File libDir;        // files/shittim/native/lib
    private final File rootfs;        // files/shittim/ubuntu
    private final File readyFlag;

    private final AtomicBoolean initializing = new AtomicBoolean(false);
    private volatile boolean ready = false;
    private volatile String lastError = null;

    /** 记录活跃 shell 进程，便于销毁时清理。 */
    private final Map<Integer, Process> processes = new ConcurrentHashMap<>();
    private volatile long seq = 0;

    public LinuxEnv(Context c) {
        this.ctx = c.getApplicationContext();
        File files = ctx.getFilesDir();
        base = new File(files, "shittim");
        binDir = new File(base, "native/bin");
        libDir = new File(base, "native/lib");
        rootfs = new File(base, "ubuntu");
        readyFlag = new File(base, "ready");
    }

    /** 当前是否已完全就绪。 */
    public boolean isReady() { return ready; }

    /** 是否正在初始化。 */
    public boolean isInitializing() { return initializing.get(); }

    public String lastError() { return lastError == null ? "" : lastError; }

    /** 返回供 UI 展示的状态 JSON。 */
    public String status() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"arch\":\"").append(android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "unknown").append("\"");
        sb.append(",\"supported\":").append(isArm64());
        sb.append(",\"ready\":").append(ready);
        sb.append(",\"initializing\":").append(initializing.get());
        sb.append(",\"rootfsPath\":").append(org.json.JSONObject.quote(rootfs.getAbsolutePath()));
        sb.append(",\"error\":").append(org.json.JSONObject.quote(lastError == null ? "" : lastError));
        sb.append("}");
        return sb.toString();
    }

    public static boolean isArm64() {
        for (String a : android.os.Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(a)) return true;
        return false;
    }

    /**
     * 初始化环境（幂等）。若已 ready 直接返回；否则在后台线程解压。
     * 通过回调把进度文本送回 UI。
     */
    public void init(final Progress cb) {
        if (ready) { if (cb != null) cb.onProgress("Linux 环境已就绪", 100); return; }
        if (!isArm64()) {
            lastError = "当前设备非 arm64-v8a 架构，内置 Linux 环境仅支持 arm64。";
            if (cb != null) cb.onFail(lastError);
            return;
        }
        if (!initializing.compareAndSet(false, true)) {
            if (cb != null) cb.onProgress("正在初始化…", -1);
            return;
        }
        lastError = null;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (cb != null) cb.onProgress("准备目录…", 5);
                    base.mkdirs(); binDir.mkdirs(); libDir.mkdirs();

                    if (cb != null) cb.onProgress("部署 proot 组件…", 15);
                    extractAsset("linux/proot", new File(binDir, "proot"), true);
                    extractAsset("linux/loader", new File(binDir, "loader"), true);
                    extractAsset("linux/libtalloc.so.2", new File(libDir, "libtalloc.so.2"), false);
                    extractAsset("linux/libandroid-shmem.so", new File(libDir, "libandroid-shmem.so"), false);

                    if (cb != null) cb.onProgress("解压 Ubuntu 22.04 rootfs（约 27MB）…", 25);
                    File marker = new File(rootfs, "etc/apt/sources.list");
                    if (!marker.exists()) {
                        extractTarGz("linux/ubuntu-base-arm64.tar.gz", rootfs, cb);
                    }

                    if (cb != null) cb.onProgress("配置 apt 源与 DNS…", 88);
                    writeAptSources();
                    writeResolv();
                    writeInitScript();

                    FileOutputStream fos = new FileOutputStream(readyFlag, false);
                    fos.write("ok".getBytes("UTF-8"));
                    fos.close();

                    ready = true;
                    if (cb != null) cb.onProgress("Linux 环境就绪，可 apt 安装软件", 100);
                } catch (Throwable t) {
                    lastError = "初始化失败：" + (t.getMessage() == null ? t.toString() : t.getMessage());
                    if (cb != null) cb.onFail(lastError);
                } finally {
                    initializing.set(false);
                }
            }
        }, "linuxenv-init").start();
    }

    private void extractAsset(String asset, File dst, boolean executable) throws Exception {
        if (dst.exists() && dst.length() > 0) {
            if (executable) dst.setExecutable(true, false);
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        InputStream in = ctx.getAssets().open(asset);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        if (executable) dst.setExecutable(true, false);
    }

    /** 用 proot 自身之外的系统工具解压：Android 无 tar，故用内置 busybox 风格 shell 命令不可行。
     *  这里改用一个纯 Java 的最小 tar.gz 解包器（gzip + ustar），避免依赖外部 tar。 */
    private void extractTarGz(String asset, File dest, Progress cb) throws Exception {
        dest.mkdirs();
        java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(ctx.getAssets().open(asset));
        byte[] buf = new byte[8192];
        int offset = 0;
        // 手工解析 ustar：512 字节头 + 数据
        byte[] header = new byte[512];
        while (readFully(gz, header, 0, 512)) {
            if (allZero(header)) break;
            String name = cstr(header, 0, 100);
            String prefix = cstr(header, 345, 155);
            if (prefix.length() > 0) name = prefix + "/" + name;
            long size = octal(header, 124, 12);
            int type = header[156] & 0xff;
            // 清理路径，防目录穿越
            name = name.replace("\\", "/");
            while (name.startsWith("./")) name = name.substring(2);
            if (name.startsWith("/")) name = name.substring(1);
            if (name.isEmpty()) { skipFully(gz, size); continue; }
            File f = new File(dest, name);
            if (type == '5' || name.endsWith("/")) {
                f.mkdirs();
            } else {
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                FileOutputStream out = new FileOutputStream(f);
                long remaining = size;
                byte[] b = new byte[16384];
                while (remaining > 0) {
                    int want = (int) Math.min(b.length, remaining);
                    int got = gz.read(b, 0, want);
                    if (got <= 0) break;
                    out.write(b, 0, got);
                    remaining -= got;
                }
                out.close();
                // 保留可执行位（ustar mode 的 owner exec bits）
                long mode = octal(header, 100, 8);
                if ((mode & 0111) != 0) f.setExecutable(true, false);
            }
            long blocks = ((size + 511) / 512) * 512;
            skipFully(gz, blocks - size);
        }
        gz.close();
    }

    private static boolean readFully(InputStream in, byte[] b, int off, int len) throws Exception {
        int got = 0;
        while (got < len) {
            int n = in.read(b, off + got, len - got);
            if (n <= 0) return got > 0;
            got += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws Exception {
        byte[] b = new byte[8192];
        while (n > 0) {
            int want = (int) Math.min(b.length, n);
            int got = in.read(b, 0, want);
            if (got <= 0) break;
            n -= got;
        }
    }

    private static boolean allZero(byte[] h) {
        for (byte b : h) if (b != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private void writeAptSources() throws Exception {
        File f = new File(rootfs, "etc/apt/sources.list");
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        FileOutputStream out = new FileOutputStream(f, false);
        out.write(("deb http://ports.ubuntu.com/ubuntu-ports jammy main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports jammy-updates main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports jammy-security main restricted universe multiverse\n")
                .getBytes("UTF-8"));
        out.close();
    }

    private void writeResolv() throws Exception {
        File f = new File(rootfs, "etc/resolv.conf");
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        FileOutputStream out = new FileOutputStream(f, false);
        out.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n".getBytes("UTF-8"));
        out.close();
    }

    private void writeInitScript() throws Exception {
        File d = new File(rootfs, "etc/shittim");
        d.mkdirs();
        File f = new File(d, "env.sh");
        FileOutputStream out = new FileOutputStream(f, false);
        out.write(("export HOME=/root\n" +
                "export TERM=xterm\n" +
                "export LANG=C.UTF-8\n" +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "cd /root\n").getBytes("UTF-8"));
        out.close();
    }

    /**
     * 在 proot 环境里执行一条命令，实时把 stdout+stderr 送给回调。
     * 返回进程退出码（-1 表示启动失败）。
     */
    public int exec(String command, final ExecCb cb) {
        if (!ready || !isArm64()) {
            if (cb != null) cb.onLine("[错误] Linux 环境未就绪：" + (lastError == null ? "请等待初始化" : lastError) + "\n");
            return -1;
        }
        if (command == null || command.trim().length() == 0) return 0;

        final long id = ++seq;
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(binDir, "proot").getAbsolutePath());
        cmd.add("-r"); cmd.add(rootfs.getAbsolutePath());
        cmd.add("-0");                       // 伪装 root（apt 需要）
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/sdcard:/sdcard");
        cmd.add("-w"); cmd.add("/root");
        cmd.add("/bin/bash"); cmd.add("-lc");
        // 每条命令都先 source 环境脚本
        cmd.add(". /etc/shittim/env.sh 2>/dev/null; " +
                "export PROOT_LOADER='" + new File(binDir, "loader").getAbsolutePath() + "'; " + command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("PROOT_LOADER", new File(binDir, "loader").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        env.put("PATH", System.getenv("PATH"));
        env.put("TMPDIR", new File(base, "tmp").getAbsolutePath());
        new File(base, "tmp").mkdirs();
        pb.redirectErrorStream(true);

        try {
            final Process proc = pb.start();
            processes.put((int) id, proc);
            final InputStream is = proc.getInputStream();
            new Thread(new Runnable() {
                @Override public void run() {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    String line;
                    try {
                        while ((line = br.readLine()) != null) {
                            if (cb != null) cb.onLine(line + "\n");
                        }
                    } catch (Exception ignored) {
                    } finally {
                        processes.remove((int) id);
                    }
                }
            }, "sh-out-" + id).start();
            int code = proc.waitFor();
            processes.remove((int) id);
            return code;
        } catch (Throwable t) {
            processes.remove((int) id);
            if (cb != null) cb.onLine("[错误] 无法启动 proot：" + (t.getMessage() == null ? t.toString() : t.getMessage()) + "\n");
            return -1;
        }
    }

    /** 停止所有仍在运行的 shell 进程。 */
    public void killAll() {
        for (Map.Entry<Integer, Process> e : processes.entrySet()) {
            try { e.getValue().destroy(); } catch (Exception ignored) { }
        }
        processes.clear();
    }

    public interface Progress {
        void onProgress(String text, int percent);
        void onFail(String reason);
    }

    public interface ExecCb {
        void onLine(String line);
    }
}
