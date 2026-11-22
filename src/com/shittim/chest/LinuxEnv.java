package com.shittim.chest;

import android.content.Context;
import android.system.Os;
import android.system.StructStat;

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
 *
 * 执行权限兜底：部分设备把应用私有目录挂载为 noexec，导致 execve(proot) 返回
 * error=13。此时改用系统保证可执行的 nativeLibraryDir（lib/arm64-v8a 解压目录）
 * 里的同款二进制，并通过符号链接让动态链接器按 soname 找到依赖库。
 */
public final class LinuxEnv {

    private static final String TAG = "LinuxEnv";

    /* 打包进 lib/arm64-v8a/ 的兜底二进制（Android 只解压 lib*.so） */
    private static final String LIB_PROOT  = "libproot.so";
    private static final String LIB_LOADER = "libprootldr.so";
    private static final String LIB_TALLOC = "libtalloc2.so";
    private static final String LIB_SHMEM  = "libshmem.so";

    private final Context ctx;
    private final File base;          // files/shittim
    private final File binDir;        // files/shittim/native/bin
    private final File libDir;        // files/shittim/native/lib
    private final File rootfs;        // files/shittim/ubuntu
    private final File readyFlag;
    private final File nativeLibDir;  // /data/app/<pkg>/lib/<abi> —— 系统保证可执行

