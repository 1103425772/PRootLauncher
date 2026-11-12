package com.proot.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * v25：跨架构模拟（QEMU）引导器 —— 给跨架构模拟提供「真实模拟」能力。
 *
 * 场景：ARM 手机跑 x86_64 / aarch64 / m68k / ppc 等「异架构 rootfs」时，
 * rootfs 里的每个二进制都是目标架构，需要 qemu-user 模拟器先跑起来。
 * 两种来源（二进制名与 tur-packages 子包一致）：
 *
 *  1. rootfs 内已装发行版 qemu-user（Debian/Ubuntu: apt install qemu-user）
 *     → /usr/bin/qemu-<arch>，直接可用。
 *  2. 宿主侧自动引导（真实模拟开箱即用）：从 multiarch/qemu-user-static
 *     （GitHub Releases v7.2.0-1，静态链接，Android 内核可直接执行）下载
 *     qemu-<arch>-static，缓存到 files/qemu/bin/，再复制进 rootfs /usr/bin/。
 *
 * tur-packages（Termux User Repository）的版本化包名作为参考元数据
 * （见 SimConfig.qemuPkgName / qemuPkgVersion / qemuPkgArch）：
 *   qemu-user-aarch64-9  9.2.4-1 arm   （QEMU 9.x，模拟 64 位 aarch64 guest）
 *   qemu-user-arm-10     10.2.1-1 arm   （QEMU 10.x，模拟 32 位 arm guest）
 *   qemu-user-i386-10    10.2.1-1 arm
 *   qemu-user-m68k-10    10.2.1-1 arm
 *   qemu-user-ppc-10     10.2.1-1 arm
 *   qemu-user-ppc64-9    9.2.4-1 arm
 *   qemu-user-riscv64-9  9.2.4-1 arm
 *   qemu-user-x86-64-9   9.2.4-1 arm
 */
public final class QemuSupport {
    private static final String TAG = "QemuSupport";
    private static final String QUS_RELEASE = "v7.2.0-1";
    private static final String QUS_BASE =
        "https://v4.gh-proxy.org/https://github.com/multiarch/qemu-user-static/releases/download/"
        + QUS_RELEASE + "/";

    private QemuSupport() {}

    /** rootfs 里是否已有可用的 qemu 模拟器（普通名或 -static 名） */
    public static boolean isQemuInstalled(File rootfsDir, String arch) {
        String bin = SimConfig.qemuBinName(arch);
        if (bin == null) return false;
        return new File(rootfsDir, "usr/bin/" + bin).isFile()
            || new File(rootfsDir, "usr/bin/" + bin + "-static").isFile();
    }

    /** 返回应使用的 qemu 二进制名（优先 rootfs 内已装的普通名，其次 -static） */
    public static String resolveQemuBin(File rootfsDir, String arch) {
        String bin = SimConfig.qemuBinName(arch);
        if (bin == null) return null;
        if (new File(rootfsDir, "usr/bin/" + bin).isFile()) return bin;
        if (new File(rootfsDir, "usr/bin/" + bin + "-static").isFile()) return bin + "-static";
        return bin;
    }

    /**
     * 确保 qemu 模拟器可用：
     *  rootfs 内已有 → 直接返回；
     *  否则下载宿主侧 qemu-<arch>-static（multiarch 静态构建）到 files/qemu/bin/，
     *  复制进 rootfs /usr/bin/ 并赋予执行权限。
     * @return 状态描述（供终端输出 / Toast）
     */
    public static synchronized String ensureInstalled(Context ctx, File rootfsDir, String arch)
            throws Exception {
        if (isQemuInstalled(rootfsDir, arch)) {
            return "QEMU 模拟器已就绪: " + resolveQemuBin(rootfsDir, arch);
        }
        String bin = SimConfig.qemuBinName(arch);
        if (bin == null) return "不支持的架构: " + arch;

        // 宿主缓存目录 files/qemu/bin（与 pulseaudio 的 files 布局一致）
        File cacheBin = new File(ctx.getFilesDir(), "qemu/bin");
        cacheBin.mkdirs();
        File hostBin = new File(cacheBin, bin + "-static");

        if (!hostBin.isFile() || hostBin.length() == 0L) {
            String url = QUS_BASE + bin + "-static";
            Log.i(TAG, "download qemu-user-static: " + url);
            download(url, hostBin);
        }
        hostBin.setExecutable(true, false);

        // 复制进 rootfs usr/bin，proot 内以 guest 路径执行
        File guestBinDir = new File(rootfsDir, "usr/bin");
        guestBinDir.mkdirs();
        File guestBin = new File(guestBinDir, bin + "-static");
        if (!guestBin.isFile() || guestBin.length() != hostBin.length()) {
            copyFile(hostBin, guestBin);
        }
        guestBin.setExecutable(true, false);

        return "已自动安装 QEMU 模拟器: " + bin + "-static（multiarch " + QUS_RELEASE + "）\n"
            + "参考包（tur-packages）: " + SimConfig.qemuPkgName(arch) + " "
            + SimConfig.qemuPkgVersion(arch) + " " + SimConfig.qemuPkgArch();
    }

    private static void download(String urlStr, File dest) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "PRootLauncher/1.0");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + code + " @ " + urlStr);
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }
}
