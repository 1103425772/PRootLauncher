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
import android.widget.FrameLayout;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

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
        if (session != null) {
            try {
                session.finishIfRunning();
            } catch (Exception ignored) {
            }
        }
        session = new TerminalSession(cmd[0], cwd, cmd, env, 2000, this);
        // 必须等 TerminalView 完成一次布局（onSizeChanged 才有宽高），
        // 否则 updateSize() 会算出 0 列 0 行，pty 尺寸非法
        view.post(() -> {
            if (session == null) return;
            view.attachSession(session);
            view.requestFocus();
            flushPending();
        });
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
