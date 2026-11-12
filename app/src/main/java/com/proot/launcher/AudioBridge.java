package com.proot.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * v27.1：Java AudioTrack ↔ FIFO 桥（Android 14/15/16 后台音频合规 + 抗断链）。
 *
 * 链路：容器 app → TCP 4713 → pulseaudio → pipe-sink → FIFO → AudioTrack → 喇叭
 *
 * v27 在 Android 16 上把媒体身份补齐了（mediaPlayback 前台服务 + 焦点先行 + MediaSession），
 * 但真机诊断暴露出两个新的致命问题，v27.1 修掉：
 *
 *  1) 读线程一遇到 FIFO EOF 就把整个桥判定为结束并静默 stop()。
 *     pulse 每次重启（点「音频诊断」会 destroy 再拉起）或 pipe-sink 挂起，写端都会关闭，
 *     于是新 pulse 的数据再也没有人接 → 表现为「策略是 pipe-sink、端口已监听、
 *     但 AudioTrack 桥接 ❌ 未运行」，而且 lastError 是空的，什么线索都没有。
 *     → 现在 EOF 只重开 FIFO（累计次数进诊断），不再杀死桥接。
 *  2) 音频焦点 AUDIOFOCUS_LOSS 直接永久停桥。
 *     接个电话、别人放首歌，容器声音就再也回不来了。
 *     → 现在 LOSS 只暂停轨道，focus-guard 每 5 秒重新申请，拿到焦点自动续播。
 *
 * 保留的 v27 设计：FGS → MediaSession → 先要焦点 → 建轨 → play()；部分写入补齐；
 * 播放头可播性探测（静默丢弃时如实报错）。
 *
 * 关键：pulse 编译期模块路径写死成 /data/data/com.termux/...，本 App 读不到；
 * default.pa 里 load-module 必须写 .so 的绝对路径（pulse 见到带 / 的参数直接 dlopen）。
 */
public class AudioBridge {
    private static final String TAG = "AudioBridge";
    private static final int RATE = 48000;

    private static volatile boolean running = false;
    private static volatile String lastError = "";
    private static volatile boolean fifoOpened = false;
    private static volatile boolean focusGranted = false;
    private static volatile long writtenBytes = 0;
    private static volatile int fifoReopens = 0;

    private static AudioTrack track;
    private static Thread readerThread;
    private static Thread watchdogThread;
    private static Thread focusGuardThread;
    private static File fifo;
    private static AudioManager audioManager;
    private static AudioFocusRequest focusRequest;   // API 26+
    private static MediaSession mediaSession;        // API 21+
    private static volatile Context appCtx;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static boolean isRunning() { return running; }
    public static String getLastError() { return lastError; }
    public static int getFifoReopens() { return fifoReopens; }

    /** 桥接尚未记录错误时补一条说明（供 AudioService 等外部兜底使用）。 */
    public static synchronized void setLastErrorIfIdle(String msg) {
        if (lastError == null || lastError.isEmpty()) lastError = msg;
        Log.w(TAG, msg);
    }

    /** 停止桥接（策略降级 / 服务销毁 / 用户关闭音频时调用）。 */
    public static synchronized void stop() {
        if (!running && track == null && mediaSession == null) return;
        running = false;
        Thread rt = readerThread; readerThread = null;
        Thread wt = watchdogThread; watchdogThread = null;
        Thread gt = focusGuardThread; focusGuardThread = null;
        if (rt != null) rt.interrupt();
        if (wt != null) wt.interrupt();
        if (gt != null) gt.interrupt();
        releaseTrack();
        abandonAudioFocus();
        releaseMediaSession();
        fifoOpened = false;
        Log.i(TAG, "AudioBridge stopped");
    }

