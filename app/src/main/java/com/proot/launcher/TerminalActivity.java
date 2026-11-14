package com.proot.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TerminalActivity extends Activity {
    private static final String TAG = "TerminalActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 2001;

    private TermuxTerminal term;
    private boolean isFallbackShell = false;
    private EditText etInput;
    private Button btnSend;
    private Button btnClose;
    private Button btnX11;
    private Button btnBrowser;
    private TextView tvTitle;

    private volatile boolean running = false;
    private long sessionStartAt = 0;

    private String rootfsPath;
    private String rootfsName;
    private String x11Command;
    private boolean realSim = false;
    private ProotInstaller installer;

    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;

    /** 当前缓冲区行数（增量维护，避免每次 append 都对全文 split） */
    private int lineCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        term = new TermuxTerminal(this, findViewById(R.id.terminal_container));
        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        btnClose = findViewById(R.id.btn_close);
        btnX11 = findViewById(R.id.btn_x11);
        btnBrowser = findViewById(R.id.btn_browser);
        tvTitle = findViewById(R.id.tv_title);

        rootfsPath = getIntent().getStringExtra("rootfs_path");
        rootfsName = getIntent().getStringExtra("rootfs_name");
        x11Command = getIntent().getStringExtra("x11_command");
        // v24：神秘入口开启「真实模拟」后，用 Tiny Container 启动命令拉起 proot
        realSim = "1".equals(getIntent().getStringExtra("real_sim"));
        if (rootfsName != null) tvTitle.setText(rootfsName);

        // X11 命令模式：左上角显示「X11」按钮（唤起 termux-x11 服务端界面）
        boolean x11Mode = x11Command != null && !x11Command.trim().isEmpty();
        btnX11.setVisibility(x11Mode ? View.VISIBLE : View.GONE);
        btnBrowser.setVisibility(View.GONE);
        btnX11.setOnClickListener(v -> {
            // v21：不再拉起内置 pulse；音频由用户在 Termux 侧自行处理
            if (!X11Support.launchTermuxX11(this)) {
                Toast.makeText(this, getString(R.string.x11_launch_failed, Config.TERMUX_X11_PKG), Toast.LENGTH_SHORT).show();
            }
        });

        installer = new ProotInstaller(this);

        btnSend.setOnClickListener(v -> sendInput());
        btnClose.setOnClickListener(v -> finish());

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendInput();
                return true;
            }
            return false;
        });

        etInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    navigateHistory(-1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    navigateHistory(1);
                    return true;
                }
            }
            return false;
        });

        requestAllFilesAccess();
        startProot();
    }

    // 项目零 AndroidX 依赖，保留 startActivityForResult 并压制废弃告警
    @SuppressWarnings("deprecation")
    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                appendOutput("[系统] 正在申请所有文件访问权限...\n");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
                    } catch (Exception e2) {
                        appendOutput("[系统] 无法跳转权限设置，请手动授权\n");
                    }
                }
            } else {
                appendOutput("[系统] 已获得所有文件访问权限\n");
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    appendOutput("[系统] 所有文件访问权限已授予\n");
                } else {
                    appendOutput("[系统] 未授予所有文件访问权限，/sdcard 可能无法访问\n");
                }
            }
        }
    }

    private void startProot() {
        if (rootfsPath == null) {
            appendOutput("错误: 未指定 rootfs 路径，进入 Android Shell\n");
            startFallbackShell();
            return;
        }

        File rootfsDir = new File(rootfsPath);
        if (!rootfsDir.exists()) {
            appendOutput("错误: rootfs 目录不存在: " + rootfsPath + "\n");
            startFallbackShell();
            return;
        }

        // 仿照 openclaw：启动前准备 fake /proc|/sys 数据、resolv.conf、
        // 运行时目录，确保 proot 容器内工具（apt/dpkg/网络）可用
        installer.prepareRuntime();

        // 默认交互 shell（v19 方案）：rootfs 的 /bin/sh，其次 bash/zsh
        String shell = installer.defaultShellPath(rootfsDir);

        // Diagnose: 判断 shell 是否可用
        // v29：/bin/sh 在很多 rootfs（alpine / debian bootstrap）里是指向 busybox 的
        // 符号链接，File.exists() 会在宿主文件系统里解析绝对链接（/bin/busybox
        // 在宿主上当然不存在）从而误报「rootfs 解压不完整」。这里用
        // NOFOLLOW_LINKS 判断链接本身，并把 busybox 存在作为兜底。
        File shellFile = new File(rootfsDir, shell.substring(1)); // remove leading /
        boolean shellLinkExists = false;
        try {
            shellLinkExists = java.nio.file.Files.exists(
                shellFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) { }
        boolean busyboxExists = new File(rootfsDir, "bin/busybox").isFile()
            || new File(rootfsDir, "usr/bin/busybox").isFile();
        if (!shellLinkExists && !busyboxExists) {
            appendOutput("[诊断] " + shell + " 不存在！rootfs 可能解压不完整。\n");
            appendOutput("[诊断] rootfs 路径: " + rootfsDir.getAbsolutePath() + "\n");
            appendOutput("[诊断] 请删除此 rootfs 后重新下载解压。\n");
            // List bin directory contents for debugging
            File binDir = new File(rootfsDir, "bin");
            if (binDir.exists() && binDir.isDirectory()) {
                String[] files = binDir.list();
                if (files != null) {
                    appendOutput("[诊断] bin/ 目录内容 (" + files.length + " 个文件):\n");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(files.length, 30); i++) {
                        sb.append(files[i]).append(" ");
                    }
                    if (files.length > 30) sb.append("...");
                    appendOutput(sb.toString() + "\n");
                }
            }
        } else if (!busyboxExists && !shellFile.canExecute()) {
            appendOutput("[诊断] " + shell + " 存在但没有执行权限！\n");
            appendOutput("[诊断] 这是旧版解压的 rootfs，文件权限未正确设置。\n");
            appendOutput("[诊断] 请删除此 rootfs 后重新下载解压（新版已修复权限问题）。\n");
            // Try to fix permissions
            shellFile.setExecutable(true, false);
            appendOutput("[诊断] 已尝试自动修复 " + shell + " 的执行权限。\n");
        } else if (shellLinkExists && !shellFile.canExecute()) {
            appendOutput("[诊断] " + shell + " 是符号链接（宿主侧不解析，容器内指向 busybox），视为可用。\n");
        } else {
            appendOutput("[诊断] " + shell + " 存在且有执行权限。\n");
        }

        // Resolve canonical path to avoid symlink issues (/data/data -> /data/user/0)
        File canonicalRootfs = rootfsDir;
        try {
            canonicalRootfs = rootfsDir.getCanonicalFile();
            appendOutput("[诊断] rootfs 绝对路径: " + rootfsDir.getAbsolutePath() + "\n");
            appendOutput("[诊断] rootfs 规范路径: " + canonicalRootfs.getAbsolutePath() + "\n");
            if (!rootfsDir.getAbsolutePath().equals(canonicalRootfs.getAbsolutePath())) {
                appendOutput("[诊断] 检测到符号链接路径，已转换为规范路径。\n");
            }
        } catch (Exception e) {
            appendOutput("[诊断] 无法获取规范路径: " + e.getMessage() + "\n");
        }

        // Also fix permissions for all files in bin/ directory
        File binDir = new File(canonicalRootfs, "bin");
        if (binDir.exists() && binDir.isDirectory()) {
            File[] binFiles = binDir.listFiles();
            if (binFiles != null) {
                int fixed = 0;
                for (File f : binFiles) {
                    if (f.isFile() && !f.canExecute()) {
                        f.setExecutable(true, false);
                        fixed++;
                    }
                }
                if (fixed > 0) {
                    appendOutput("[诊断] 已自动修复 bin/ 目录下 " + fixed + " 个文件的执行权限。\n");
                }
            }
        }

        // v21：不再内置/拉起 pulseaudio，直接进 rootfs。
        // 音频需自行在 Termux 侧起 pulse TCP daemon 并 export PULSE_SERVER。
        final File cf = canonicalRootfs;
        new Thread(() -> launchProot(cf, shell)).start();
    }

    private void launchProot(File canonicalRootfs, String shell) {
        try {
            // v25：跨架构模拟（真实模拟）——先确保 qemu 模拟器可用
            String qemuArch = SimConfig.getQemuArch(this, rootfsName);
            if (!SimConfig.QEMU_NONE.equals(qemuArch)) {
                // v31：自动校验 rootfs 真实架构，防止模拟器选错
                // （x86_64 的 rootfs 配 qemu-i386 会直接 execve Permission denied）
                String realArch = SimConfig.detectRootfsArch(canonicalRootfs);
                if (realArch != null) {
                    appendOutput("[诊断] rootfs 实际架构: " + realArch + "\n");
                    if (!realArch.equals(qemuArch)) {
                        appendOutput("[诊断] 所选模拟器 " + qemuArch + " 与实际架构不符，已自动切换为 "
                            + realArch + "\n");
                        SimConfig.setQemuArch(this, rootfsName, realArch);
                        qemuArch = realArch;
                    }
                }
                appendOutput("[QEMU] 跨架构模拟: " + qemuArch
                    + "（内置 qemu-user " + SimConfig.qemuPkgArch() + "，离线）\n");
                if (!QemuSupport.isHostReady(this, qemuArch)) {
                    appendOutput("[QEMU] 正在部署 APK 内置模拟器（无需联网）...\n");
                    try {
                        appendOutput("[QEMU] " + QemuSupport.ensureInstalled(this, canonicalRootfs, qemuArch) + "\n");
                    } catch (Exception e) {
                        appendOutput("[QEMU] 内置模拟器部署失败: " + e.getMessage() + "\n");
                    }
                } else {
                    File hq = QemuSupport.hostQemu(this, qemuArch);
                    appendOutput("[QEMU] 模拟器已就绪: " + (hq == null ? "?" : hq.getAbsolutePath()) + "\n");
                }
            }

            String[] cmd;
            if (x11Command != null && !x11Command.trim().isEmpty()) {
                // X11 命令模式：非交互，跑用户命令（buildProotExecCommand 已注入 DISPLAY）
                cmd = installer.buildProotExecCommand(canonicalRootfs, x11Command.trim());
                appendOutput("[X11] 运行: " + x11Command.trim() + "\n");
                appendOutput("[X11] DISPLAY=" + Config.X11_DISPLAY + "\n");
            } else if (realSim) {
                // v24 真实模拟（v25 改名「运行带有容器检测的二进制」）：
                // Tiny Container 启动命令（模板来自 files/config/launch.conf）
                cmd = installer.buildTinyContainerCommand(canonicalRootfs, shell);
                appendOutput("[容器检测] 使用 Tiny Container 启动命令（运行带有容器检测的二进制）\n");
            } else {
                cmd = installer.buildProotCommand(canonicalRootfs, shell);
            }
            String[] env = installer.buildEnv();

            appendOutput("启动 proot...\n");
            appendOutput("RootFS: " + rootfsName + "\n");
            appendOutput("Shell: " + shell + "\n");
            appendOutput("----------------------------------------\n");

            // v34：改由 Termux 的 pty 启动（libtermux.so 的 posix_openpt + forkpty）
            // 这样 rootfs 内的程序 isatty() 为真，ANSI 彩色 / 全屏程序 / resize 全部正常
            running = true;
            isFallbackShell = false;
            sessionStartAt = System.currentTimeMillis();
            term.setOnSessionFinished(() -> {
                running = false;
                if (isFallbackShell) return;
                long alive = System.currentTimeMillis() - sessionStartAt;
                if (alive < 3000) {
                    appendOutput("\nproot 启动失败，自动进入 Android Shell...\n");
                    startFallbackShell();
                }
            });
            term.start(cmd, getFilesDir().getAbsolutePath(), env);

            // v24：前台服务保活 —— app 退到后台后进程不被冻结，容器不断网
            startContainerService();

        } catch (Exception e) {
            appendOutput("proot 启动失败: " + e.getMessage() + "\n");
            appendOutput("自动进入 Android Shell (/system/bin/sh)...\n");
            Log.e(TAG, "Failed to start proot", e);
            startFallbackShell();
        }
    }

    private void startFallbackShell() {
        running = false;
        if (term != null) term.shutdown();

        try {
            String[] cmd = installer.buildAndroidShellCommand();
            String[] env = installer.buildAndroidEnv();

            appendOutput("\n========================================\n");
            appendOutput("Android Shell (/system/bin/sh)\n");
            appendOutput("PATH 包含: " + installer.getBinDir().getAbsolutePath() + ":/system/bin\n");
            appendOutput("========================================\n");

            running = true;
            isFallbackShell = true;
            sessionStartAt = System.currentTimeMillis();
            term.setOnSessionFinished(() -> {
                running = false;
                appendOutput("\nShell 已退出\n");
            });
            term.start(cmd, getFilesDir().getAbsolutePath(), env);

        } catch (Exception e) {
            appendOutput("Android Shell 启动失败: " + e.getMessage() + "\n");
            Log.e(TAG, "Failed to start fallback shell", e);
        }
    }

    /** v24：启动容器前台保活服务（通知栏常驻 + WakeLock）。 */
    private void startContainerService() {
        try {
            Intent svc = new Intent(this, ContainerService.class);
            svc.putExtra(ContainerService.EXTRA_ROOTFS_NAME,
                rootfsName != null ? rootfsName : "rootfs");
            svc.putExtra(ContainerService.EXTRA_ROOTFS_PATH, rootfsPath);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "start ContainerService failed: " + e.getMessage());
        }
    }

    private void sendInput() {
        String cmd = etInput.getText().toString();
        if (running && term != null) {
            term.write(cmd + "\r");
            history.add(cmd);
            historyIndex = history.size();
        }
        etInput.setText("");
    }

    private void navigateHistory(int dir) {
        if (history.isEmpty()) return;
        historyIndex += dir;
        if (historyIndex < 0) historyIndex = 0;
        if (historyIndex >= history.size()) {
            historyIndex = history.size();
            etInput.setText("");
            return;
        }
        etInput.setText(history.get(historyIndex));
        etInput.setSelection(etInput.getText().length());
    }

    private void appendOutput(String text) {
        term.append(text);
    }

    private static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        // v24：会话结束，停掉容器保活服务（释放通知与 WakeLock）
        try { stopService(new Intent(this, ContainerService.class)); } catch (Exception e) { /* ignore */ }
        if (term != null) term.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (running && term != null) {
            term.write("\u0004");
            Toast.makeText(this, getString(R.string.toast_ctrl_d), Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }
}
