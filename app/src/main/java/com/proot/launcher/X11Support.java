package com.proot.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * termux-x11 集成（v21 真正启动 X server；v26 起不再内置 termux-x11 APK）：
 *  - termux-x11 显示端 app：用户手动安装（未装时跳浏览器 releases 页面）；
 *  - 内置 loader.apk（assets/x11/loader.apk，3.3KB stub dex）：
 *    PL 自己用 /system/bin/app_process 跑它，它会拉起 com.termux.x11 的 Service
 *    在 app 进程里 dlopen libtermux-x11.so，真正启动 X server 监听 :0。
 *
 * v21 之前只做了 launchTermuxX11（跳 MainActivity 显示壳），X server 没起来，
 * 所以 rootfs 里 DISPLAY=:0 永远连不上。现在补全 loader.apk 这一步。
 */
public final class X11Support {
    private static final String TAG = "X11Support";

    private X11Support() {}

    // ================================================================
    // X11 模式开关 / guest 环境注入
    // ================================================================

    public static boolean isX11Enabled(Context ctx) {
        return prefs(ctx).getBoolean(Config.KEY_X11_ENABLED, false);
    }

    public static void setX11Enabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(Config.KEY_X11_ENABLED, enabled).apply();
    }

    /** 追加到 rootfs 会话的 shell export 行。X11 开启时注入 DISPLAY=:0。 */
    public static String guestEnvPrefix(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (isX11Enabled(ctx)) {
            sb.append("DISPLAY=").append(Config.X11_DISPLAY).append(" ");
        }
        sb.append("XDG_RUNTIME_DIR=/tmp ");
        return sb.toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("proot_launcher", Context.MODE_PRIVATE);
    }

    // ================================================================
    // termux-x11 显示端 app：检查 / 安装 / 拉起
    // ================================================================

    public static boolean isTermuxX11Installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(Config.TERMUX_X11_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * v23：termux-x11 不再自动下载安装，改成跳浏览器打开 releases 页面。
     * 用户手动下载 APK 并安装后，回到 App 再点 X11 即可。
     * 这样避免了：
     *   - 内置 APK 体积过大
     *   - 自动下载被网络限制 / 代理失效
     *   - 版本过旧导致 loader 崩溃（exit code=134）
     */
    public static void installTermuxX11(Context ctx) throws Exception {
        String releasesUrl = "https://github.com/termux/termux-x11/releases";
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl));
        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(browser);
            Log.i(TAG, "opened browser for termux-x11 releases: " + releasesUrl);
        } catch (Exception e) {
            throw new IOException("无法打开浏览器，请手动访问: " + releasesUrl);
        }
    }

    /**
     * 拉起已安装的 termux-x11 显示端 Activity（SurfaceView）。
     * 未安装返回 false（由 MainActivity 引导用户去浏览器下载）。
     */
    public static boolean launchTermuxX11(Context ctx) {
        Intent launcher = ctx.getPackageManager().getLaunchIntentForPackage(Config.TERMUX_X11_PKG);
        if (launcher == null) return false;
        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(launcher);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "launch termux-x11 failed", e);
            return false;
        }
    }

    // ================================================================
    // X server 启动：app_process 跑 loader.apk（核心新增）
    // ================================================================

    /** 持有 loader 进程（必须被引用，防止 GC 回收导致 X server 被杀）。 */
    private static volatile Process loaderProcess;

    /** loader 进程的 stdout/stderr 收集（签名校验失败/类找不到时会打印在这里）。 */
    private static final StringBuilder loaderLog = new StringBuilder();

    /** 取最近一次 loader 的输出（供 UI 弹错误用）。 */
    public static synchronized String getLoaderLog() {
        return loaderLog.toString();
    }

    private static synchronized void appendLoaderLog(String s) {
        loaderLog.append(s);
        if (loaderLog.length() > 4000) loaderLog.delete(0, loaderLog.length() - 4000);
    }

    /**
     * 解压内置 loader.apk 到 files/x11/loader.apk，幂等。
     */
    public static File extractLoaderApk(Context ctx) throws IOException {
        File f = new File(ctx.getFilesDir(), "x11/loader.apk");
        f.getParentFile().mkdirs();
        if (!f.exists() || f.length() < 1000) {
            copyAsset(ctx, "x11/loader.apk", f);
            f.setReadable(true, false);
            f.setExecutable(true, false);
        }
        return f;
    }

    /**
     * X server socket 所在目录（宿主侧，PRootLauncher 自己的数据目录内）。
     *
     * termux-x11 的 native X server 把 unix socket 建在 $TMPDIR/.X11-unix/X0
     * （Termux 里 TMPDIR=$PREFIX/tmp，所以 socket 在 $PREFIX/tmp/.X11-unix）。
     * v21 之前没设 TMPDIR，socket 落到了 PRootLauncher 读不到的位置，
     * proot 也没 bind，rootfs 里 DISPLAY=:0 永远连不上。
     * v22：显式把 TMPDIR 指到 files/tmp，socket 落在自己数据目录内，
     * 再由 ProotInstaller bind 进每个 rootfs 的 /tmp/.X11-unix。
     */
    public static File x11SocketDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "tmp/.X11-unix");
        d.mkdirs();
        return d;
    }

    /**
     * 用 /system/bin/app_process 启动 X server（v26 双模式，修复 Android 12+ exit code 134）。
     *
     * 背景：termux-x11 新版（nightly 1.03+，支持 Android 15/16）已取消独立 loader.apk 资产，
     * 官方 README 的 chroot 启动方式改为：
     *   CLASSPATH=$(pm path com.termux.x11 | cut -d: -f2)
     *   /system/bin/app_process / --nice-name=termux-x11 com.termux.x11.CmdEntryPoint :0
     * 即直接以 termux-x11 自己的 APK 为 CLASSPATH、入口类为 CmdEntryPoint。
     *
     * 旧版 loader.apk（2023 年 stub，入口 com.termux.x11.Loader）在 Android 12+ 上
     * 反射调用新版接口失败 → SIGABRT（exit code 134）。
     *
     * 因此 v26 启动顺序：
     *   模式 A（新版，优先）：CLASSPATH=termux-x11 APK 路径 + com.termux.x11.CmdEntryPoint
     *   模式 B（旧版，回退）：CLASSPATH=内置 loader.apk + com.termux.x11.Loader
     * 模式 A 失败且日志表明「找不到 CmdEntryPoint 类」→ 自动切模式 B；
     * 模式 B 仍失败 → 提示升级 termux-x11 到新版。
     *
     * @param xkbRoot rootfs 内 /usr/share/X11/xkb 的宿主绝对路径（chroot 场景必需）
     * 进程后台常驻；调用方之后负责 launchTermuxX11 弹显示端。
     */
    public static boolean startXServerViaLoader(Context ctx, String xkbRoot) {
        synchronized (X11Support.class) { loaderLog.setLength(0); }
        try {
            // 先杀残留
            try {
                Process k = new ProcessBuilder("sh", "-c", "pkill -f 'termux-x11' 2>/dev/null || true").start();
                k.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignore) {}

            // 关键：让 X server 把 socket 建在自己数据目录内（proot 才能 bind 进 rootfs）
            File sockDir = x11SocketDir(ctx);
            File sockFile = new File(sockDir, "X0");
            File altSocket = new File("/tmp/.X11-unix/X0");

            // termux-x11 必须已安装（MainActivity 未装时已引导浏览器）
            String apkPath = termuxX11ApkPath(ctx);
            if (apkPath == null) {
                appendLoaderLog("[termux-x11 未安装，无法启动 X server]");
                return false;
            }
            int ver = termuxX11Version(ctx);
            Log.i(TAG, "termux-x11 installed, versionCode=" + ver + " apk=" + apkPath);

            // ---- 模式 A：新版官方方式（CLASSPATH=termux-x11 APK + CmdEntryPoint） ----
            {
                Process p = startAppProcess(ctx, "com.termux.x11.CmdEntryPoint", apkPath, xkbRoot);
                if (p != null) {
                    int st = waitForSocket(p, sockFile, altSocket, 10_000, 400);
                    if (st == SOCK_UP) return true;
                    if (st == SOCK_TIMEOUT) {
                        // 进程还活着但 socket 未就绪：首次冷启动慢，再给 10 秒
                        int st2 = waitForSocket(p, sockFile, altSocket, 10_000, 500);
                        if (st2 == SOCK_UP) return true;
                        if (p.isAlive()) {
                            Log.w(TAG, "CmdEntryPoint still running but socket not ready in 20s");
                            return true; // 进程存活，保守视为已启动
                        }
                        Log.e(TAG, "CmdEntryPoint exited, code=" + p.exitValue());
                        appendLoaderLog("[CmdEntryPoint exit code=" + p.exitValue() + "]");
                        return false;
                    }
                    // SOCK_EXITED：判断是否为「旧版 termux-x11 无 CmdEntryPoint 类」
                    String log = getLoaderLog();
                    boolean oldVersion = log.contains("CmdEntryPoint")
                        || log.contains("ClassNotFoundException")
                        || log.contains("NoClassDefFoundError")
                        || log.contains("Couldn't find class");
                    if (oldVersion) {
                        Log.i(TAG, "termux-x11 过旧（无 CmdEntryPoint），回退内置 loader");
                    } else {
                        return false; // 新版方式崩溃，日志已留原因
                    }
                }
            }

            // ---- 模式 B：旧版（CLASSPATH=内置 loader.apk + Loader 入口） ----
            {
                File loader = extractLoaderApk(ctx);
                Process p = startAppProcess(ctx, "com.termux.x11.Loader", loader.getAbsolutePath(), xkbRoot);
                if (p == null) return false;
                int st = waitForSocket(p, sockFile, altSocket, 10_000, 400);
                if (st == SOCK_UP) return true;
                if (st == SOCK_TIMEOUT) {
                    if (p.isAlive()) {
                        Log.w(TAG, "Loader still running but socket not ready in 10s");
                        return true;
                    }
                    appendLoaderLog("[Loader exit code=" + p.exitValue() + "]");
                    return false;
                }
                // SOCK_EXITED：旧 loader 在 Android 12+ 上崩（exit 134）→ 明确提示升级
                appendLoaderLog("\n[提示] 内置旧版 loader 启动失败（exit " + p.exitValue() + "）。"
                    + "请升级 termux-x11 到最新 nightly（v1.03+，支持 Android 15/16）后重试。");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "startXServerViaLoader failed", e);
            appendLoaderLog("startXServerViaLoader exception: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 双模式辅助方法
    // ================================================================

    private static final int SOCK_UP = 0;
    private static final int SOCK_EXITED = 1;
    private static final int SOCK_TIMEOUT = 2;

    /** termux-x11 已安装 APK 的 sourceDir（world-readable，PL 可读）；未安装返回 null。 */
    private static String termuxX11ApkPath(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                .getApplicationInfo(Config.TERMUX_X11_PKG, 0);
            return (ai != null) ? ai.sourceDir : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static int termuxX11Version(Context ctx) {
        try {
            return ctx.getPackageManager()
                .getPackageInfo(Config.TERMUX_X11_PKG, 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /**
     * 启动 app_process 进程：`app_process -Xnoimage-dex2oat / --nice-name=termux-x11 <class> :0`。
     * @param clazz 入口类（新版 CmdEntryPoint / 旧版 Loader）
     * @param classpath CLASSPATH 值（termux-x11 APK 路径 或 内置 loader.apk 路径）
     */
    private static Process startAppProcess(Context ctx, String clazz,
                                           String classpath, String xkbRoot) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            "/system/bin/app_process",
            "-Xnoimage-dex2oat",
            "/",
            "--nice-name=termux-x11",
            clazz,
            ":0"
        );
        pb.redirectErrorStream(true);
        // 清理环境，避免 PL 自己的 LD_LIBRARY_PATH 污染
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().remove("LD_PRELOAD");
        // app_process 从 CLASSPATH 环境变量找主类（官方脚本就是 export CLASSPATH）
        pb.environment().put("CLASSPATH", classpath);
        // TMPDIR 决定 X unix socket 的落点；HOME 给 native server 一个可写目录
        pb.environment().put("TMPDIR", new File(ctx.getFilesDir(), "tmp").getAbsolutePath());
        pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
        // xkb 数据目录（chroot/proot 场景必需，README 明确要求）
        if (xkbRoot != null && !xkbRoot.isEmpty()) {
            pb.environment().put("XKB_CONFIG_ROOT", xkbRoot);
            Log.i(TAG, "XKB_CONFIG_ROOT=" + xkbRoot);
        }
        Process p = pb.start();
        loaderProcess = p;
        Log.i(TAG, "app_process started: clazz=" + clazz + " classpath=" + classpath);

        // 后台收集 loader 输出（签名错误/类找不到时打印在这里）
        Thread drain = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.i(TAG, "loader(" + clazz + "): " + line);
                    appendLoaderLog(line + "\n");
                }
            } catch (Exception ignore) {}
        }, "loader-output");
        drain.setDaemon(true);
        drain.start();
        return p;
    }

    /**
     * 轮询等待 X socket 出现。
     * @return SOCK_UP=socket 就绪；SOCK_EXITED=进程已退出；SOCK_TIMEOUT=超时但进程存活
     */
    private static int waitForSocket(Process p, File sockFile, File altSocket,
                                     long timeoutMs, long pollMs) {
        long dead = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dead) {
            if (sockFile.exists() || altSocket.exists()) {
                Log.i(TAG, "X socket ready: "
                    + (sockFile.exists() ? sockFile.getAbsolutePath() : altSocket.getAbsolutePath()));
                return SOCK_UP;
            }
            if (!p.isAlive()) {
                try {
                    Thread.sleep(300);
                    Log.e(TAG, "loader exited early, code=" + p.exitValue());
                    appendLoaderLog("[loader exit code=" + p.exitValue() + "]");
                } catch (InterruptedException | IllegalThreadStateException ignore) {}
                return SOCK_EXITED;
            }
            try { Thread.sleep(pollMs); } catch (InterruptedException ignore) {}
        }
        return SOCK_TIMEOUT;
    }

    // ================================================================
    // assets 解压工具
    // ================================================================

    private static void copyAsset(Context ctx, String assetPath, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }
}