    private final AtomicBoolean initializing = new AtomicBoolean(false);
    private volatile boolean ready = false;
    private volatile String lastError = null;
    /* files 目录里的二进制是否真正可执行；false = 走 nativeLibraryDir 兜底 */
    private volatile boolean binExecOk = false;
    private volatile String execMode = "未就绪";

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
        String nld = ctx.getApplicationInfo().nativeLibraryDir;
        nativeLibDir = new File(nld == null ? "/data/app" : nld);
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
        sb.append(",\"binExec\":").append(binExecOk);
        sb.append(",\"execMode\":").append(org.json.JSONObject.quote(execMode));
        sb.append(",\"nativeLibDir\":").append(org.json.JSONObject.quote(nativeLibDir.getAbsolutePath()));
        sb.append(",\"error\":").append(org.json.JSONObject.quote(lastError == null ? "" : lastError));
        sb.append("}");
        return sb.toString();
    }

    public static boolean isArm64() {
        for (String a : android.os.Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(a)) return true;
        return false;
    }

    /**
     * 用 Os.chmod 强制设置 0755，再用 Os.stat 校验执行位确实生效。
     * 比 File.setExecutable 更可靠，且能检测「静默失败」。
     */
    private boolean forceExec(File f) {
        if (f == null || !f.exists()) return false;
        try {
            Os.chmod(f.getAbsolutePath(), 0755);
            StructStat st = Os.stat(f.getAbsolutePath());
            return (st.st_mode & 0111) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只检查执行位，不改权限（用于系统 nativeLibraryDir）。 */
    private boolean canExec(File f) {
        if (f == null || !f.exists()) return false;
        try {
            StructStat st = Os.stat(f.getAbsolutePath());
            return (st.st_mode & 0111) != 0;
        } catch (Throwable t) {
            return f.canExecute();
        }
    }

    /**
     * 真正执行一次 ELF 来判断能否运行，而非只看 stat 执行位。
     * 某些设备把应用目录挂载为 noexec：chmod 后 stat 有执行位，但 execve 仍返回 EACCES。
     * mode:
     *   "files"  —— 直接 execve(files/bin/proot)
     *   "native" —— 直接 execve(nativeLibraryDir/libproot.so)
     *   "linker" —— execve(/system/bin/linker64, libproot.so)（系统分区可执行，绕过 noexec）
     * 返回 true 表示该模式可真正启动 proot（--version 正常输出）。
     */
    private boolean probeExec(String mode) {
        Process p = null;
        try {
            File proot;
            List<String> cmd = new ArrayList<String>();
            if ("linker".equals(mode)) {
                proot = new File(nativeLibDir, LIB_PROOT);
                cmd.add("/system/bin/linker64");
            } else if ("native".equals(mode)) {
                proot = new File(nativeLibDir, LIB_PROOT);
            } else {
                proot = new File(binDir, "proot");
            }
            if (!proot.exists()) return false;
            cmd.add(proot.getAbsolutePath());
            cmd.add("--version");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            File probeTmp = new File(base, "tmp");
            probeTmp.mkdirs();
            env.put("PROOT_TMP_DIR", probeTmp.getAbsolutePath());
            env.put("TMPDIR", probeTmp.getAbsolutePath());
            env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + nativeLibDir.getAbsolutePath());
            env.put("PROOT_LOADER", new File(binDir, "loader").getAbsolutePath());
            String php = System.getenv("PATH");
            env.put("PATH", (php == null || php.length() == 0) ? "/system/bin:/system/xbin" : php);
            pb.redirectErrorStream(true);
            p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 4096) break;
            }
            int rc = p.waitFor();
            String out = sb.toString();
            // --version 成功时输出形如 "proot 5.x.x"，且退出码 0
            return rc == 0 && (out.contains("proot") || out.contains("Proot"));
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
    }

    /**
     * 按当前 execMode 构造 proot 启动命令前缀（可执行文件 + 可能的 linker64）。
     * 返回 null 表示模式未知。
     */
    private List<String> buildLaunch() {
        List<String> cmd = new ArrayList<String>();
        if ("linker".equals(execMode)) {
            if (!new File("/system/bin/linker64").exists()) return null;
            cmd.add("/system/bin/linker64");
            cmd.add(new File(nativeLibDir, LIB_PROOT).getAbsolutePath());
        } else if ("native".equals(execMode)) {
            cmd.add(new File(nativeLibDir, LIB_PROOT).getAbsolutePath());
        } else if ("files".equals(execMode)) {
            cmd.add(new File(binDir, "proot").getAbsolutePath());
        } else {
            return null;
        }
        return cmd;
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

                    if (cb != null) cb.onProgress("校验执行权限（真实探测）…", 85);
                    File prootF = new File(binDir, "proot");
                    File loaderF = new File(binDir, "loader");
                    forceExec(prootF); forceExec(loaderF);

                    /* 关键：stat 执行位不可靠（noexec 挂载下 chmod 成功仍无法 execve）。
                     * 用真实执行 proot --version 逐级探测，选第一个真正可用的模式。 */
                    String chosen = null;
                    if (probeExec("files")) {
                        chosen = "files";
                    } else {
                        if (cb != null) cb.onProgress("私有目录不可执行，尝试系统库目录…", 86);
                        File np = new File(nativeLibDir, LIB_PROOT);
                        File nl = new File(nativeLibDir, LIB_LOADER);
                        if (np.exists() && nl.exists()) {
                            forceExec(np); forceExec(nl);
                            setupLibLinks();
                            if (probeExec("native")) {
                                chosen = "native";
                            } else if (probeExec("linker")) {
                                chosen = "linker";
                            }
                        }
                    }
                    if (chosen == null) {
                        throw new Exception("应用无法执行内置 proot（私有目录被 noexec 限制，且系统库目录兜底不可用）。" +
                                "该设备的系统策略禁止应用内执行代码，内置 Linux 环境无法启用。");
                    }
                    execMode = chosen;
                    binExecOk = "files".equals(chosen);

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

    /**
     * 当应用私有目录 noexec 时，为动态链接器建立 soname → nativeLibraryDir 的符号链接。
     * 链接文件本身位于 noexec 目录无妨；linker 最终 mmap 的目标在 nativeLibraryDir（可执行）。
     */
    private void setupLibLinks() {
        linkLib("libtalloc.so.2", LIB_TALLOC);
        linkLib("libandroid-shmem.so", LIB_SHMEM);
    }

    private void linkLib(String soname, String nativeName) {
        try {
            File src = new File(nativeLibDir, nativeName);
            if (!src.exists()) return;   // 无 native 库时保留解压版
            File dst = new File(libDir, soname);
            if (dst.exists() && !dst.delete()) return;
            Os.symlink(src.getAbsolutePath(), dst.getAbsolutePath());
        } catch (Throwable t) {
            /* 保留解压版依赖库 */
        }
    }

    private void extractAsset(String asset, File dst, boolean executable) throws Exception {
        if (dst.exists() && dst.length() > 0) {
            if (executable) forceExec(dst);
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
        if (executable) forceExec(dst);
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
                if ((mode & 0111) != 0) forceExec(f);
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
        /* 执行前自愈：重新修复 files 目录执行位（对 noexec 无效但无害），并校验当前模式仍可用 */
        forceExec(new File(binDir, "proot"));
        forceExec(new File(binDir, "loader"));

        // 按探测到的实际可用模式构造启动命令（files / native / linker64）
        List<String> launch = buildLaunch();
        if (launch == null) {
            if (cb != null) cb.onLine("[错误] 执行模式未就绪，请重新执行 linux init\n");
            return -1;
        }
        // loader 必须是 proot 能 execve 的那一份：files 模式用 bin 目录，其余用 nativeLibraryDir
        final File loader = "files".equals(execMode)
                ? new File(binDir, "loader")
                : new File(nativeLibDir, LIB_LOADER);

        final long id = ++seq;
        List<String> cmd = new ArrayList<String>(launch);
        cmd.add("-r"); cmd.add(rootfs.getAbsolutePath());
        cmd.add("-0");                       // 伪装 root（apt 需要）
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/sdcard:/sdcard");
        cmd.add("-w"); cmd.add("/root");
        cmd.add("/bin/bash"); cmd.add("-lc");
        /* 每条命令：source 环境 → 恢复上次工作目录 → 用户命令 → 保存工作目录 → 输出真实 PS1。
         * PS1 用 \001P: 前缀标记，Java 读取线程识别后经 onPrompt 回传，前端不作为普通输出显示。
         * 这样提示符严格等于系统 bash 的 PS1（root 为 #，cd 会改变路径）。 */
        String script = ". /etc/shittim/env.sh 2>/dev/null; " +
                "export PROOT_LOADER='" + loader.getAbsolutePath() + "'; " +
                "[ -f /etc/shittim/cwd ] && cd \"$(cat /etc/shittim/cwd 2>/dev/null)\" 2>/dev/null; " +
                command + "; " +
                "__shittim_rc=$?; " +
                "pwd > /etc/shittim/cwd 2>/dev/null; " +
                "printf '\\001P:%s@%s:%s%s \\n' " +
                "\"$(whoami)\" \"$(hostname)\" \"${PWD/$HOME/~}\" " +
                "\"$([ $(id -u) = 0 ] && echo '#' || echo '$')\"; " +
                "exit $__shittim_rc";
        cmd.add(script);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        // 可写临时目录：proot 编译时默认指向 Termux 的 /data/data/com.termux/files/usr/tmp/，
        // 在本应用不存在，会导致 "can't create glue roots" 进而 /bin/bash not found。
        File tmpDir = new File(base, "tmp");
        tmpDir.mkdirs();
        String tmpPath = tmpDir.getAbsolutePath();
        env.put("PROOT_TMP_DIR", tmpPath);          // 关键：proot 创建 glue roots 用
        env.put("TMPDIR", tmpPath);
        env.put("PROOT_LOADER", loader.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + nativeLibDir.getAbsolutePath());
        // Android 的 System.getenv("PATH") 常为 null；给宿主进程一个明确 PATH，
        // guest 内 PATH 由 env.sh 覆盖为 GNU 标准路径，避免 $PATH=(null)。
        String hostPath = System.getenv("PATH");
        env.put("PATH", (hostPath == null || hostPath.length() == 0)
                ? "/system/bin:/system/xbin:/vendor/bin" : hostPath);
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
                            if (line.startsWith("\u0001P:")) {
                                // 提示符标记行：回传真实 PS1，不作为普通输出回显
                                if (cb != null) cb.onPrompt(line.substring(3));
                            } else if (cb != null) cb.onLine(line + "\n");
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
            String m = t.getMessage() == null ? t.toString() : t.getMessage();
            String hint = "";
            if (m.contains("Permission denied") || m.contains("error=13")) {
                hint = "（执行权限被拒绝，当前模式=" + execMode +
                       "：已用真实执行探测并尝试 files/系统库目录/linker64 全部模式，" +
                       "该设备系统策略可能禁止应用内执行代码；请把完整报错反馈开发者）";
            }
            if (cb != null) cb.onLine("[错误] 无法启动 proot：" + m + hint + "\n");
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
        /** 收到 bash 真实生成的提示符（user@host:path#），可覆盖 UI 提示符。 */
        void onPrompt(String prompt);
    }
}
