package com.proot.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Web 终端模式：自动识别 rootfs 发行版，在 proot 里装 ttyd，
 * 然后启动 TtydService（前台服务）后台持有 ttyd，跳浏览器。
 *
 * 与旧版区别：ttyd 进程由 TtydService 持有，不是 Activity 的子进程。
 * 切到浏览器/系统杀后台 Activity 都不会影响 ttyd。
 */
public class WebTerminalActivity extends Activity {
    private static final String TAG = "WebTerminalActivity";

    private TextView tvOutput;
    private ScrollView scrollOutput;
    private EditText etInput;
    private Button btnSend;
    private Button btnClose;
    private Button btnBrowser;
    private TextView tvTitle;

    private ProotInstaller installer;
    private String rootfsPath;
    private String rootfsName;
    private String currentUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_terminal);

        tvOutput = findViewById(R.id.tv_output);
        scrollOutput = findViewById(R.id.scroll_output);
        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        btnClose = findViewById(R.id.btn_close);
        btnBrowser = findViewById(R.id.btn_browser);
        tvTitle = findViewById(R.id.tv_title);

        etInput.setVisibility(View.GONE);
        btnSend.setVisibility(View.GONE);
        btnClose.setText(getString(R.string.btn_close_terminal));
        btnClose.setOnClickListener(v -> {
            // 点关闭：停掉前台服务和 ttyd
            stopService(new Intent(this, TtydService.class));
            finish();
        });

        // 左上角「浏览器」按钮：重新打开 ttyd 页面（切回本页后仍可一键回浏览器）
        btnBrowser.setVisibility(View.VISIBLE);
        btnBrowser.setOnClickListener(v -> openBrowser());

        tvOutput.setMovementMethod(new ScrollingMovementMethod());

        rootfsPath = getIntent().getStringExtra("rootfs_path");
        rootfsName = getIntent().getStringExtra("rootfs_name");
        if (rootfsName != null) {
            tvTitle.setText(getString(R.string.web_terminal_title, rootfsName));
        }

        installer = new ProotInstaller(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) { /* ignore */ }
            }
        }

        startSetup();
    }

    private void append(String s) {
        runOnUiThread(() -> {
            tvOutput.append(s);
            scrollOutput.post(() -> scrollOutput.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** 左上角「浏览器」按钮：重新打开 ttyd 页面。 */
    private void openBrowser() {
        String url = (currentUrl != null && !currentUrl.isEmpty())
            ? currentUrl
            : "http://127.0.0.1:" + Config.TTYD_PORT + "/";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            append("打开浏览器失败，请手动访问: " + url + "\n");
        }
    }

    private void startSetup() {
        if (rootfsPath == null) {
            append("错误: 未指定 rootfs 路径\n");
            return;
        }
        File rootfsDir = new File(rootfsPath);
        if (!rootfsDir.exists()) {
            append("错误: rootfs 不存在: " + rootfsPath + "\n");
            return;
        }
        installer.prepareRuntime();

        // v21：不再内置 pulseaudio，音频由用户在 Termux 侧自行起 daemon

        new Thread(() -> {
            try {
                step1DetectAndInstall(rootfsDir);
                step2StartServiceAndBrowser();
            } catch (Exception e) {
                Log.e(TAG, "setup failed", e);
                append("\n[错误] " + e.getMessage() + "\n");
            }
        }).start();
    }

    private void step1DetectAndInstall(File rootfsDir) throws Exception {
        append("=== 1/2 识别系统并安装 ttyd ===\n");
        String pkg = detectPackageManager(rootfsDir);
        append("包管理器: " + pkg + "\n");

        // Arch Linux ARM 特殊初始化：resolv.conf / pacman-key / 权限
        if ("pacman".equals(pkg)) {
            setupArchLinuxArm(rootfsDir);
        }

        String installCmd = getInstallCommand(pkg);
        append("尝试包管理器安装: " + installCmd + "\n\n");
        int code = runProotCommand(rootfsDir, installCmd, Config.PKG_INSTALL_TIMEOUT_SEC);
        append("退出码: " + code + "\n\n");

        append("验证 ttyd...\n");
        code = runProotCommand(rootfsDir, "which ttyd || command -v ttyd || ls -la /usr/local/bin/ttyd", Config.QUICK_TIMEOUT_SEC);
        if (code != 0) {
            append("\n包管理器装不上（debian bookworm 等官方仓库无 ttyd），\n");
            append("改用 GitHub 静态二进制（镜像 v4.gh-proxy.org）...\n");
            installTtydStaticBinary(rootfsDir, pkg);
            code = runProotCommand(rootfsDir, "which ttyd || command -v ttyd || ls -la /usr/local/bin/ttyd", Config.QUICK_TIMEOUT_SEC);
            if (code != 0) {
                throw new RuntimeException("ttyd 安装失败，请检查网络后重试");
            }
        }
        append("ttyd 就绪 ✓\n\n");
    }

    private void installTtydStaticBinary(File rootfsDir, String pkg) throws Exception {
        String asset;
        if (android.os.Build.SUPPORTED_64_BIT_ABIS != null
                && android.os.Build.SUPPORTED_64_BIT_ABIS.length > 0
                && android.os.Build.SUPPORTED_64_BIT_ABIS[0].contains("arm64")) {
            asset = "ttyd.aarch64";
        } else {
            asset = "ttyd.arm";
        }
        // GitHub 加速代理镜像：v4.gh-proxy.org（原 github.1zyq1.com 已失效）
        String url = "https://v4.gh-proxy.org/https://github.com/tsl0922/ttyd/releases/download/1.7.7/" + asset;
        append("下载: " + url + "\n");

        // 先保证有 wget + CA 证书（部分精简 rootfs 未预装，且无 CA 时
        // HTTPS 仓库/下载会 TLS 握手失败），再用 wget 下载
        append("确保 wget 与 CA 证书已安装...\n");
        runProotCommand(rootfsDir,
            "command -v wget >/dev/null 2>&1 || (" + pkgInstallWget(pkg) + ") || true", Config.DOWNLOAD_TIMEOUT_SEC);

        String dlCmd =
            "mkdir -p /usr/local/bin && " +
            "(wget -q --tries=3 --timeout=30 -O /usr/local/bin/ttyd " + url + " || " +
            "curl -L -s --retry 3 --connect-timeout 30 -o /usr/local/bin/ttyd " + url + ") && " +
            "chmod +x /usr/local/bin/ttyd && " +
            "ls -la /usr/local/bin/ttyd";
        int code = runProotCommand(rootfsDir, dlCmd, Config.DOWNLOAD_TIMEOUT_SEC);
        if (code != 0) {
            throw new RuntimeException("下载 ttyd 静态二进制失败 (exit=" + code + ")");
        }
    }

    /** 按包管理器生成「安装 wget + CA 证书」的命令（best effort，失败由调用方忽略）。
     *  精简 rootfs（如 alpine minirootfs）默认没有 CA 证书，https 仓库/下载会
     *  TLS 握手失败，因此必须先装 ca-certificates。 */
    private String pkgInstallWget(String pkg) {
        switch (pkg) {
            case "apt":    return "apt-get update && apt-get install -y ca-certificates wget";
            case "apk":    return "apk update && apk add ca-certificates wget";
            case "pacman": return "pacman -S --disable-sandbox --noconfirm ca-certificates wget";
            case "dnf":    return "dnf install -y ca-certificates wget";
            case "zypper": return "zypper --non-interactive install ca-certificates wget";
            default:       return "true";
        }
    }

    /**
     * Arch Linux ARM 特殊初始化：
     *  - 确保 /etc/resolv.conf 正确（proot bind 之外的兜底）
     *  - 初始化 pacman-key（否则 pacman -Syu 报 signature is not trustable）
     *  - 修关键二进制的 setuid 权限（sudo/su 等）
     * 所有命令 || true，失败不中断。
     */
    private void setupArchLinuxArm(File rootfsDir) throws Exception {
        append("--- Arch ARM 特殊初始化 ---\n");

        // 1. resolv.conf 兜底（proot 已 bind /etc/resolv.conf，但容器里可能被覆盖）
        runProotCommand(rootfsDir,
            "echo 'nameserver 8.8.8.8' > /etc/resolv.conf && " +
            "echo 'nameserver 223.5.5.5' >> /etc/resolv.conf && " +
            "chmod 644 /etc/resolv.conf || true", 15);

        // 2. pacman-key 初始化（ARM 版密钥环名是 archlinuxarm）
        append("初始化 pacman-key...\n");
        runProotCommand(rootfsDir,
            "pacman-key --init || true; " +
            "pacman-key --populate archlinuxarm || pacman-key --populate archlinux || true",
            120);

        // 3. 修权限：sudo/su 等 setuid 位（proot 解压后可能丢）
        runProotCommand(rootfsDir,
            "chmod u+s /usr/bin/sudo /usr/bin/su /usr/bin/passwd /usr/bin/chsh /usr/bin/chfn 2>/dev/null; " +
            "chmod 755 /usr/bin/sudo /usr/bin/su 2>/dev/null; true", 15);

        // 4. 关键目录可写
        runProotCommand(rootfsDir,
            "mkdir -p /var/cache/pacman/pkg /var/lib/pacman /var/log /tmp && " +
            "chmod 777 /tmp /var/cache/pacman/pkg 2>/dev/null; true", 15);

        // 5. 关 pacman Landlock 沙箱（proot 内核不支持，否则 pacman -Syu 直接挂）
        append("关闭 pacman 沙箱...\n");
        runProotCommand(rootfsDir,
            "grep -q '^Sandbox' /etc/pacman.conf 2>/dev/null && " +
            "sed -i 's/^Sandbox.*/Sandbox = 0/' /etc/pacman.conf || " +
            "echo 'Sandbox = 0' >> /etc/pacman.conf; true", 15);

        // 6. 补 awk（pacman-key 脚本依赖，基础镜像常缺）
        runProotCommand(rootfsDir,
            "command -v awk >/dev/null 2>&1 || " +
            "(ln -sf /usr/bin/gawk /usr/bin/awk 2>/dev/null; " +
            " ln -sf /usr/bin/mawk /usr/bin/awk 2>/dev/null); true", 10);

        append("Arch 初始化完成（错误已忽略）\n\n");
    }

    /** 启动前台服务（持有 ttyd）+ 申请电池白名单 + 跳浏览器 */
    private void step2StartServiceAndBrowser() {
        append("=== 2/2 启动前台服务 + 打开浏览器 ===\n");

        // 1. 申请电池优化白名单
        requestIgnoreBatteryOptimization();

        // 2. 生成一次性认证 token（防同设备其他 App 直连拿 root shell），
        //    随 Intent 传给 TtydService；浏览器 URL 也带 token。
        String token = TtydService.generateToken();
        Intent svc = new Intent(this, TtydService.class);
        svc.putExtra(TtydService.EXTRA_ROOTFS_PATH, rootfsPath);
        svc.putExtra(TtydService.EXTRA_ROOTFS_NAME, rootfsName);
        svc.putExtra(TtydService.EXTRA_TTYD_TOKEN, token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        append("前台服务已启动 ✓（通知栏有常驻通知）\n");

        // 3. 等 ttyd 绑端口：轮询而非硬编码 2 秒（低端设备 ttyd 启动可能要 3-5 秒）
        boolean up = waitForPort(Config.TTYD_PORT, Config.TTYD_START_TIMEOUT_MS);
        if (!up) {
            append("警告: ttyd 尚未就绪（可能仍在启动），浏览器打开后稍等几秒刷新即可\n");
        }

        // 4. 跳浏览器（带 token 认证）。本方法在 setup 后台线程执行，
        //    startActivity 必须回到 UI 线程（规范要求，也避免个别机型抛
        //    "Calling startActivity() from outside of an Activity context"）。
        String url = "http://127.0.0.1:" + Config.TTYD_PORT + "/?token=" + token;
        currentUrl = url;
        append("打开浏览器: " + url + "\n\n");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        runOnUiThread(() -> {
            try {
                startActivity(i);
                append("================================\n");
                append("完成！浏览器已打开 Web 终端。\n");
                append("通知栏常驻通知 = ttyd 在跑。\n");
                append("想停 ttyd：回本页点「关闭」。\n");
            } catch (Exception e) {
                append("打开浏览器失败，请手动访问: " + url + "\n");
            }
        });
    }

    /** 轮询检测 127.0.0.1:port 是否已可连接（ttyd 绑定端口即返回） */
    private boolean waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (Exception e) {
                try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
            }
        }
        return false;
    }

    /** 申请电池优化白名单（startActivity 回 UI 线程） */
    private void requestIgnoreBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent i = new Intent();
            i.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            runOnUiThread(() -> {
                try {
                    startActivity(i);
                    append("已弹出「关闭电池优化」请求，请选「允许」\n");
                } catch (Exception e2) {
                    append("提示：请到系统设置 → 应用 → PRoot Launcher → 电池 → 不限制\n");
                }
            });
        } catch (Exception e) {
            append("提示：请到系统设置 → 应用 → PRoot Launcher → 电池 → 不限制\n");
        }
    }

    /** 识别 rootfs 发行版的包管理器（apt/apk/pacman/dnf/zypper/unknown）。
     *  static：供其它入口复用。 */
    public static String detectPackageManager(File rootfsDir) {
        File osRelease = new File(rootfsDir, "etc/os-release");
        if (osRelease.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(osRelease.toPath()));
                String lower = content.toLowerCase();
                if (lower.contains("debian") || lower.contains("ubuntu")
                        || lower.contains("mint") || lower.contains("kali")
                        || lower.contains("raspbian") || lower.contains("devuan")
                        || lower.contains("pop!_os") || lower.contains("linuxmint")) {
                    return "apt";
                }
                if (lower.contains("alpine")) return "apk";
                if (lower.contains("arch") || lower.contains("manjaro")
                        || lower.contains("endeavour") || lower.contains("garuda")) {
                    return "pacman";
                }
                if (lower.contains("fedora") || lower.contains("rhel")
                        || lower.contains("centos") || lower.contains("rocky")
                        || lower.contains("almalinux") || lower.contains("oracle")) {
                    return "dnf";
                }
                if (lower.contains("opensuse") || lower.contains("suse")) return "zypper";
            } catch (Exception e) { /* ignore */ }
        }
        if (new File(rootfsDir, "usr/bin/apt-get").exists()) return "apt";
        if (new File(rootfsDir, "usr/bin/apk").exists()) return "apk";
        if (new File(rootfsDir, "usr/bin/pacman").exists()) return "pacman";
        if (new File(rootfsDir, "usr/bin/dnf").exists()) return "dnf";
        if (new File(rootfsDir, "usr/bin/yum").exists()) return "dnf";
        if (new File(rootfsDir, "usr/bin/zypper").exists()) return "zypper";
        return "unknown";
    }

    private String getInstallCommand(String pkg) {
        switch (pkg) {
            case "apt":
                // ca-certificates 前置：精简 rootfs 无 CA 时 https 仓库会 TLS 失败
                return "apt update && apt install -y ca-certificates ttyd";
            case "apk":
                return "apk update && apk add ca-certificates ttyd";
            case "pacman":
                // --disable-sandbox：proot 不支持 Landlock，否则 -Syu 直接失败
                return "pacman -Syu --disable-sandbox --noconfirm && pacman -S --disable-sandbox --noconfirm ttyd";
            case "dnf":
                return "dnf install -y ca-certificates ttyd";
            case "zypper":
                return "zypper refresh && zypper --non-interactive install ca-certificates ttyd";
            default:
                return "echo 'unsupported distro, please install ttyd manually' && false";
        }
    }

    private int runProotCommand(File rootfsDir, String command, long timeoutSec) throws Exception {
        String[] cmd = installer.buildProotExecCommand(rootfsDir, command);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        for (String e : installer.buildEnv()) {
            int eq = e.indexOf('=');
            if (eq > 0) pb.environment().put(e.substring(0, eq), e.substring(eq + 1));
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            append(line + "\n");
        }
        boolean done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            append("[超时] 命令超过 " + timeoutSec + "s\n");
            return -1;
        }
        return p.exitValue();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 不杀 ttyd，由 TtydService 持有；用户点"关闭"按钮时已 stopService。
    }
}
