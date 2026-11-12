package com.proot.launcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * v24：内置 pulseaudio 17.0（Termux 构建）在本 App 沙盒内运行。
 *
 * 发声链路：
 *  容器 app → TCP 4713 → pulseaudio → sink → 喇叭
 *  - 策略 A（默认）：module-pipe-sink → FIFO → Java AudioTrack 直接播 PCM
 *    （绕开 module-sles-sink 在 Android 14/16 上的兼容性问题）
 *  - 策略 B：module-sles-sink（OpenSL ES，老机型）
 *  - 策略 C：module-null-sink（保底，至少 TCP 能连上）
 *
 * v24 修复：
 *  - 多策略自动降级：A 失败自动换 B，再换 C，不再“启动一次就躺平”
 *  - 日志落盘 files/audio/pulse.log：进程被 SIGKILL/闪退也不丢日志，
 *    诊断框直接展示文件路径与尾部内容，杜绝“日志什么都没有”
 *  - --log-target=stderr 显式指定，防止 pulse 把日志写进系统日志
 */
public class AudioSupport {
    private static final String TAG = "AudioSupport";
    public static final int PORT = 4713;
    private static volatile boolean started = false;
    private static volatile String lastError = "";
    private static volatile String lastStrategy = "none";
    private static final StringBuilder pulseLog = new StringBuilder();
    private static Process pulseProc;
    private static File logFile;

    public static String getDiag() { return lastError; }

    /** 诊断按钮用：强制重启一次 pulse 并返回可读状态文本。 */
    public static synchronized String forceRestartAndDiag(Context ctx) {
        started = false;
        lastError = "";
        lastStrategy = "none";
        synchronized (pulseLog) { pulseLog.setLength(0); }
        logFile = null;
        // 先把桥接干净停掉：否则旧 pulse 被 destroy 时 FIFO 读到 EOF，
        // 会把刚起来的桥一起拖死（v27 真机上「桥接 ❌ 未运行」就是这么来的）
        AudioBridge.stop();
        if (pulseProc != null) { pulseProc.destroy(); pulseProc = null; }
        ensureStarted(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("端口 4713: ").append(started ? "✅ 已监听" : "❌ 未监听").append('\n');
        sb.append("启动策略: ").append(lastStrategy).append('\n');
        sb.append("AudioTrack 桥接: ").append(AudioBridge.isRunning() ? "✅ 运行中" : "❌ 未运行")
          .append("（FIFO 累计重开 ").append(AudioBridge.getFifoReopens()).append(" 次）");
        if (!AudioBridge.getLastError().isEmpty())
            sb.append(" (").append(AudioBridge.getLastError()).append(')');
        sb.append('\n');
        sb.append("mediaPlayback 前台服务: ").append(AudioService.isAlive() ? "✅ 存活" : "⚠️ 未存活")
          .append("（Android 14+ 后台出声硬依赖；未存活时系统会静默丢弃这路音频）\n");
        sb.append("----- pulseaudio 输出 -----\n");
        synchronized (pulseLog) {
            sb.append(pulseLog.length() == 0 ? "(无输出)" : pulseLog.toString());
        }
        if (logFile != null) {
            sb.append("\n----- 日志文件 -----\n").append(logFile.getAbsolutePath());
        }
        return sb.toString();
    }

    public static synchronized void ensureStarted(Context ctx) {
        if (started) return;
        try {
            File root = prepare(ctx);
            if (root == null) {
                lastError = "assets/audio/" + currentAbi() + " 未打包";
                Log.w(TAG, lastError);
                return;
            }
            // 策略 A：AudioTrack 桥（pipe-sink → FIFO → AudioTrack）
            // 只有桥接真的拿到焦点并建轨成功才试 A，否则纯属白等 8 秒
            lastStrategy = "pipe-sink(AudioTrack)";
            if (AudioBridge.start(ctx, root)) {
                startPulseOnce(ctx, root, "A");
                started = waitForPort(8000);
                if (started) { Log.i(TAG, "pulseaudio up: " + lastStrategy); return; }
            } else {
                Log.w(TAG, "AudioBridge 不可用: " + AudioBridge.getLastError());
            }

            // A 档不成立：mediaPlayback 前台服务失去意义，收掉
            AudioBridge.stop();
            AudioService.shutdown(ctx);

            // 策略 B：aaudio-sink（AAudio，API 26+；Android 16 上唯一还能初始化的原生 sink）
            if (Build.VERSION.SDK_INT >= 26) {
                lastStrategy = "aaudio-sink(AAudio)";
                writePaConfig(ctx, root, "B");
                startPulseOnce(ctx, root, "B");
                started = waitForPort(6000);
                if (started) { Log.i(TAG, "pulseaudio up: " + lastStrategy); return; }
            }

            // 策略 C：sles-sink（OpenSL ES，仅 API 24/25；Android 14+ 会 error 12）
            if (Build.VERSION.SDK_INT < 26) {
                lastStrategy = "sles-sink(OpenSL ES)";
                writePaConfig(ctx, root, "C");
                startPulseOnce(ctx, root, "C");
                started = waitForPort(6000);
                if (started) { Log.i(TAG, "pulseaudio up: " + lastStrategy); return; }
            }

            // 策略 D：null-sink 保底
            lastStrategy = "null-sink";
            writePaConfig(ctx, root, "D");
            startPulseOnce(ctx, root, "D");
            started = waitForPort(5000);
            Log.i(TAG, "pulseaudio " + (started ? "up: " + lastStrategy : "FAILED (all strategies)"));
        } catch (Exception e) {
            lastError = Log.getStackTraceString(e);
            Log.e(TAG, "pulseaudio start failed", e);
            synchronized (pulseLog) {
                pulseLog.append("\n[ensureStarted 异常] ").append(e.getMessage()).append('\n');
            }
        }
    }

    public static String guestEnvPrefix() {
        return "PULSE_SERVER=tcp:127.0.0.1:" + PORT
             + " PULSE_LATENCY_MSEC=60 PULSE_NO_SPAWN=1 ";
    }

    private static String currentAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return (abis != null && abis.length > 0) ? abis[0] : "arm64-v8a";
    }

