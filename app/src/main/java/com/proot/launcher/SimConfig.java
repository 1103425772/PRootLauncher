package com.proot.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * v24：app 配置文件 files/config/launch.conf。
 *
 * 文件格式（每行一条，`#` 开头为注释）：
 *   <rootfs名字>=real          → 该 rootfs 开启「真实模拟」（Tiny Container 启动命令）
 *   boot_command=...           → 可选：覆盖 Tiny Container 启动命令模板
 *
 * 真实模拟的 proot 启动命令参考
 * https://github.com/Cateners/tiny_container 的 boot_command，
 * 模板内可用占位符（由 ProotInstaller 替换）：
 *   $BIN_DIR / $CONTAINER_DIR / $CACHE_DIR / $EXTRA_ARGS / $EXTRA_ENV / $EXTRA_PATH
 */
public final class SimConfig {
    private static final String TAG = "SimConfig";
    private static final String CONF_NAME = "launch.conf";

    private SimConfig() {}

    /** 配置文件路径：files/config/launch.conf */
    public static File confFile(Context ctx) {
        return new File(ctx.getFilesDir(), "config/" + CONF_NAME);
    }

    /** 该 rootfs 是否开启真实模拟 */
    public static boolean isRealSim(Context ctx, String rootfsName) {
        if (rootfsName == null) return false;
        for (String line : readLines(ctx)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("boot_command=")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            String name = l.substring(0, eq).trim();
            String val = l.substring(eq + 1).trim();
            if (name.equals(rootfsName)) {
                return "real".equals(val) || "1".equals(val) || "true".equalsIgnoreCase(val);
            }
        }
        return false;
    }

