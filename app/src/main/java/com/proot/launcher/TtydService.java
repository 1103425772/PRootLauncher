package com.proot.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 前台服务：持有 ttyd 进程，常驻通知。
 * 这样切到浏览器后系统不会把 App 进程判为后台冻结/杀掉。
 */
public class TtydService extends Service {
    private static final String TAG = "TtydService";
    private static final String CHANNEL_ID = "ttyd_service";
    private static final int NOTI_ID = 7681;
    public static final String EXTRA_ROOTFS_PATH = "rootfs_path";
    public static final String EXTRA_ROOTFS_NAME = "rootfs_name";
    public static final String EXTRA_TTYD_TOKEN = "ttyd_token";
    public static final int TTYD_PORT = Config.TTYD_PORT;

    /** 生成 32 位随机 hex token（ttyd tokenAuth 认证，防同设备其他 App 直连） */
    public static String generateToken() {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        byte[] bytes = new byte[16];
        rnd.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Process ttydProcess;
    private PowerManager.WakeLock wakeLock;
    private String ttydToken = "";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 防御：intent 为 null（系统重启服务时可能发生）直接停止，
        // 不要对 null intent 调 getStringExtra 导致 NPE。
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String rootfsPath = intent.getStringExtra(EXTRA_ROOTFS_PATH);
        String rootfsName = intent.getStringExtra(EXTRA_ROOTFS_NAME);
        String token = intent.getStringExtra(EXTRA_TTYD_TOKEN);
        ttydToken = (token != null && !token.isEmpty()) ? token : generateToken();
        if (rootfsPath == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 1. 前台通知（必须在 startForeground 后才能跑后台任务）
        Notification n = buildNotification(rootfsName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTI_ID, n);
        }

        // 2. WakeLock（屏幕灭也保 CPU）
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prootlauncher:ttyd");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        // 3. 启动 ttyd
        try {
            startTtyd(new File(rootfsPath));
        } catch (Exception e) {
            Log.e(TAG, "start ttyd failed", e);
            stopSelf();
        }
        // START_NOT_STICKY：被系统杀掉后不自动重启。
        // START_STICKY 会让系统用 null intent 重启服务，走上面的
        // rootfsPath == null 分支立刻 stopSelf，白浪费一次启动。
        return START_NOT_STICKY;
    }

    private void startTtyd(File rootfsDir) throws Exception {
        ProotInstaller installer = new ProotInstaller(this);
        installer.prepareRuntime();

        // 先清残留
        runProotQuiet(installer, rootfsDir, "pkill -9 ttyd 2>/dev/null; sleep 1; true");

        // 默认交互 shell（v19 方案）：rootfs 的 /bin/sh，其次 bash/zsh
        String shell = installer.defaultShellPath(rootfsDir);

        String ttydCmd = "ttyd -p " + TTYD_PORT + " -i 127.0.0.1 -W -t 'tokenAuth="
            + ttydToken + "' " + shell;
        String[] cmd = installer.buildProotExecCommand(rootfsDir, ttydCmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        for (String e : installer.buildEnv()) {
            int eq = e.indexOf('=');
            if (eq > 0) pb.environment().put(e.substring(0, eq), e.substring(eq + 1));
        }
        pb.redirectErrorStream(true);
        ttydProcess = pb.start();

        // 读输出（try-with-resources：ttyd 退出后 BufferedReader 随线程结束被关闭，
        // 不会反复开关 Web 终端时逐个泄漏 Reader）
        final Process p = ttydProcess;
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.i(TAG, line);
                }
            } catch (Exception e) { /* ignore */ }
        }).start();
    }

    private int runProotQuiet(ProotInstaller installer, File rootfsDir, String cmd) throws Exception {
        String[] full = installer.buildProotExecCommand(rootfsDir, cmd);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.environment().clear();
        for (String e : installer.buildEnv()) {
            int eq = e.indexOf('=');
            if (eq > 0) pb.environment().put(e.substring(0, eq), e.substring(eq + 1));
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 丢弃输出（Reader 在 drain 线程结束时 finally 关闭）
        final InputStreamReader r = new InputStreamReader(p.getInputStream());
        new Thread(() -> {
            try {
                while (r.read() != -1) {}
            } catch (Exception e) {
            } finally {
                try { r.close(); } catch (Exception e) {}
            }
        }).start();
        p.waitFor();
        return p.exitValue();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.noti_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.noti_channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String rootfsName) {
        // 通知里的 URL 也带 token，点通知可直接打开终端
        Intent openIntent = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("http://127.0.0.1:" + TTYD_PORT + "/?token=" + ttydToken));
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        String title = getString(R.string.noti_title);
        String url = "http://127.0.0.1:" + TTYD_PORT + "/?token=" + ttydToken;
        String text = (rootfsName != null && !rootfsName.isEmpty())
                ? getString(R.string.noti_text, rootfsName, url) : url;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ttydProcess != null) {
            try { ttydProcess.destroy(); } catch (Exception e) { /* ignore */ }
            try { ttydProcess.waitFor(); } catch (Exception e) { /* ignore */ }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception e) { /* ignore */ }
        }
    }
}