    /**
     * 启动桥接。
     * @return true=桥已跑起来；false=被系统拒（焦点/权限/建轨失败），调用方应降级。
     */
    public static synchronized boolean start(Context ctx, File root) {
        if (running) return true;
        lastError = "";
        fifoOpened = false;
        focusGranted = false;
        writtenBytes = 0;
        fifoReopens = 0;
        appCtx = ctx.getApplicationContext();

        File files = ctx.getFilesDir();
        File runDir = new File(files, "audio/run");
        runDir.mkdirs();
        fifo = new File(runDir, "audio.fifo");
        if (fifo.exists()) fifo.delete();

        File modules = new File(root, "lib/pulseaudio/modules");
        String mp = modules.getAbsolutePath();

        // 0. 前台服务先就位：Android 14+ 后台起播必须有 mediaPlayback 类型 FGS
        AudioService.ensureRunning(ctx);
        // startForegroundService 是异步的，等它真的进入前台再要焦点（最多 1.5 秒）
        for (int i = 0; i < 30 && !AudioService.isAlive(); i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (!AudioService.isAlive()) {
            lastError = "mediaPlayback 前台服务未进入前台（Android 14+ 后台出声会被系统拦），"
                + "桥接改由原生 AAudio 输出兜底";
        }

        // 1. 创建 FIFO
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "mkfifo " + fifo.getAbsolutePath()});
            if (p.waitFor() != 0) {
                lastError = "mkfifo exit=" + p.exitValue();
                return false;
            }
        } catch (Exception e) {
            lastError = "mkfifo: " + e.getMessage();
            return false;
        }

        // 2. 写 default.pa：模块全用绝对路径
        File paConf = new File(files, "audio/default.pa");
        try {
            String cfg =
                "load-module " + mp + "/module-native-protocol-tcp.so"
                    + " auth-anonymous=1 port=" + AudioSupport.PORT + " listen=127.0.0.1\n"
              + "load-module " + mp + "/module-pipe-sink.so"
                    + " sink_name=androidsink format=s16le rate=48000 channels=2"
                    + " file=" + fifo.getAbsolutePath() + "\n"
              + "set-default-sink androidsink\n";
            FileOutputStream out = new FileOutputStream(paConf);
            out.write(cfg.getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            lastError = "write default.pa: " + e.getMessage();
            return false;
        }

        // 3. MediaSession（先把这路 PCM 声明成真实媒体播放）
        ensureMediaSession(ctx);

        // 4. 先请求焦点，再建轨起播（Android 15+ 硬要求）
        if (!requestAudioFocus(ctx)) {
            releaseMediaSession();
            return false;
        }
        if (!buildAudioTrack(ctx)) {
            abandonAudioFocus();
            releaseMediaSession();
            return false;
        }

        // 5. 读线程：EOF 只重开 FIFO，绝不杀死桥接
        final File f = fifo;
        running = true;
        readerThread = new Thread(() -> {
            byte[] buf = new byte[Math.max(4096, RATE / 10 * 4)]; // ~100ms @ s16 stereo
            while (running) {
                InputStream in = null;
                boolean fatal = false;
                try {
                    in = new FileInputStream(f);        // 阻塞直到 pulse 打开写端
                    fifoOpened = true;
                    if (fifoReopens > 0) {
                        Log.i(TAG, "FIFO 第 " + fifoReopens + " 次重开成功");
                        lastError = "";
                    }
                    while (running) {
                        int n = in.read(buf);
                        if (n < 0) break;               // EOF：写端关闭，出去重开
                        if (n == 0) continue;
                        AudioTrack t = track;
                        if (t == null) return;           // 已被 stop()，直接退出
                        int off = 0;
                        while (off < n && running) {
                            int w = t.write(buf, off, n - off);
                            if (w < 0) {
                                lastError = "AudioTrack.write 错误码: " + w + "（轨道已被系统回收）";
                                fatal = true;
                                break;
                            }
                            if (w == 0) {                // 轨道暂停/缓冲满：让出重试，不丢数据
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    fatal = true; break;
                                }
                                continue;
                            }
                            off += w;
                            writtenBytes += w;
                        }
                        if (fatal || !running) break;
                    }
                } catch (Exception e) {
                    if (running) lastError = "FIFO 打开/读取异常: " + e.getMessage();
                    else break;
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignore) {}
                }
                if (fatal) { running = false; stop(); return; }
                if (!running) return;
                // 到这里就是 EOF：pulse 重启或 pipe-sink 挂起，等 1 秒重开，桥接保持存活
                fifoOpened = false;
                fifoReopens++;
                lastError = "FIFO 写端关闭（pulse 重启或 pipe-sink 挂起），第 "
                    + fifoReopens + " 次自动重开，等待 pulse 连上来";
                Log.w(TAG, lastError);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "pcm-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 6. watchdog：pulse 没连上 FIFO 写端时给出诊断（不静默卡住）
        watchdogThread = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (running && !fifoOpened) {
                try {
                    if (System.currentTimeMillis() - t0 > 20000) {
                        lastError = "FIFO 无写端（pulse pipe-sink 未连接），端口 4713: "
                            + (portListening() ? "已监听" : "未监听")
                            + "。请点「音频诊断」查看 pulse 日志。";
                        return;
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            probeAudibility();
        }, "fifo-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();

        // 7. focus-guard：焦点被抢只暂停，每 5 秒试着夺回来，不永久停桥
        focusGuardThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) return;
                if (focusGranted) continue;
                Context c = appCtx;
                if (c == null) return;
                if (requestAudioFocus(c)) {
                    AudioTrack t = track;
                    if (t != null) {
                        try { t.setVolume(1.0f); t.play(); } catch (Exception ignore) {}
                    }
                    setPlaybackState(true);
                    lastError = "";
                    Log.i(TAG, "音频焦点已夺回，恢复播放");
                }
            }
        }, "focus-guard");
        focusGuardThread.setDaemon(true);
        focusGuardThread.start();

        Log.i(TAG, "AudioBridge started, fifo=" + fifo.getAbsolutePath());
        return true;
    }

    /**
     * 可播性探测：Android 16 的后台音频拦截是静默的——write 成功但听不到声音。
     * 用 playback head 是否推进来判定，窗口内没前进就如实报错。
     */
    private static void probeAudibility() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            return;
        }
        if (!running) return;
        AudioTrack t = track;
        if (t == null) return;
        long head0;
        try {
            head0 = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (IllegalStateException e) {
            return;
        }
        long w0 = writtenBytes;
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            return;
        }
        if (!running) return;
        t = track;
        if (t == null) return;
        long head1;
        try {
            head1 = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (IllegalStateException e) {
            return;
        }
        if (writtenBytes > w0 && head1 == head0) {
            lastError = "写入 " + (writtenBytes - w0) + " 字节但播放头没有前进："
                + "系统正在静默丢弃这路音频（Android 16 后台音频拦截）。"
                + "请保持「音频前台服务」存活，或让 App 停留在前台。";
            Log.w(TAG, lastError);
        } else if (writtenBytes == w0) {
            Log.i(TAG, "探测窗口内 FIFO 无数据（容器还没出声）");
        } else {
            Log.i(TAG, "audibility ok: head advanced " + (head1 - head0) + " frames");
        }
    }

    /** 建轨并起播；失败时 lastError 已记录。 */
    private static boolean buildAudioTrack(Context ctx) {
        int minBuf = AudioTrack.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 8192;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build();
                track = new AudioTrack.Builder()
                        .setAudioAttributes(mediaAttributes())
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(minBuf * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC, RATE,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2, AudioTrack.MODE_STREAM);
            }
            AudioTrack t = track;
            t.setVolume(1.0f);
            t.play();
        } catch (Exception e) {
            lastError = "AudioTrack 建轨失败: " + e.getMessage();
            releaseTrack();
            return false;
        }
        setPlaybackState(true);
        return true;
    }

    private static AudioAttributes mediaAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    /** @return true=可以继续播放（GRANTED 或 DELAYED）；false=被拒。 */
    private static boolean requestAudioFocus(Context ctx) {
        if (audioManager == null) {
            audioManager = (AudioManager) ctx.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) {
            lastError = "AudioManager 不可用，无法请求音频焦点";
            return false;
        }
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(mediaAttributes())
                        .setAcceptsDelayedFocusGain(true)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(focusListener, MAIN)
                        .build();
                result = audioManager.requestAudioFocus(focusRequest);
            } else {
                result = audioManager.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusGranted = true;
                return true;
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                focusGranted = false;   // 焦点排队中，回调到达后才真正出声
                lastError = "AudioFocus: 延迟授予(DELAYED)，等其他应用让出焦点";
                Log.w(TAG, "requestAudioFocus delayed");
                return true;
            }
            lastError = "AudioFocus 被拒绝(code=" + result + ")："
                + "Android 15+ 要求顶部应用或 mediaPlayback 前台服务才能拿到焦点，"
                + "本 App 已改走 AAudio 原生输出。";
            Log.w(TAG, "requestAudioFocus refused: " + result);
            return false;
        } catch (Exception e) {
            lastError = "requestAudioFocus 异常: " + e.getMessage();
            return false;
        }
    }

    private static final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) {
                    AudioTrack t = track;
                    switch (change) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            // 不永久停桥：暂停这路 PCM，交给 focus-guard 周期性夺回
                            focusGranted = false;
                            lastError = "AudioFocus 永久丢失(LOSS)：其他应用抢占，已暂停并准备自动恢复";
                            if (t != null) { try { t.pause(); } catch (Exception ignore) {} }
                            setPlaybackState(false);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            focusGranted = false;
                            if (t != null) { try { t.pause(); } catch (Exception ignore) {} }
                            setPlaybackState(false);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            if (t != null) { try { t.setVolume(0.2f); } catch (Exception ignore) {} }
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                        default:
                            focusGranted = true;
                            if (t != null) {
                                try { t.setVolume(1.0f); t.play(); } catch (Exception ignore) {}
                            }
                            setPlaybackState(true);
                            break;
                    }
                }
            };

    private static void abandonAudioFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            } else {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignore) {}
        focusGranted = false;
    }

    private static void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); } catch (Exception ignore) {}
            try { t.flush(); } catch (Exception ignore) {}
            try { t.release(); } catch (Exception ignore) {}
        }
    }

    private static void ensureMediaSession(Context ctx) {
        if (Build.VERSION.SDK_INT < 21) return;
        if (mediaSession == null) {
            try {
                mediaSession = new MediaSession(ctx.getApplicationContext(), "PRootAudioBridge");
                mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                mediaSession.setCallback(new MediaSession.Callback() { });
            } catch (Exception e) {
                Log.w(TAG, "MediaSession create failed: " + e.getMessage());
                mediaSession = null;
            }
        }
        if (mediaSession != null) {
            try { mediaSession.setActive(true); } catch (Exception ignore) {}
        }
        setPlaybackState(true);
    }

    private static void setPlaybackState(boolean playing) {
        MediaSession ms = mediaSession;
        if (ms == null) return;
        try {
            ms.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_STOP)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build());
        } catch (Exception ignore) {}
    }

    private static void releaseMediaSession() {
        MediaSession ms = mediaSession;
        mediaSession = null;
        if (ms != null) {
            try { ms.setActive(false); } catch (Exception ignore) {}
            try { ms.release(); } catch (Exception ignore) {}
        }
    }

    /** 探测 127.0.0.1:4713 是否在监听（watchdog 诊断用）。 */
    private static boolean portListening() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", AudioSupport.PORT), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