    /**
     * v23 修复 A16/Android 10+ noexec bug：
     * files 目录挂载为 noexec，直接 exec files/audio/.../bin/pulseaudio
     * 会报 error=13 Permission denied。解决方案：
     *   1. pulseaudio 主二进制由构建脚本打包进 jniLibs（libpulseaudio.so），
     *      Android 安装时解压到 nativeLibraryDir，该目录可执行。
     *   2. 模块 .so（dlopen 用，不需要 exec 权限）继续从 assets 解压到 files。
     *   3. 旧版 APK 无 jniLibs pulseaudio 时，降级走 files 路径（兼容老设备）。
     */
    private static File prepare(Context ctx) throws Exception {
        String abi = currentAbi();
        File dir = new File(ctx.getFilesDir(), "audio/" + abi);
        dir.mkdirs();

        // 解压模块（lib/*.so，dlopen 用，不需要 exec 权限）
        File libDir = new File(dir, "lib");
        if (!libDir.exists() || libDir.list() == null || libDir.list().length == 0) {
            copyAssetDir(ctx, "audio/" + abi, dir);
        }
        chmodTree(libDir);

        // 优先从 nativeLibraryDir 找 pulseaudio 主二进制（jniLibs 打包，可执行）
        File nativeBin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libpulseaudio.so");
        if (nativeBin.exists() && nativeBin.canExecute()) {
            Log.i(TAG, "pulseaudio from nativeLibraryDir: " + nativeBin.getAbsolutePath());
            return dir;
        }

        // 降级：从 files 执行（仅老设备 / 旧 APK 有效）
        File bin = new File(dir, "bin/pulseaudio");
        if (!bin.exists()) {
            copyAssetDir(ctx, "audio/" + abi, dir);
            if (!bin.exists()) return null;
        }
        bin.setExecutable(true, false);
        return dir;
    }

    /** 返回 pulseaudio 可执行文件路径（优先 nativeLibraryDir）。 */
    private static File getPulseBinary(Context ctx, File root) {
        File nativeBin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libpulseaudio.so");
        if (nativeBin.exists() && nativeBin.canExecute()) {
            return nativeBin;
        }
        return new File(root, "bin/pulseaudio");
    }

