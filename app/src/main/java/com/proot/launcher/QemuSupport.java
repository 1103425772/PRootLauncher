package com.proot.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;

/**
 * v28：跨架构模拟（QEMU）引导器 —— 使用发行版官方仓库的静态模拟器。
 *
 * 与 v25 的区别（重要）：
 *
 *  v25 用 multiarch/qemu-user-static 的 GitHub Releases 静态包，但那批二进制
 *  是「x86_64 宿主」构建的，在 arm64 安卓设备上根本执行不了（exec format
 *  error），而且启动时把它当作 proot 的 argv 命令名传入，容器内 PATH 为空
 *  （proot 报 `'qemu-x86_64-static' not found ... $PATH=(null)`）。
 *
 *  v28 改用 **Debian 12 (bookworm) 官方仓库**的 qemu-user-static：
 *    https://deb.debian.org/debian/pool/main/q/qemu/qemu-user-static_<ver>_<arch>.deb
 *  该包内是 static-pie 链接的 ELF（ARM aarch64 / x86_64 宿主），无任何动态
 *  依赖，安卓内核可直接执行；一个包内含全部目标架构（x86_64 / i386 / arm /
 *  aarch64 / m68k / ppc / ppc64 / riscv32 / riscv64 ...）。
 *
 *  下载后按 deb(ar) -> data.tar.xz -> tar 的顺序流式抽取单个
 *  `./usr/bin/qemu-<target>-static`，缓存到 files/qemu/bin/。
 *  启动时由 ProotInstaller 传给 proot 的 `-q <绝对路径>`，proot 在宿主侧
 *  用 QEMU 接管容器内所有 execve，不依赖容器 PATH。
 *
 *  32 位宿主（armhf）Debian 未提供 static 包，回退旧 multiarch 静态源。
 */
public final class QemuSupport {
    private static final String TAG = "QemuSupport";

    /** Debian 官方源（主源 + 两个国内镜像兜底） */
    private static final String[] DEB_MIRRORS = {
        "https://deb.debian.org/debian",
        "https://mirrors.ustc.edu.cn/debian",
        "https://mirrors.aliyun.com/debian",
    };

    /** 旧回退：multiarch 静态（仅 32 位宿主用） */
    private static final String QUS_RELEASE = "v7.2.0-1";
    private static final String QUS_BASE =
        "https://v4.gh-proxy.org/https://github.com/multiarch/qemu-user-static/releases/download/"
        + QUS_RELEASE + "/";

    private QemuSupport() {}

    /** 宿主缓存目录 files/qemu/bin（旧 Debian 静态包解出位置） */
    public static File hostBinDir(Context ctx) {
        return new File(ctx.getFilesDir(), "qemu/bin");
    }

    /** v30 内置模拟器目录（从 APK assets 部署，离线可用） */
    public static File builtinDir(Context ctx) {
        return new File(ctx.getFilesDir(), "qemu/builtin");
    }

    public static File builtinBinDir(Context ctx) {
        return new File(builtinDir(ctx), "bin");
    }

    public static File builtinLibDir(Context ctx) {
        return new File(builtinDir(ctx), "lib");
    }

    /**
     * 把 APK assets/qemu/&lt;abi&gt; 里的 qemu 二进制与依赖库部署到 files/qemu/builtin。
     * 只复制缺失/大小不符的文件，部署完写 .version 标记，之后启动零 IO。
     */
    public static synchronized void deployBuiltin(Context ctx) {
        String abi = SimConfig.hostIs64() ? "arm64" : "arm";
        File binDir = builtinBinDir(ctx);
        File libDir = builtinLibDir(ctx);
        File marker = new File(builtinDir(ctx), ".version-" + abi);
        if (marker.isFile()) return;
        binDir.mkdirs();
        libDir.mkdirs();
        try {
            AssetManager am = ctx.getAssets();
            copyAssetDir(am, "qemu/" + abi + "/bin", binDir, true);
            copyAssetDir(am, "qemu/" + abi + "/lib", libDir, false);
            // 标记写入（内容是 abi，仅作存在性判断）
            FileOutputStream fos = new FileOutputStream(marker);
            fos.write(abi.getBytes());
            fos.close();
            Log.i(TAG, "内置模拟器部署完成: " + builtinDir(ctx).getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "内置模拟器部署失败: " + e.getMessage());
        }
    }