    /** 开启/关闭真实模拟（保留文件里其它条目） */
    public static synchronized void setRealSim(Context ctx, String rootfsName, boolean on) {
        List<String> out = new ArrayList<>();
        boolean found = false;
        for (String line : readLines(ctx)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("boot_command=")) {
                out.add(line);
                continue;
            }
            int eq = l.indexOf('=');
            if (eq > 0 && l.substring(0, eq).trim().equals(rootfsName)) {
                found = true;
                out.add(rootfsName + "=" + (on ? "real" : "off"));
            } else {
                out.add(line);
            }
        }
        if (!found) {
            out.add(rootfsName + "=" + (on ? "real" : "off"));
        }
        writeLines(ctx, out);
        Log.i(TAG, "setRealSim " + rootfsName + "=" + on + " -> " + confFile(ctx));
    }

    /** 配置文件中的 boot_command 覆盖模板；没有则返回 null（用内置默认模板）。 */
    public static String getBootCommand(Context ctx) {
        for (String line : readLines(ctx)) {
            String l = line.trim();
            if (l.startsWith("boot_command=")) {
                String v = l.substring("boot_command=".length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 内置默认模板：Tiny Container proot-distro.tiny.yaml 的 boot_command 简化版 */
    public static String defaultBootCommand() {
        return "$BIN_DIR/proot $EXTRA_ARGS -H --kill-on-exit --link2symlink --sysvipc "
            + "--kernel-release=\"Linux localhost 6.17.0-PRoot-Distro "
            + "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000 aarch64 localdomain -1\" "
            + "-L --change-id=0:0 --rootfs=$CONTAINER_DIR --cwd=/root "
            + "--bind=/data --bind=/dev --bind=/proc --bind=/sys "
            + "--bind=/dev/urandom:/dev/random "
            + "--bind=/proc/self/fd:/dev/fd --bind=/proc/self/fd/0:/dev/stdin "
            + "--bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/2:/dev/stderr "
            + "--bind=$CONTAINER_DIR/tmp:/dev/shm --bind=$CACHE_DIR/tmp:/tmp "
            + "--bind=$CACHE_DIR/run:/run "
            + "/usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
            + "$EXTRA_ENV HOME=/root USER=root TERM=xterm-256color COLORTERM=truecolor /bin/bash -l";
    }

    private static List<String> readLines(Context ctx) {
        List<String> lines = new ArrayList<>();
        try {
            File f = confFile(ctx);
            if (f.exists()) {
                lines = java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "read " + CONF_NAME + " failed: " + e.getMessage());
        }
        return lines;
    }

    private static void writeLines(Context ctx, List<String> lines) {
        try {
            File f = confFile(ctx);
            f.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            java.nio.file.Files.write(f.toPath(), sb.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "write " + CONF_NAME + " failed: " + e.getMessage());
        }
    }

    // ================================================================
    // v23：跨架构模拟（QEMU）配置
    // v25：扩展 8 个 tur-packages 的 qemu-user 目标架构
    // ================================================================

    /** QEMU 架构选项 */
    public static final String QEMU_NONE = "none";
    public static final String QEMU_X86_64 = "x86_64";
    public static final String QEMU_I386 = "i386";
    public static final String QEMU_ARM = "arm";
    public static final String QEMU_AARCH64 = "aarch64";
    public static final String QEMU_M68K = "m68k";
    public static final String QEMU_PPC = "ppc";
    public static final String QEMU_PPC64 = "ppc64";
    public static final String QEMU_RISCV64 = "riscv64";
    public static final String QEMU_RISCV32 = "riscv32";

    /** Debian 12 (bookworm) 官方 qemu-user-static 包版本 */
    public static final String QEMU_DEB_VERSION = "7.2+dfsg-7+deb12u18+b3";

    /**
     * 宿主设备架构对应的 Debian 包架构。
     * arm64 设备 → arm64；x86_64 设备 → amd64；
     * 32 位 arm/x86 宿主 Debian 未提供 static 包，返回 null（走 multiarch 回退）。
     */
    public static String qemuDebArch() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        String abi = (abis != null && abis.length > 0) ? abis[0].toLowerCase() : "arm64-v8a";
        if (abi.contains("arm64") || abi.contains("aarch64")) return "arm64";
        if (abi.contains("x86_64")) return "amd64";
        return null;
    }

    /** 该 rootfs 的 QEMU 模拟架构（默认 none） */
    public static String getQemuArch(Context ctx, String rootfsName) {
        if (rootfsName == null) return QEMU_NONE;
        String key = rootfsName + ".qemu=";
        for (String line : readLines(ctx)) {
            String l = line.trim();
            if (l.startsWith(key)) {
                return l.substring(key.length()).trim();
            }
        }
        return QEMU_NONE;
    }

    /** 设置该 rootfs 的 QEMU 模拟架构 */
    public static synchronized void setQemuArch(Context ctx, String rootfsName, String arch) {
        List<String> out = new ArrayList<>();
        String key = rootfsName + ".qemu=";
        boolean found = false;
        for (String line : readLines(ctx)) {
            String l = line.trim();
            if (l.startsWith(key)) {
                found = true;
                out.add(key + arch);
            } else {
                out.add(line);
            }
        }
        if (!found) {
            out.add(key + arch);
        }
        writeLines(ctx, out);
        Log.i(TAG, "setQemuArch " + rootfsName + "=" + arch);
    }

    /**
     * 检测本机架构，返回应从 QEMU 选项列表中排除的架构。
     * 例如 arm64 设备排除 aarch64（不需要模拟同架构）。
     */
    public static String getNativeArch() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) return QEMU_AARCH64;
        String abi = abis[0].toLowerCase();
        if (abi.contains("arm64") || abi.contains("aarch64")) return QEMU_AARCH64;
        if (abi.contains("x86_64")) return QEMU_X86_64;
        if (abi.contains("x86") || abi.contains("i686")) return QEMU_I386;
        if (abi.contains("arm")) return QEMU_ARM;
        return QEMU_AARCH64;
    }

    /**
     * QEMU 包名映射 —— tur-packages（Termux User Repository）里的版本化包名。
     * v25：全部 8 个目标架构对应 tur 子包（qemu-system-x86-64-9 / qemu-system-i386-10 的子包）：
     *   qemu-user-x86-64-9   9.2.4-1 arm  （QEMU 9.x）
     *   qemu-user-aarch64-9  9.2.4-1 arm
     *   qemu-user-ppc64-9    9.2.4-1 arm
     *   qemu-user-riscv64-9  9.2.4-1 arm
     *   qemu-user-arm-10     10.2.1-1 arm （QEMU 10.x）
     *   qemu-user-i386-10    10.2.1-1 arm
     *   qemu-user-m68k-10    10.2.1-1 arm
     *   qemu-user-ppc-10     10.2.1-1 arm
     * 注意：这些是 Termux 架构的 .deb（宿主侧），rootfs 内安装请用发行版自带的
     * qemu-user 包（Debian/Ubuntu: apt install qemu-user，二进制名一致）。
     */
    public static String qemuPkgName(String arch) {
        if (qemuBinName(arch) == null) return null;
        return "qemu-user-static";
    }

    /** 安装来源的大版本（Debian 12 官方包：QEMU 7.2） */
    public static String qemuMajor(String arch) {
        return "7.2";
    }

    /** 包版本（Debian bookworm 官方包版本） */
    public static String qemuPkgVersion(String arch) {
        return QEMU_DEB_VERSION;
    }

    /** 包架构：随宿主 ABI（arm64 / amd64），32 位宿主回退 multiarch */
    public static String qemuPkgArch() {
        String a = qemuDebArch();
        return a == null ? "armhf(multiarch)" : a;
    }

    /** QEMU 二进制名映射（rootfs 里 /usr/bin/qemu-xxx，tur 包里 bin/qemu-xxx） */
    public static String qemuBinName(String arch) {
        if (QEMU_X86_64.equals(arch)) return "qemu-x86_64";
        if (QEMU_I386.equals(arch)) return "qemu-i386";
        if (QEMU_ARM.equals(arch)) return "qemu-arm";
        if (QEMU_AARCH64.equals(arch)) return "qemu-aarch64";
        if (QEMU_M68K.equals(arch)) return "qemu-m68k";
        if (QEMU_PPC.equals(arch)) return "qemu-ppc";
        if (QEMU_PPC64.equals(arch)) return "qemu-ppc64";
        if (QEMU_RISCV64.equals(arch)) return "qemu-riscv64";
        if (QEMU_RISCV32.equals(arch)) return "qemu-riscv32";
        return null;
    }

    /**
     * guest 动态链接器前缀（-L 参数）。
     * Debian/Ubuntu 风格：/usr/<triplet>；其它发行版可能不同，
     * 可用 files/config/launch.conf 的 boot_command 覆盖。
     */
    public static String qemuLdPrefix(String arch) {
        if (QEMU_X86_64.equals(arch)) return "/usr/x86_64-linux-gnu";
        if (QEMU_I386.equals(arch)) return "/usr/i386-linux-gnu";
        if (QEMU_ARM.equals(arch)) return "/usr/arm-linux-gnueabi";
        if (QEMU_AARCH64.equals(arch)) return "/usr/aarch64-linux-gnu";
        if (QEMU_M68K.equals(arch)) return "/usr/m68k-linux-gnu";
        if (QEMU_PPC.equals(arch)) return "/usr/powerpc-linux-gnu";
        if (QEMU_PPC64.equals(arch)) return "/usr/powerpc64-linux-gnu";
        if (QEMU_RISCV64.equals(arch)) return "/usr/riscv64-linux-gnu";
        if (QEMU_RISCV32.equals(arch)) return "/usr/riscv32-linux-gnu";
        return "/";
    }
}
