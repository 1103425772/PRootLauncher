package com.proot.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * v34：基于 Termux 官方 terminal-emulator / terminal-view 的终端视图。
 *
 * 显示层与 ANSI 解析完全使用 Termux 源码（com.termux.terminal + com.termux.view），
 * 进程侧使用 Termux 的 JNI（libtermux.so，termux.c 自带的 posix_openpt + forkpty）
 * 建立真实伪终端 —— 因此：
 *   · ANSI 颜色/256 色/真彩色正常渲染
 *   · 程序 isatty() 为真，能正确输出彩色、进度条、vim/top 等全屏程序
 *   · 双指捏合缩放会同时改变字号并 resize pty（SIGWINCH 生效）
 *   · 手势上下滑动即为终端 scrollback 滚动
 */
public class TermuxTerminal implements TerminalViewClient, TerminalSessionClient {

    private static final String TAG = "TermuxTerminal";
    private static final int DEFAULT_TEXT_SIZE = 11;
    private static final int MIN_TEXT_SIZE = 6;
    private static final int MAX_TEXT_SIZE = 30;

    private final Activity activity;
    private final TerminalView view;
    private TerminalSession session;
    private final StringBuilder pendingOutput = new StringBuilder();
    private TextView statusView;
    private boolean attached = false;
    private boolean ptyFailed = false;
    private boolean pipeModeActive = false;
    private volatile int outputCount = 0;
    private Process pipeProcess;
    private String[] pendingCmd;
    private String pendingCwd;
    private String[] pendingEnv;
    private int textSize = DEFAULT_TEXT_SIZE;
    private Runnable onSessionFinished;

    public TermuxTerminal(Activity activity, ViewGroup container) {
        this.activity = activity;
        this.view = new TerminalView(activity, null);
        view.setTerminalViewClient(this);
        view.setTextSize(textSize);
        view.setTypeface(Typeface.MONOSPACE);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(view, lp);

        // 可视诊断层：Termux 终端建不起来时，屏幕会是纯黑，用户无法提供任何线索。
        // 这里把 session / emulator / JNI 的真实错误直接画在屏幕上。
        statusView = new TextView(activity);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setTextColor(0xFF9BE89B);
        statusView.setGravity(Gravity.TOP | Gravity.START);
        statusView.setPadding(16, 16, 16, 16);
        statusView.setText("Termux 终端初始化中…\n");
        container.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public TerminalView getView() {
        return view;
    }

    public TerminalSession getSession() {
        return session;
    }

    public void setOnSessionFinished(Runnable r) {
        this.onSessionFinished = r;
    }

    /**
     * 用 Termux pty 启动一个进程。
     *
     * @param cmd 完整命令行（cmd[0] 作为 execvp 的目标，同时作为 argv[0]）
     * @param cwd 工作目录
     * @param env "KEY=VALUE" 形式的环境变量（pty 子进程会 clearenv 后只保留这些）
     */
    public void start(String[] cmd, String cwd, String[] env) {
        if (cmd == null || cmd.length == 0) throw new IllegalArgumentException("empty cmd");
        final String[] c = cmd;
        final String[] e = env;
        // TerminalSession 会在构造时 new Handler()，且 TerminalView 操作必须在主线程 ——
        // 调用方（launchProot）跑在后台线程，这里统一切回主线程再初始化
        activity.runOnUiThread(() -> startInternal(c, cwd, e));
    }

    private void startInternal(String[] cmd, String cwd, String[] env) {
        pendingCmd = cmd;
        pendingCwd = cwd;
        pendingEnv = env;
        attached = false;
        ptyFailed = false;
        pipeModeActive = false;
        outputCount = 0;
        detached();
        try {
            session = new TerminalSession(cmd[0], cwd, cmd, env, 2000, this);
            setStatus("pty 模式：session 已创建，等待布局…\n", false);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "pty session failed", t);
            ptyFailed = true;
            startPipeMode("pty 创建失败: " + t);
            return;
        }
        attachWhenLaidOut();
    }

