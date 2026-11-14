package com.proot.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TerminalActivity extends Activity {
    private static final String TAG = "TerminalActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 2001;
    private static final int MAX_TABS = 5;

    /** 正在运行的终端窗口列表（供 MainActivity 显示和关闭） */
    public static final List<TerminalActivity> activeWindows = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final List<TermuxTerminal> terminals = new ArrayList<>();
    private int currentIndex = -1;

    private TextView tvTitle;
    private Button btnClose, btnNewTab, btnMinimize;
    private LinearLayout tabBar;
    private HorizontalScrollView tabScroller;
    private LinearLayout extraKeys;

    private boolean isFallbackShell = false;
    private volatile boolean running = false;
    private long sessionStartAt = 0;

    public String rootfsPath;
    public String rootfsName;
    private String x11Command;
    private boolean realSim = false;
    private ProotInstaller installer;

    private boolean ctrlToggle = false;
    private boolean altToggle = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        tvTitle = findViewById(R.id.tv_title);
        btnClose = findViewById(R.id.btn_close);
        btnNewTab = findViewById(R.id.btn_new_tab);
        btnMinimize = findViewById(R.id.btn_minimize);
        tabBar = findViewById(R.id.tab_bar);
        tabScroller = findViewById(R.id.tab_scroller);
        extraKeys = findViewById(R.id.extra_keys);

        rootfsPath = getIntent().getStringExtra("rootfs_path");
        rootfsName = getIntent().getStringExtra("rootfs_name");
        x11Command = getIntent().getStringExtra("x11_command");
        realSim = "1".equals(getIntent().getStringExtra("real_sim"));
        if (rootfsName != null) tvTitle.setText(rootfsName);

        installer = new ProotInstaller(this);
        activeWindows.add(this);

        btnClose.setOnClickListener(v -> finish());
        btnMinimize.setOnClickListener(v -> goHome());
        btnNewTab.setOnClickListener(v -> newTab());

        buildExtraKeys();
        requestAllFilesAccess();

        // 第一个标签
        newTab();
    }

    // ============================================================
    // 多标签管理
    // ============================================================

    private void newTab() {
        if (terminals.size() >= MAX_TABS) {
            Toast.makeText(this, "最多 " + MAX_TABS + " 个终端", Toast.LENGTH_SHORT).show();
            return;
        }
        ViewGroup container = findViewById(R.id.terminal_container);
        TermuxTerminal term = new TermuxTerminal(this, container);
        terminals.add(term);
        int idx = terminals.size() - 1;
        selectTab(idx);
        rebuildTabBar();

        // 启动 proot（在后台线程准备）
        final TermuxTerminal t = term;
        new Thread(() -> startProotIn(t)).start();
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx >= terminals.size()) return;
        currentIndex = idx;
        for (int i = 0; i < terminals.size(); i++) {
            terminals.get(i).setViewVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        rebuildTabBar();
    }

    private void closeTab(int idx) {
        if (idx < 0 || idx >= terminals.size()) return;
        TermuxTerminal t = terminals.remove(idx);
        t.shutdown();
        // 从 container 移除 view
        ViewGroup container = findViewById(R.id.terminal_container);
        container.removeView(t.getView());
        if (terminals.isEmpty()) {
            finish();
            return;
        }
        if (currentIndex >= terminals.size()) currentIndex = terminals.size() - 1;
        selectTab(currentIndex);
    }

    private TermuxTerminal currentTerm() {
        if (currentIndex < 0 || currentIndex >= terminals.size()) return null;
        return terminals.get(currentIndex);
    }

    private void rebuildTabBar() {
        tabBar.removeAllViews();
        if (terminals.size() <= 1) {
            tabScroller.setVisibility(View.GONE);
            return;
        }
        tabScroller.setVisibility(View.VISIBLE);
        for (int i = 0; i < terminals.size(); i++) {
            Button b = new Button(this);
            b.setText(String.valueOf(i + 1));
            b.setTextSize(11);
            b.setAllCaps(false);
            int pad = dp(10);
            b.setPadding(pad, dp(2), pad, dp(2));
            int finalI = i;
            b.setOnClickListener(v -> selectTab(finalI));
            b.setOnLongClickListener(v -> {
                closeTab(finalI);
                return true;
            });
            if (i == currentIndex) {
                b.setBackgroundColor(0xFF4A6FA5);
                b.setTextColor(0xFFFFFFFF);
            } else {
                b.setBackgroundColor(0xFF2a2a3e);
                b.setTextColor(0xFFAAAAAA);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabBar.addView(b, lp);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ============================================================
    // 快捷键栏
    // ============================================================

    private void buildExtraKeys() {
        addKey("ESC", () -> writeCurrent("\u001b"));
        addKey("TAB", () -> writeCurrent("\t"));
        addKey("CTRL", () -> {
            ctrlToggle = !ctrlToggle;
            TermuxTerminal t = currentTerm();
            if (t != null) t.setCtrlActive(ctrlToggle);
            refreshKeyHighlight();
        });
        addKey("ALT", () -> {
            altToggle = !altToggle;
            TermuxTerminal t = currentTerm();
            if (t != null) t.setAltActive(altToggle);
            refreshKeyHighlight();
        });
        addKey("HOME", () -> writeCurrent("\u001b[H"));
        addKey("↑", () -> writeCurrent("\u001b[A"));
        addKey("END", () -> writeCurrent("\u001b[F"));
        addKey("PGUP", () -> writeCurrent("\u001b[5~"));
        addKey("←", () -> writeCurrent("\u001b[D"));
        addKey("↓", () -> writeCurrent("\u001b[B"));
        addKey("→", () -> writeCurrent("\u001b[C"));
        addKey("PGDN", () -> writeCurrent("\u001b[6~"));
    }

    private Button ctrlBtn, altBtn;

    private void addKey(String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        int pad = dp(8);
        b.setPadding(pad, dp(2), pad, dp(2));
        b.setBackgroundColor(0xFF3a3a4e);
        b.setTextColor(0xFFDDDDDD);
        b.setOnClickListener(v -> {
            onClick.run();
            // CTRL/ALT 是 toggle，其他按完不自动取消
            if (!"CTRL".equals(label) && !"ALT".equals(label)) {
                // 按普通键后清除修饰键
                if (ctrlToggle || altToggle) {
                    ctrlToggle = false;
                    altToggle = false;
                    TermuxTerminal t = currentTerm();
                    if (t != null) { t.setCtrlActive(false); t.setAltActive(false); }
                    refreshKeyHighlight();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        lp.setMargins(dp(2), 0, dp(2), 0);
        extraKeys.addView(b, lp);
        if ("CTRL".equals(label)) ctrlBtn = b;
        if ("ALT".equals(label)) altBtn = b;
    }

    private void refreshKeyHighlight() {
        if (ctrlBtn != null) ctrlBtn.setBackgroundColor(ctrlToggle ? 0xFF4A6FA5 : 0xFF3a3a4e);
        if (altBtn != null) altBtn.setBackgroundColor(altToggle ? 0xFF4A6FA5 : 0xFF3a3a4e);
    }

    private void writeCurrent(String s) {
        TermuxTerminal t = currentTerm();
        if (t != null) t.write(s);
    }

    // ============================================================
    // proot 启动逻辑（与之前一致，改为写入指定 term）
    // ============================================================

    private void appendTo(TermuxTerminal t, String s) {
        if (t != null) t.append(s);
    }

    private void startProotIn(TermuxTerminal t) {
        if (rootfsPath == null) {
            startFallbackShellIn(t);
            return;
        }
        File rootfsDir = new File(rootfsPath);
        if (!rootfsDir.exists()) {
            startFallbackShellIn(t);
            return;
        }
        installer.prepareRuntime();
        String shell = installer.defaultShellPath(rootfsDir);

        File canonicalRootfs;
        try {
            canonicalRootfs = rootfsDir.getCanonicalFile();
        } catch (Exception e) {
            canonicalRootfs = rootfsDir;
        }
        launchProotIn(t, canonicalRootfs, shell);
    }

    private void launchProotIn(TermuxTerminal t, File canonicalRootfs, String shell) {
        try {
            String qemuArch = SimConfig.getQemuArch(this, rootfsName);
            if (!SimConfig.QEMU_NONE.equals(qemuArch)) {
                String realArch = SimConfig.detectRootfsArch(canonicalRootfs);
                if (realArch != null && !realArch.equals(qemuArch)) {
                    SimConfig.setQemuArch(this, rootfsName, realArch);
                    qemuArch = realArch;
                }
                if (!QemuSupport.isHostReady(this, qemuArch)) {
                    try {
                        QemuSupport.ensureInstalled(this, canonicalRootfs, qemuArch);
                    } catch (Exception e) {
                        appendTo(t, "[QEMU] " + e.getMessage() + "\n");
                    }
                }
            }

            String[] cmd;
            if (x11Command != null && !x11Command.trim().isEmpty()) {
                cmd = installer.buildProotExecCommand(canonicalRootfs, x11Command.trim());
            } else if (realSim) {
                cmd = installer.buildTinyContainerCommand(canonicalRootfs, shell);
            } else {
                cmd = installer.buildProotCommand(canonicalRootfs, shell);
            }
            String[] env = installer.buildEnv();

            running = true;
            isFallbackShell = false;
            sessionStartAt = System.currentTimeMillis();
            t.setOnSessionFinished(() -> {
                running = false;
                int code = -1;
                try {
                    if (t.getSession() != null) code = t.getSession().getExitStatus();
                } catch (Exception ignored) { }
                appendTo(t, "\n[进程已退出 code=" + code + "]\n");
            });
            t.start(cmd, getFilesDir().getAbsolutePath(), env);
            startContainerService();
        } catch (Exception e) {
            appendTo(t, "启动失败: " + e.getMessage() + "\n");
            startFallbackShellIn(t);
        }
    }

    private void startFallbackShellIn(TermuxTerminal t) {
        try {
            String[] cmd = installer.buildAndroidShellCommand();
            String[] env = installer.buildAndroidEnv();
            appendTo(t, "\nAndroid Shell (/system/bin/sh)\n");
            running = true;
            isFallbackShell = true;
            t.setOnSessionFinished(() -> running = false);
            t.start(cmd, getFilesDir().getAbsolutePath(), env);
        } catch (Exception e) {
            appendTo(t, "Shell 启动失败: " + e.getMessage() + "\n");
        }
    }

    // ============================================================
    // 权限 / 服务
    // ============================================================

    @SuppressWarnings("deprecation")
    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
                    } catch (Exception e2) { }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void startContainerService() {
        try {
            Intent svc = new Intent(this, ContainerService.class);
            svc.putExtra(ContainerService.EXTRA_ROOTFS_NAME, rootfsName != null ? rootfsName : "rootfs");
            svc.putExtra(ContainerService.EXTRA_ROOTFS_PATH, rootfsPath);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "start ContainerService failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        activeWindows.remove(this);
        try { stopService(new Intent(this, ContainerService.class)); } catch (Exception e) { }
        for (TermuxTerminal t : terminals) t.shutdown();
        terminals.clear();
    }

    /** 最小化：回到主界面，终端进程在后台继续跑（不 finish、不 shutdown） */
    private void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    @Override
    public void onBackPressed() {
        // 返回键 = 回主界面（终端继续后台运行）
        goHome();
    }
}
