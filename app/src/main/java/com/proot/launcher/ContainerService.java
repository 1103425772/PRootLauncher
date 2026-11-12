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

/**
 * v24：容器会话前台服务。
 *
 * 作用：内置终端（TerminalActivity）启动 proot 会话时拉起本服务，
 * 以「前台服务 + WakeLock + 常驻通知」方式让 App 进程在退到后台后
 * 不被系统冻结/杀掉——否则 Android 12+ 会把后台进程 freezer 掉，
 * proot 子进程全部挂起，表现为「app 放到后台就没网/容器不动」。
 *
 * 停止：会话结束（TerminalActivity.onDestroy）时 stopService。
 */
public class ContainerService extends Service {
    private static final String TAG = "ContainerService";
    private static final String CHANNEL_ID = "container_service";
    private static final int NOTI_ID = 4713;

    public static final String EXTRA_ROOTFS_NAME = "rootfs_name";
    public static final String EXTRA_ROOTFS_PATH = "rootfs_path";

    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String rootfsName = intent.getStringExtra(EXTRA_ROOTFS_NAME);
        String rootfsPath = intent.getStringExtra(EXTRA_ROOTFS_PATH);

        // 通知点按回到主界面
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        String text = (rootfsName != null && !rootfsName.isEmpty())
            ? "容器运行中：" + rootfsName
            : "容器运行中";
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("PRoot 容器运行中")
                .setContentText(text)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTI_ID, n);
        }

        // WakeLock：灭屏也保 CPU（容器里的 ttyd/下载任务不会停）
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prootlauncher:container");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        Log.i(TAG, "container keep-alive started: " + rootfsName + " @ " + rootfsPath);
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "容器后台保活", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("容器会话在后台时保持网络与进程存活");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception e) { /* ignore */ }
        }
        Log.i(TAG, "container keep-alive stopped");
    }
}