    private static void copyAssetDir(AssetManager am, String assetPath, File destDir,
                                     boolean executable) throws IOException {
        destDir.mkdirs();
        String[] list = am.list(assetPath);
        if (list == null) return;
        for (String name : list) {
            String sub = assetPath + "/" + name;
            String[] subList = am.list(sub);
            if (subList != null && subList.length > 0) {
                copyAssetDir(am, sub, new File(destDir, name), executable);
                continue;
            }
            File out = new File(destDir, name);
            InputStream in = am.open(sub);
            try {
                FileOutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
            if (executable) {
                out.setExecutable(true, false);
                out.setReadable(true, true);
            }
        }
    }

    /** 宿主可执行的模拟器：优先内置 files/qemu/builtin/bin/qemu-&lt;arch&gt; */
    public static File hostQemu(Context ctx, String arch) {
        String bin = SimConfig.qemuBinName(arch);
        if (bin == null) return null;
        File builtin = new File(builtinBinDir(ctx), bin);
        if (builtin.isFile() && builtin.length() > 1024L) return builtin;
        // 兼容旧版：Debian static 包解出的 qemu-<arch>-static
        File legacy = new File(hostBinDir(ctx), bin + "-static");
        if (legacy.isFile() && legacy.length() > 1024L) return legacy;
        return builtin;
    }

    /** 宿主侧静态模拟器是否已就绪（存在 / 体积合理 / 可执行） */
    public static boolean isHostReady(Context ctx, String arch) {
        File f = hostQemu(ctx, arch);
        if (f == null || !f.isFile() || f.length() < 1024L) return false;
        if (!f.canExecute()) f.setExecutable(true, false);
        return f.canExecute();
    }

    /** rootfs 里是否已有可用的 qemu 模拟器（普通名或 -static 名），仅作信息展示 */
    public static boolean isQemuInstalled(File rootfsDir, String arch) {
        String bin = SimConfig.qemuBinName(arch);
        if (bin == null) return false;
        return new File(rootfsDir, "usr/bin/" + bin).isFile()
            || new File(rootfsDir, "usr/bin/" + bin + "-static").isFile();
    }

    /** rootfs 内已有的二进制名（优先普通名，其次 -static） */
    public static String resolveQemuBin(File rootfsDir, String arch) {
        String bin = SimConfig.qemuBinName(arch);
        if (bin == null) return null;
        if (new File(rootfsDir, "usr/bin/" + bin).isFile()) return bin;
        if (new File(rootfsDir, "usr/bin/" + bin + "-static").isFile()) return bin + "-static";
        return bin;
    }

    /**
     * 确保宿主侧静态模拟器可用。
     * 已缓存则直接返回；否则下载 Debian 官方 qemu-user-static deb 并解出目标架构二进制。
     *
     * @return 状态描述（供终端输出）
     */
    public static synchronized String ensureInstalled(Context ctx, File rootfsDir, String arch)
            throws Exception {
        String bin = SimConfig.qemuBinName(arch);
        if (bin == null) throw new Exception("不支持的架构: " + arch);

        // v30：模拟器全部内置（APK assets），部署一次即可 —— 不再联网下载 Debian
        deployBuiltin(ctx);

        File host = hostQemu(ctx, arch);
        if (host == null) throw new Exception("无法解析模拟器路径: " + arch);
        if (isHostReady(ctx, arch)) {
            return "内置模拟器已就绪: " + host.getAbsolutePath()
                + "（" + (host.length() / (1024 * 1024)) + " MB）";
        }

        // 32 位宿主：内置 arm 版只有 32 位 guest 模拟器，
        // 若用户选了 64 位目标架构则明确说明（其余情况照常启动）
        if (!SimConfig.hostIs64() && SimConfig.qemuTargetIs64(arch)) {
            throw new Exception("当前设备是 32 位 ABI（" + android.os.Build.SUPPORTED_ABIS[0]
                + "），无法模拟 64 位目标架构 " + arch + "。\n"
                + "32 位设备可用：i386 / arm / m68k / ppc / riscv32。");
        }

        throw new Exception("未找到内置模拟器: " + bin
            + "\n当前 ABI: " + android.os.Build.SUPPORTED_ABIS[0]
            + "\n内置目录: " + builtinBinDir(ctx).getAbsolutePath()
            + "\nAPK 可能损坏，请重新安装完整版。");
    }

    // ================================================================
    // Debian 官方 deb 下载
    // ================================================================

    private static void downloadDeb(String debArch, File dest) throws Exception {
        String file = "qemu-user-static_" + SimConfig.QEMU_DEB_VERSION + "_" + debArch + ".deb";
        String poolPath = "pool/main/q/qemu/" + file;
        Exception last = null;
        for (String mirror : DEB_MIRRORS) {
            String urlStr = mirror + "/" + poolPath;
            File tmp = new File(dest.getAbsolutePath() + ".part");
            try {
                Log.i(TAG, "download qemu-user-static deb: " + urlStr);
                download(urlStr, tmp);
                if (tmp.length() < 1024L * 1024L) {
                    throw new IOException("deb 体积异常: " + tmp.length());
                }
                if (dest.exists() && !dest.delete()) {
                    throw new IOException("无法覆盖旧 deb: " + dest);
                }
                if (!tmp.renameTo(dest)) throw new IOException("rename 失败: " + tmp);
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "mirror failed (" + mirror + "): " + e.getMessage());
                tmp.delete();
            }
        }
        throw new Exception("下载 qemu-user-static deb 失败: "
            + (last == null ? "unknown" : last.getMessage()));
    }