    private static void chmodTree(File f) {
        if (f == null || !f.exists()) return;
        f.setReadable(true, false);
        f.setExecutable(true, false);
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) chmodTree(k);
            else {
                k.setReadable(true, false);
                k.setExecutable(true, false);
            }
        }
    }

    /** 写不同策略的 default.pa（模块绝对路径，pulse 见到带 / 直接 dlopen）。 */
    private static void writePaConfig(Context ctx, File root, String strategy) throws Exception {
        File files = ctx.getFilesDir();
        File paConf = new File(files, "audio/default.pa");
        File modules = new File(root, "lib/pulseaudio/modules");
        String mp = modules.getAbsolutePath();
        StringBuilder cfg = new StringBuilder();
        cfg.append("load-module ").append(mp).append("/module-native-protocol-tcp.so")
           .append(" auth-anonymous=1 port=").append(PORT).append(" listen=127.0.0.1\n");
        switch (strategy) {
            case "A": {
                // AudioBridge 已生成同名文件（pipe-sink + set-default-sink），
                // 这里保持与 AudioBridge 一致
                File fifo = new File(files, "audio/run/audio.fifo");
                cfg.append("load-module ").append(mp).append("/module-pipe-sink.so")
                   .append(" sink_name=androidsink format=s16le rate=48000 channels=2")
                   .append(" file=").append(fifo.getAbsolutePath()).append("\n");
                cfg.append("set-default-sink androidsink\n");
                break;
            }
            case "B": {
                // AAudio：Android 16 上唯一还能初始化成功的原生 sink。
                // 该模块只认 sink_name/sink_properties/rate/latency/no_close_hack，
                // 传 channels/format 会被 pulse 判为非法参数导致加载失败。
                cfg.append("load-module ").append(mp).append("/module-aaudio-sink.so")
                   .append(" sink_name=androidsink rate=48000\n");
                cfg.append("set-default-sink androidsink\n");
                break;
            }
            case "C": {
                cfg.append("load-module ").append(mp).append("/module-sles-sink.so")
                   .append(" sink_name=sles rate=48000 channels=2\n");
                cfg.append("set-default-sink sles\n");
                break;
            }
            case "D": {
                cfg.append("load-module ").append(mp).append("/module-null-sink.so")
                   .append(" sink_name=nullsink rate=48000 channels=2\n");
                cfg.append("set-default-sink nullsink\n");
                break;
            }
        }
        FileOutputStream out = new FileOutputStream(paConf);
        out.write(cfg.toString().getBytes("UTF-8"));
        out.close();
    }

    /** 启动 pulse（前台模式，输出同时进 pulseLog 与落盘文件）。 */
    private static void startPulseOnce(Context ctx, File root, String strategy) throws Exception {
        File files = ctx.getFilesDir();
        File run = new File(files, "audio/run");
        run.mkdirs();
        File stalePid = new File(run, "pid");
        if (stalePid.exists()) stalePid.delete();
        File bin = getPulseBinary(ctx, root);
        if (!bin.exists() || !bin.canExecute()) {
            throw new IOException("pulseaudio 二进制不存在或不可执行: " + bin.getAbsolutePath());
        }
        File paConf = new File(files, "audio/default.pa");
        logFile = new File(files, "audio/pulse.log");

        // 策略 A 的 default.pa 由 AudioBridge 生成；B/C 由 writePaConfig 生成
        if ("A".equals(strategy) && !paConf.exists()) {
            AudioBridge.start(ctx, root);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("--exit-idle-time=-1");
        cmd.add("--disallow-exit");
        cmd.add("--disable-shm");
        cmd.add("--use-pid-file=no");
        cmd.add("--system=no");
        cmd.add("--log-level=4");
        cmd.add("--log-time=1");
        cmd.add("--log-target=stderr");
        cmd.add("--file=" + paConf.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("HOME", files.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", new File(root, "lib").getAbsolutePath());
        pb.environment().put("TMPDIR", new File(files, "tmp").getAbsolutePath());
        pb.environment().put("PULSE_RUNTIME_PATH", run.getAbsolutePath());
        pb.redirectErrorStream(true);
        pulseProc = pb.start();
        final Process p = pulseProc;
        final File log = logFile;
        new Thread(() -> {
            // 输出同时进内存 pulseLog（诊断框）与落盘文件（SIGKILL 也不丢）
            FileOutputStream fos = null;
            try { fos = new FileOutputStream(log, true); } catch (Exception ignored) {}
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) {
                    String s = new String(buf, 0, n, "UTF-8");
                    synchronized (pulseLog) {
                        pulseLog.append(s);
                        if (pulseLog.length() > 8000)
                            pulseLog.delete(0, pulseLog.length() - 4000);
                    }
                    if (fos != null) {
                        try { fos.write(buf, 0, n); fos.flush(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            synchronized (pulseLog) {
                pulseLog.append("\n[pulse 进程退出]\n");
                // 进程提前退出：把落盘日志尾部补进来，方便定位
                String tail = readTail(log, 1200);
                if (!tail.isEmpty()) pulseLog.append(tail);
            }
        }, "pulse-drain").start();
    }

    /** 读日志文件尾部（进程被强杀后诊断仍可见）。 */
    private static String readTail(File f, int maxChars) {
        if (f == null || !f.exists()) return "";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxChars * 2L);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String s = new String(buf, "UTF-8");
            return s.length() > maxChars ? s.substring(s.length() - maxChars) : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean waitForPort(long timeoutMs) {
        long dead = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dead) {
            try (Socket s = new Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 500);
                return true;
            } catch (Exception e) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    private static void copyAssetDir(Context ctx, String assetDir, File destDir) throws Exception {
        destDir.mkdirs();
        String[] files = ctx.getAssets().list(assetDir);
        if (files == null) return;
        for (String name : files) {
            String child = assetDir + "/" + name;
            File dest = new File(destDir, name);
            String[] kids = ctx.getAssets().list(child);
            if (kids != null && kids.length > 0) {
                copyAssetDir(ctx, child, dest);
            } else {
                try (InputStream in = ctx.getAssets().open(child);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
            }
        }
    }
}
