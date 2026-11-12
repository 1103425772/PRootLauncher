package com.proot.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * v27 新增：音频桥接专用前台服务（foregroundServiceType=mediaPlayback）。
 *
 * 为什么必须有它：
 *  - Android 14(API 34) 起：后台起播音频必须挂在 mediaPlayback 类型前台服务上，
 *    类型与 startForeground() 传入值必须和清单声明一致，否则 SecurityException；
 *  - Android 15(API 35) 起：非顶部应用且没有音频类前台服务时，
 *    requestAudioFocus() 直接返回 AUDIOFOCUS_REQUEST_FAILED；
 *  - Android 16(API 36) 起：不符合上面两条的音频交互会被静默丢弃
 *    （不抛异常、不报错，就是没声音）。
 * v26 只有 ContainerService 的 dataSync 类型，既不被算作音频播放身份，
 * 还吃 Android 15 起 dataSync 的 6 小时/24 小时配额，到点直接被强停。
 */
public class AudioService extends Service {
    private static final String TAG = "AudioService";
    private static final String CHANNEL_ID = "audio_bridge";
    private static final int NOTI_ID = 4715;

    private static volatile boolean alive = false;

    public static boolean isAlive() { return alive; }

    /** 起服务（异步），并在 AudioBridge 里靠 FIFO watchdog 兜住时序。 */
    public static void ensureRunning(Context ctx) {
        if (alive) return;
        try {
            Intent i = new Intent(ctx.getApplicationContext(), AudioService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getApplicationContext().startForegroundService(i);
            } else {
                ctx.getApplicationContext().startService(i);
            }
        } catch (Exception e) {
            // 后台启动前台服务被拒（Android 12+）：不阻断，降级交给 AAudio sink
            Log.w(TAG, "ensureRunning failed: " + e.getMessage());
            AudioBridge.setLastErrorIfIdle("音频前台服务未能启动: " + e.getMessage());
        }
    }

    public static void shutdown(Context ctx) {
        try {
            ctx.getApplicationContext().stopService(
                new Intent(ctx.getApplicationContext(), AudioService.class));
        } catch (Exception ignore) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promote();
        return START_NOT_STICKY;
    }

    /** 先试 mediaPlayback 类型；清单/权限不匹配时退回无类型前台，至少进程不被冻结。 */
    private void promote() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                alive = true;
                Log.i(TAG, "audio FGS up: mediaPlayback");
                return;
            } catch (Exception e) {
                Log.w(TAG, "mediaPlayback type rejected: " + e.getMessage());
            }
        }
        try {
            startForeground(NOTI_ID, n);
            alive = true;
            Log.i(TAG, "audio FGS up: untyped");
        } catch (Exception e) {
            alive = false;
            Log.e(TAG, "startForeground failed: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("容器音频桥接运行中")
                .setContentText("Linux 容器里的声音正在通过本机播放")
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "容器音频", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持容器音频在后台可播放（Android 14+ 要求）");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        alive = false;
        AudioBridge.stop();
        Log.i(TAG, "audio FGS down");
    }
}