    /** 32 位宿主回退：multiarch 静态包 */
    private static void downloadFromMultiarch(Context ctx, File host, String bin) throws Exception {
        if (!host.isFile() || host.length() == 0L) {
            String url = QUS_BASE + bin + "-static";
            Log.i(TAG, "download multiarch qemu-user-static: " + url);
            download(url, host);
        }
        host.setExecutable(true, false);
    }

    private static void download(String urlStr, File dest) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(180000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "PRootLauncher/1.0");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " @ " + urlStr);
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

    // ================================================================
    // ELF 校验
    // ================================================================

    /** 读出 ELF 头，校验魔数 + e_machine + 64 位标志 */
    private static boolean isValidElfForHost(File f, int expectedMachine) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            if (raf.length() < 64) return false;
            byte[] h = new byte[64];
            raf.readFully(h);
            if (h[0] != 0x7F || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            if (h[4] != 2) return false;        // EI_CLASS=2 → 64 位
            int machine = (h[18] & 0xFF) | ((h[19] & 0xFF) << 8); // e_machine 小端
            return machine == expectedMachine;
        } catch (IOException e) {
            return false;
        }
    }

    // ================================================================
    // deb(ar) -> data.tar.xz -> tar 流式抽取单个文件
    // ================================================================

    /** 从 .deb 中抽出指定 tar 条目（如 "./usr/bin/qemu-x86_64-static"）写到 dest */
    private static boolean extractTarEntry(File deb, String entryPath, File dest) throws IOException {
        FileChannel ch = new FileInputStream(deb).getChannel();
        try {
            // 1) 解析 ar，定位 data.tar.* 成员
            byte[] arMagic = new byte[8];
            readFullyAt(ch, 0, arMagic);
            String magic = new String(arMagic, "US-ASCII");
            if (!magic.startsWith("!<arch>")) throw new IOException("不是 ar 归档: " + deb);

            long pos = 8;
            long dataOff = -1;
            long dataLen = -1;
            String dataName = null;
            long fileLen = ch.size();
            while (pos + 60 <= fileLen) {
                byte[] hdr = new byte[60];
                readFullyAt(ch, pos, hdr);
                String name = new String(hdr, 0, 16, "US-ASCII").trim();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                long size = Long.parseLong(new String(hdr, 48, 10, "US-ASCII").trim());
                long bodyOff = pos + 60;
                if (name.startsWith("data.tar")) {
                    dataOff = bodyOff;
                    dataLen = size;
                    dataName = name;
                    break;
                }
                pos = bodyOff + size + (size % 2); // ar 成员按偶数字节对齐
            }
            if (dataOff < 0) throw new IOException("deb 内找不到 data.tar: " + deb);

            // 2) 解压 data.tar（xz / gz / 无压缩）
            InputStream raw = new BoundedChannelStream(ch, dataOff, dataLen);
            InputStream dec;
            if (dataName.endsWith(".xz")) {
                dec = new XZInputStream(new BufferedInputStream(raw));
            } else if (dataName.endsWith(".gz")) {
                dec = new java.util.zip.GZIPInputStream(new BufferedInputStream(raw));
            } else {
                dec = new BufferedInputStream(raw);
            }

            // 3) 顺序扫 tar，命中目标条目即写入
            try {
                return scanTar(dec, entryPath, dest);
            } finally {
                try { dec.close(); } catch (IOException ignored) { }
            }
        } finally {
            ch.close();
        }
    }

    private static boolean scanTar(InputStream tar, String target, File dest) throws IOException {
        String normalizedTarget = normalizeEntry(target);
        byte[] hdr = new byte[512];
        while (true) {
            if (!readFully(tar, hdr)) return false;
            if (isZeroBlock(hdr)) return false;   // tar 结束标记

            String name = new String(hdr, 0, 100, "UTF-8").trim();
            String prefix = new String(hdr, 345, 155, "UTF-8").trim();
            long size = parseOctal(hdr, 124, 12);
            char type = (char) hdr[156];

            String full = prefix.isEmpty() ? name : prefix + "/" + name;
            boolean regular = (type == '0' || type == '\0' || type == '7');

            if (regular && normalizeEntry(full).equals(normalizedTarget)) {
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    copyN(tar, out, size);
                }
                return true;
            }
            skipN(tar, size);
        }
    }

    private static String normalizeEntry(String path) {
        String p = path;
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static long parseOctal(byte[] b, int off, int len) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') break;
            sb.append((char) c);
        }
        if (sb.length() == 0) return 0;
        try {
            return Long.parseLong(sb.toString().trim(), 8);
        } catch (NumberFormatException e) {
            throw new IOException("tar 头 size 解析失败: " + sb);
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) return false;
            read += n;
        }
        return true;
    }

    private static void copyN(InputStream in, FileOutputStream out, long size) throws IOException {
        byte[] buf = new byte[65536];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("tar 数据提前结束");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void skipN(InputStream in, long size) throws IOException {
        long left = size;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            left -= n;
        }
    }

    private static void readFullyAt(FileChannel ch, long pos, byte[] buf) throws IOException {
        long p = pos;
        int read = 0;
        while (read < buf.length) {
            int n = (int) ch.read(java.nio.ByteBuffer.wrap(buf, read, buf.length - read), p + read);
            if (n <= 0) throw new IOException("ar 头读取越界");
            read += n;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    /** 从 FileChannel 指定区间读的 InputStream（不把 54MB deb 读进内存） */
    private static final class BoundedChannelStream extends InputStream {
        private final FileChannel ch;
        private long pos;
        private long remaining;

        BoundedChannelStream(FileChannel ch, long start, long length) {
            this.ch = ch;
            this.pos = start;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = (int) Math.min(len, remaining);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b, off, n);
            int got = ch.read(bb, pos);
            if (got <= 0) return -1;
            pos += got;
            remaining -= got;
            return got;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }
    }
}