    private void detached() {
        if (session != null) {
            try {
                session.finishIfRunning();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (pipeProcess != null) {
            try {
                pipeProcess.destroy();
            } catch (Exception ignored) {
            }
            pipeProcess = null;
        }
    }

    private void attachWhenLaidOut() {
        if (view.getWidth() > 0 && view.getHeight() > 0) {
            attachNow();
            return;
        }
        view.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
                        if (view.getViewTreeObserver().isAlive()) {
                            view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                        attachNow();
                    }
                });
    }

    private void attachNow() {
        if (attached || session == null) return;
        attached = true;
        try {
            view.attachSession(session);
            view.updateSize();
            view.requestFocus();
            view.invalidate();
            TerminalEmulator em = session.getEmulator();
            if (em == null) {
                retryAttach();
                return;
            }
            setStatus(null, false);
            flushPending();
            // pty 可能“静默失败”：fd 打开成功但子进程 open(pts) 失败后退出，
            // 屏幕上只会是空白。4 秒内一条输出都没有就降级到管道模式。
            view.postDelayed(() -> {
                if (!pipeModeActive && outputCount <= 0) {
                    startPipeMode("pty 无任何输出，疑似 /dev/pts 被拒");
                }
            }, 6000);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "pty attach failed", t);
            // /dev/ptmx 在 targetSdk>=29 上可能被拒绝，直接降级为管道模式
            ptyFailed = true;
            startPipeMode("pty 不可用: " + t);
        }
    }

    private void retryAttach() {
        view.postDelayed(() -> {
            if (session == null) return;
            try {
                view.updateSize();
                view.invalidate();
                if (session.getEmulator() != null) {
                    setStatus(null, false);
                    flushPending();
                } else {
                    ptyFailed = true;
                    startPipeMode("emulator 未创建（尺寸异常）");
                }
            } catch (Throwable t) {
                ptyFailed = true;
                startPipeMode("重试失败: " + t);
            }
        }, 800);
    }

    /**
     * 降级模式：没有 pty 时用普通 Process，把 stdout 字节喂给 Termux 的 TerminalEmulator。
     * ANSI 颜色、256 色、宽字符、scrollback 仍然全部由 Termux 渲染器负责。
     */
    private void startPipeMode(String reason) {
        android.util.Log.w(TAG, "fallback to pipe mode: " + reason);
        pipeModeActive = true;
        attached = false;
        // 收掉旧的 pty session（若 pty 半死不活会占着 fd 和子进程）
        if (session != null) {
            try {
                session.finishIfRunning();
            } catch (Exception ignored) {
            }
            session = null;
        }
        try {
            session = new TerminalSession(pendingCmd[0], pendingCwd, pendingCmd, pendingEnv, 2000, this);
            session.setExternalMode(new ByteArrayOutputStream());
        } catch (Throwable t) {
            setStatus("终端不可用：" + reason + "\n" + t + "\n", true);
            return;
        }
        try {
            view.attachSession(session);
            view.updateSize();
            view.requestFocus();
            view.invalidate();
        } catch (Throwable t) {
            setStatus("终端不可用：" + reason + "\n" + t + "\n", true);
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(pendingCmd);
            pb.environment().clear();
            for (String e : pendingEnv) {
                int eq = e.indexOf('=');
                if (eq > 0) pb.environment().put(e.substring(0, eq), e.substring(eq + 1));
            }
            pb.directory(new File(pendingCwd));
            pb.redirectErrorStream(true);
            pipeProcess = pb.start();
            session.setExternalOutput(pipeProcess.getOutputStream());
            setStatus("管道模式（无 pty）：proot 已启动\n" + reason + "\n", false);
            view.postDelayed(() -> setStatus(null, false), 3000);

            final Process proc = pipeProcess;
            new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        final byte[] chunk = new byte[len];
                        System.arraycopy(buf, 0, chunk, 0, len);
                        activity.runOnUiThread(() -> {
                            if (session != null) session.feedExternalBytes(chunk, chunk.length);
                            view.onScreenUpdated();
                        });
                    }
                } catch (Exception ignored) {
                }
                activity.runOnUiThread(() -> {
                    if (onSessionFinished != null) onSessionFinished.run();
                });
            }, "TermuxPipeReader").start();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "pipe start failed", t);
            setStatus("proot 启动失败：" + t + "\n", true);
            append("proot 启动失败: " + t + "\n");
        }
    }

    private void setStatus(String text, boolean error) {
        Runnable r = () -> {
            if (statusView == null) return;
            statusView.setTextColor(error ? 0xFFFF8A80 : 0xFF9BE89B);
            statusView.setText(text == null ? "" : text);
            statusView.setVisibility((text == null || text.isEmpty()) ? View.GONE : View.VISIBLE);
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
        else activity.runOnUiThread(r);
    }

    /** 把 app 侧的诊断日志写进终端（session 未就绪时先缓存） */
    public void append(String text) {
        if (text == null || text.isEmpty()) return;
        if (session == null || session.getEmulator() == null) {
            pendingOutput.append(text);
            return;
        }
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Runnable r = () -> {
            TerminalEmulator em = (session == null) ? null : session.getEmulator();
            if (em == null) return;
            em.append(bytes, bytes.length);
            view.onScreenUpdated();
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
        else activity.runOnUiThread(r);
    }

    private void flushPending() {
        if (pendingOutput.length() == 0) return;
        String text = pendingOutput.toString();
        pendingOutput.setLength(0);
        append(text);
    }

    /** 向 pty 写入（等同于在终端里敲键盘） */
    public void write(String data) {
        if (session != null) session.write(data);
    }

    /** 主动改变字号（同时 resize pty） */
    public void changeTextSize(boolean increase) {
        int next = textSize + (increase ? 1 : -1);
        next = Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, next));
        if (next == textSize) return;
        textSize = next;
        view.setTextSize(textSize);
    }

    public int getTextSize() {
        return textSize;
    }

    public void shutdown() {
        if (pipeProcess != null) {
            try { pipeProcess.destroy(); } catch (Exception ignored) { }
            pipeProcess = null;
        }
        if (session != null) {
            try {
                session.finishIfRunning();
            } catch (Exception ignored) {
            }
            session = null;
        }
    }

    // ============================================================
    // TerminalViewClient
    // ============================================================

    @Override
    public float onScale(float scale) {
        // 捏合：改变字号交由 TerminalView.setTextSize（内部会 updateSize → resize pty）
        if (scale < 0.9f || scale > 1.1f) {
            changeTextSize(scale > 1.0f);
            return 1.0f;
        }
        return 1.0f;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        showSoftKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            changeTextSize(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            changeTextSize(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            // 回车交给 pty：TerminalView 自己会发 \r，这里不拦截
            return false;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        flushPending();
    }

    // ============================================================
    // TerminalSessionClient
    // ============================================================

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        outputCount++;
        activity.runOnUiThread(() -> view.onScreenUpdated());
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        activity.runOnUiThread(() -> {
            append("\n[进程已退出]\n");
            if (onSessionFinished != null) onSessionFinished.run();
        });
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession s, String text) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession s) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(activity);
            if (text != null) write(text.toString());
        }
    }

    @Override
    public void onBell(TerminalSession s) {
    }

    @Override
    public void onColorsChanged(TerminalSession s) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        // 0 = 竖线光标（Termux 默认），交给 emulator 自行决定闪烁
        return 0;
    }

    @Override
    public void setTerminalShellPid(TerminalSession s, int pid) {
        android.util.Log.i(TAG, "shell pid=" + pid);
    }

    // ---- 日志回调 ----

    @Override
    public void logError(String tag, String message) {
        android.util.Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        android.util.Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        android.util.Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        android.util.Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        android.util.Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        android.util.Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        android.util.Log.e(tag, "stack", e);
    }

    private void showSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }
}
