package com.proot.launcher;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * proot 安装与启动管理器。
 *
 * 仿照 openclaw-termux 的 proot 实现（ProcessManager / BootstrapManager）：
 *  - proot 二进制以 jniLibs .so 形式打包（libproot.so / libproot_loader.so），
 *    Android 自动解压到 nativeLibraryDir 并赋予可执行权限（绕开 W^X）。
 *  - 启动命令匹配 Termux proot-distro：--link2symlink -L --kill-on-exit、
 *    --change-id=0:0、--sysvipc、完整 bind 列表、fake /proc|/sys 数据、
 *    resolv.conf 绑定，以及 `env -i` 干净 guest 环境。
 */
public class ProotInstaller {
    private static final String TAG = "ProotInstaller";

    // 匹配 proot-distro 的 fake kernel 信息
    public static final String FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Distro";
    public static final String FAKE_KERNEL_VERSION =
        "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000";

    private final Context context;
    private final File binDir;
    private final File libDir;
    private final File libexecDir;
    private final File shareDir;
    private final File includeDir;
    private final File tmpDir;
    private final File configDir;
    private final File homeDir;
    // proot/loader 由 Android 从 jniLibs 解压到 nativeLibraryDir（可执行，W^X 兼容）
    private final File nativeLibDir;

    public ProotInstaller(Context context) {
        this.context = context;
        File filesDir = context.getFilesDir();
        this.binDir = new File(filesDir, "bin");
        this.libDir = new File(filesDir, "lib");
        this.libexecDir = new File(filesDir, "libexec");
        this.shareDir = new File(filesDir, "share");
        this.includeDir = new File(filesDir, "include");
        this.tmpDir = new File(filesDir, "tmp");
        this.configDir = new File(filesDir, "config");
        this.homeDir = new File(filesDir, "home");
        this.nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
    }

    public File getBinDir() { return binDir; }
    public File getLibDir() { return libDir; }
    public File getLibexecDir() { return libexecDir; }
    public File getShareDir() { return shareDir; }
    public File getIncludeDir() { return includeDir; }
    public File getTmpDir() { return tmpDir; }
    public File getConfigDir() { return configDir; }
    public File getHomeDir() { return homeDir; }
    public File getNativeLibDir() { return nativeLibDir; }

    /** proot 主程序（jniLibs 打包，Android 已解压且可执行） */
    public File getProotBinary() { return new File(nativeLibDir, "libproot.so"); }
    /** proot loader（execve 拦截用） */
    public File getLoader() { return new File(nativeLibDir, "libproot_loader.so"); }

    public boolean isInstalled() {
        return getProotBinary().exists() && getLoader().exists();
    }

    // ================================================================
    // 运行时准备：目录、fake /proc|/sys、resolv.conf
    // 与 openclaw BootstrapManager.setupDirectories() 对齐
    // ================================================================

    /** 启动前必须调用：创建运行时目录 + fake 数据 + DNS 配置 */
    public void prepareRuntime() {
        binDir.mkdirs();
        libDir.mkdirs();
        libexecDir.mkdirs();
        shareDir.mkdirs();
        includeDir.mkdirs();
        tmpDir.mkdirs();
        configDir.mkdirs();
        homeDir.mkdirs();
        // X11 socket 目录预先建好（proot bind 目标必须存在，X server 后写 X0 进去）
        new File(tmpDir, ".X11-unix").mkdirs();
        // 真实模拟模板会 --bind=files/run:/run，目录必须存在
        new File(context.getFilesDir(), "run").mkdirs();
        setupFakeSysdata();
        ensureResolvConf();
        ensureRootfsDirs();
    }

    /**
     * 创建 fake /proc 与 /sys 数据（Android 限制宿主 /proc 读取，
     * proot-distro 用静态假数据绕过）。仿照 openclaw setupFakeSysdata()。
     */
    private void setupFakeSysdata() {
        File procDir = new File(configDir, "proc_fakes");
        File sysDir = new File(configDir, "sys_fakes");
        procDir.mkdirs();
        sysDir.mkdirs();

        writeIfMissing(new File(procDir, "loadavg"), "0.12 0.07 0.02 2/165 765\n");
        writeIfMissing(new File(procDir, "stat"),
            "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
            "cpu0 31 0 226 12027 82 10 4 9 0 0\n" +
            "cpu1 45 0 290 11498 21 9 8 7 0 0\n" +
            "cpu2 52 0 401 11730 36 15 6 10 0 0\n" +
            "cpu3 42 0 268 11677 31 12 5 8 0 0\n" +
            "cpu4 789 0 720 11364 26 100 83 18 0 0\n" +
            "cpu5 486 0 438 11685 42 86 60 13 0 0\n" +
            "cpu6 314 0 336 11808 45 68 52 11 0 0\n" +
            "cpu7 198 0 198 11491 25 42 36 11 0 0\n" +
            "intr 63361\n" +
            "ctxt 38014093\n" +
            "btime 1694292441\n" +
            "processes 26442\n" +
            "procs_running 1\n" +
            "procs_blocked 0\n");
        writeIfMissing(new File(procDir, "uptime"), "124.08 932.80\n");
        writeIfMissing(new File(procDir, "version"),
            "Linux version " + FAKE_KERNEL_RELEASE + " (proot@termux) " +
            "(gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) " +
            FAKE_KERNEL_VERSION + "\n");
        writeIfMissing(new File(procDir, "vmstat"),
            "nr_free_pages 1743136\n" +
            "nr_zone_inactive_anon 179281\n" +
            "nr_zone_active_anon 7183\n" +
            "nr_zone_inactive_file 22858\n" +
            "nr_zone_active_file 51328\n" +
            "nr_zone_unevictable 642\n" +
            "nr_mlock 0\n" +
            "pgpgin 198292\n" +
            "pgpgout 7674\n" +
            "pswpin 0\n" +
            "pswpout 0\n" +
            "pgalloc_normal 44669136\n" +
            "pgfree 46674674\n" +
            "pgfault 37291463\n" +
            "pgmajfault 6854\n");
        writeIfMissing(new File(procDir, "cap_last_cap"), "40\n");
        writeIfMissing(new File(procDir, "max_user_watches"), "4096\n");
        // libgcrypt 启动时读取；缺失会让 apt HTTP 方法 SIGABRT
        writeIfMissing(new File(procDir, "fips_enabled"), "0\n");
        // /sys/fs/selinux 绑定用空文件
        writeIfMissing(new File(sysDir, "empty"), "");
    }

    /** 写 resolv.conf：Android 系统 DNS + 公共 DNS 兜底。
     *  glibc 解析器最多读 3 个 nameserver，第 4 个会被静默忽略，
     *  因此只写 3 个（Google ×2 + 阿里公共 DNS）。 */
    private void ensureResolvConf() {
        String content = "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 223.5.5.5\n";
        try {
            File f = new File(configDir, "resolv.conf");
            if (!f.exists() || f.length() == 0L) {
                f.getParentFile().mkdirs();
                java.io.FileWriter w = new java.io.FileWriter(f);
                w.write(content);
                w.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "write resolv.conf failed", e);
        }
    }

    /**
     * 确保 rootfs 关键运行时目录存在。
     * Android 10+ 下 proot 的 mkdir 系统调用可能损坏/失败，
     * 预创建 dpkg/apt/临时目录（仿照 openclaw configureRootfs()）。
     */
    private void ensureRootfsDirs() {
        File rootfs = new File(context.getFilesDir(), "rootfs");
        String[][] candidates = {
            { "etc/apt/apt.conf.d" },
            { "etc/dpkg/dpkg.cfg.d" },
            { "var/lib/dpkg/updates" },
            { "var/lib/dpkg/triggers" },
            { "var/tmp" },
            { "run" },
            { "run/lock" },
            { "dev/shm" },
            { "tmp" },
            // termux-x11 socket bind 目标：--shared-x11 等价（$PREFIX/tmp/.X11-unix → /tmp/.X11-unix）
            { "tmp/.X11-unix" },
        };
        // 每个已解压的 rootfs 都尝试补目录（静默失败）
        File[] rootfses = rootfs.listFiles(File::isDirectory);
        if (rootfses == null) return;
        for (File rf : rootfses) {
            for (String[] rel : candidates) {
                File d = new File(rf, rel[0]);
                if (!d.exists()) d.mkdirs();
            }
            // /dev/shm 由 --bind rootfs/tmp:/dev/shm 提供，tmp 必须可写
            File tmp = new File(rf, "tmp");
            if (tmp.exists()) {
                tmp.setReadable(true, false);
                tmp.setWritable(true, false);
                tmp.setExecutable(true, false);
            }
            // /etc/resolv.conf 兜底写入（bind 失败时 DNS 仍可用）
            try {
                File resolv = new File(rf, "etc/resolv.conf");
                if (!resolv.exists() || resolv.length() == 0L) {
                    resolv.getParentFile().mkdirs();
                    java.io.FileWriter w = new java.io.FileWriter(resolv);
                    w.write("nameserver 8.8.8.8\nnameserver 223.5.5.5\n");
                    w.close();
                }
            } catch (Exception e) { /* ignore */ }
        }
    }

    private void writeIfMissing(File f, String content) {
        try {
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                java.io.FileWriter w = new java.io.FileWriter(f);
                w.write(content);
                w.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "write fake data failed: " + f.getAbsolutePath(), e);
        }
    }

    // ================================================================
    // 宿主侧环境（proot 自身用）。
    // 注意：guest 环境在命令行里用 `env -i` 设置，这里只放 proot
    // 进程需要的变量，避免宿主 JVM 环境泄漏进容器。
    // ================================================================

    public String[] buildEnv() {
        List<String> envList = new ArrayList<>();
        // v28：必须有宿主 PATH —— TerminalActivity 里 environment().clear() 后
        // 子进程没有 PATH，proot 会报 `$PATH=(null)` 并按空 PATH 查找可执行文件
        // （跨架构时即 "qemu-x86_64-static not found"）。这里注入宿主 PATH 兜底。
        envList.add("PATH=" + binDir.getAbsolutePath() + ":/system/bin:/system/xbin:/vendor/bin");
        // v30：内置 qemu 是 bionic 动态二进制（termux 11.1.3 拆分包），
        // 依赖的 libglib/libgnutls/libpixman 等一并内置在 files/qemu/builtin/lib，
        // 这里加进 LD_LIBRARY_PATH 让 proot 的 -q 子进程能找到它们。
        envList.add("LD_LIBRARY_PATH=" + libDir.getAbsolutePath() + ":"
            + nativeLibDir.getAbsolutePath() + ":"
            + QemuSupport.builtinLibDir(context).getAbsolutePath());
        envList.add("PROOT_LOADER=" + getLoader().getAbsolutePath());
        envList.add("PROOT_TMP_DIR=" + tmpDir.getAbsolutePath());
        return envList.toArray(new String[0]);
    }

    // ================================================================
    // proot 启动命令 —— 完全仿照 openclaw ProcessManager.buildGatewayCommand()
    // 匹配 Termux proot-distro 的 command_login()
    // ================================================================

    /** 构建 proot 的公共 flags（proot 二进制 + bind + fake proc 等） */
    private List<String> buildCommonFlags(File rootfsDir) {
        String proot = getProotBinary().getAbsolutePath();
        String rootfs = rootfsDir.getAbsolutePath();
        String procFakes = new File(configDir, "proc_fakes").getAbsolutePath();
        String sysFakes = new File(configDir, "sys_fakes").getAbsolutePath();
        String resolv = new File(configDir, "resolv.conf").getAbsolutePath();

        String machine = getUnameMachine();
        String kernelRelease = "\\Linux\\localhost\\" + FAKE_KERNEL_RELEASE +
            "\\" + FAKE_KERNEL_VERSION + "\\" + machine + "\\localdomain\\-1\\";

        List<String> cmd = new ArrayList<>();
        cmd.add(proot);
        cmd.add("--link2symlink");
        cmd.add("-L");
        cmd.add("--kill-on-exit");
        cmd.add("--change-id=0:0");
        cmd.add("--sysvipc");
        cmd.add("--kernel-release=" + kernelRelease);
        cmd.add("--rootfs=" + rootfs);
        cmd.add("--cwd=/root");

        cmd.add("--bind=/dev");
        cmd.add("--bind=/dev/urandom:/dev/random");
        cmd.add("--bind=/proc");
        cmd.add("--bind=/proc/self/fd:/dev/fd");
        cmd.add("--bind=/proc/self/fd/0:/dev/stdin");
        cmd.add("--bind=/proc/self/fd/1:/dev/stdout");
        cmd.add("--bind=/proc/self/fd/2:/dev/stderr");
        cmd.add("--bind=/sys");

        cmd.add("--bind=" + procFakes + "/loadavg:/proc/loadavg");
        cmd.add("--bind=" + procFakes + "/stat:/proc/stat");
        cmd.add("--bind=" + procFakes + "/uptime:/proc/uptime");
        cmd.add("--bind=" + procFakes + "/version:/proc/version");
        cmd.add("--bind=" + procFakes + "/vmstat:/proc/vmstat");
        cmd.add("--bind=" + procFakes + "/cap_last_cap:/proc/sys/kernel/cap_last_cap");
        cmd.add("--bind=" + procFakes + "/max_user_watches:/proc/sys/fs/inotify/max_user_watches");
        cmd.add("--bind=" + procFakes + "/fips_enabled:/proc/sys/crypto/fips_enabled");

        cmd.add("--bind=" + rootfs + "/tmp:/dev/shm");
        cmd.add("--bind=" + sysFakes + "/empty:/sys/fs/selinux");
        cmd.add("--bind=" + resolv + ":/etc/resolv.conf");
        cmd.add("--bind=" + homeDir.getAbsolutePath() + ":/root/home");

        // termux-x11 socket：等价 proot-distro --shared-x11，
        // 把 X server 的 .X11-unix 目录绑到容器 /tmp/.X11-unix，
        // DISPLAY=:0 才能连上。
        // v22 修复：X server（app_process 跑 loader）由本 app 启动，
        // TMPDIR 被指到 files/tmp，socket 落在 files/tmp/.X11-unix/X0——
        // 这是本 app 自己的数据目录，一定可读、一定能 bind。
        // 旧逻辑只探测 /data/data/com.termux(.x11)/... 跨应用目录，
        // Android 10+ 隔离后永远读不到，bind 从不发生，xterm 报 Can't open display。
        List<String> x11SocketCandidates = new ArrayList<>();
        x11SocketCandidates.add(new File(context.getFilesDir(), "tmp/.X11-unix").getAbsolutePath());
        x11SocketCandidates.add("/tmp/.X11-unix");
        x11SocketCandidates.add("/data/data/com.termux.x11/files/usr/tmp/.X11-unix");
        x11SocketCandidates.add("/data/data/com.termux/files/usr/tmp/.X11-unix");
        for (String p : x11SocketCandidates) {
            File d = new File(p);
            if (d.isDirectory()) {
                cmd.add("--bind=" + d.getAbsolutePath() + ":/tmp/.X11-unix");
                break;
            }
        }

        boolean hasAccess = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasAccess = Environment.isExternalStorageManager();
        } else {
            File sdcard = Environment.getExternalStorageDirectory();
            hasAccess = sdcard.exists() && sdcard.canRead();
        }
        if (hasAccess) {
            cmd.add("--bind=/storage:/storage");
            cmd.add("--bind=/storage/emulated/0:/sdcard");
        }
        return cmd;
    }

    /**
     * 默认交互 shell（v19 方案）：rootfs 的 /bin/sh（busybox/alpine 一定有），
     * 其次 bash（bin 或 usr/bin）/zsh。
     */
    public String defaultShellPath(File rootfsDir) {
        String shell = "/bin/sh";
        if (new File(rootfsDir, "bin/bash").exists()) shell = "/bin/bash";
        else if (new File(rootfsDir, "usr/bin/bash").exists()) shell = "/bin/bash";
        else if (new File(rootfsDir, "bin/zsh").exists()) shell = "/bin/zsh";
        return shell;
    }

    /**
     * v28：跨架构模拟 —— proot 的 `-q <QEMU 绝对路径>`。
     *
     * v25 把 "qemu-x86_64-static -L /usr/..." 当作 proot 之后的 argv 命令名，
     * 由容器内 shell 去执行，容器 PATH 为空时 proot 直接报
     *   `'qemu-x86_64-static' not found (..., $PATH=(null))`
     * 而 multiarch 的静态包又是 x86_64 宿主构建，arm64 设备执行不了。
     *
     * v28 改用 proot 自带的 -q 机制：qemu 由 proot 在宿主侧调用（绝对路径），
     * 透明的接管容器内每个 execve，不依赖容器 PATH，也不需要 guest 侧 -L。
     */
    private void addQemuArgs(List<String> cmd, File rootfsDir) {
        String qemuArch = SimConfig.getQemuArch(context, rootfsDir.getName());
        if (SimConfig.QEMU_NONE.equals(qemuArch)) return;
        // v29：32 位宿主无法承载静态 QEMU（proot 只能执行 32 位程序），不注入 -q
        if (!SimConfig.hostIs64()) {
            Log.w(TAG, "宿主 32 位 ABI，跳过 -q（无法跨架构模拟）");
            return;
        }
        File host = QemuSupport.hostQemu(context, qemuArch);
        if (host == null) {
            Log.w(TAG, "QEMU 架构无对应二进制: " + qemuArch);
            return;
        }
        if (!host.isFile()) {
            Log.w(TAG, "QEMU 静态模拟器缺失（应先 ensureInstalled）: " + host.getAbsolutePath());
            return;
        }
        host.setExecutable(true, false);
        cmd.add("-q");
        cmd.add(host.getAbsolutePath());
        Log.i(TAG, "QEMU sim: " + qemuArch + " -> -q " + host.getAbsolutePath());
    }

    /**
     * 交互式 shell（内置终端用）。
     *
     * 优先用 `script -qec ... /dev/null` 分配 PTY（util-linux 提供，
     * Debian/Ubuntu/Fedora 等完整 rootfs 自带）；若 rootfs 是精简版
     * （alpine minirootfs 等只有 busybox，无 util-linux），则降级为
     * 直接 exec 交互式 shell——通过管道通信，无 PTY/job control 但可正常用。
     *
     * v23/v25：若开启跨架构模拟（QEMU），qemu-<arch>[-static] 前缀作用于
     * 外层 /bin/sh（qemu-user 透明接管其 fork/exec 的子进程，整 rootfs 按
     * 目标架构真实运行）。
     */
    public String[] buildProotCommand(File rootfsDir, String shell) {
        List<String> cmd = buildCommonFlags(rootfsDir);
        // v25：跨架构模拟 QEMU 前缀（真实模拟）→ argv 插入 proot 之后
        addQemuTokens(cmd, rootfsDir);
        cmd.add("/bin/sh");
        cmd.add("-c");
        String guestShell = (shell != null && !shell.isEmpty()) ? shell : defaultShellPath(rootfsDir);

        // 检测 rootfs 里是否有 script（util-linux 的 /usr/bin/script）。
        // busybox 不提供 script applet；alpine minirootfs 等精简 rootfs 缺它，
        // 硬调会报 "script: not found"（exit 127）。
        boolean hasScript =
            new File(rootfsDir, "usr/bin/script").isFile() ||
            new File(rootfsDir, "bin/script").isFile();

        String envScript;
        // v24：X11 + pulse + llvmpipe/Wayland 环境一起注入容器
        String guestEnv = X11Support.guestEnvPrefix(context)
            + AudioSupport.guestEnvPrefix()
            + GpuSupport.guestEnvPrefix();
        if (hasScript) {
            envScript =
                "export HOME=/root USER=root LANG=C.UTF-8 " +
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "TERM=xterm-256color TMPDIR=/tmp " + guestEnv +
                "PS1='root@localhost:\\w# '; " +
                "exec script -qec \"" + guestShell + " -i\" /dev/null";
        } else {
            // 无 script：直接交互式 shell（管道通信，无 PTY）
            envScript =
                "export HOME=/root USER=root LANG=C.UTF-8 " +
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "TERM=xterm-256color TMPDIR=/tmp " + guestEnv +
                "PS1='root@localhost:\\w# '; " +
                "exec " + guestShell + " -i";
        }
        cmd.add(envScript);
        return cmd.toArray(new String[0]);
    }

    /**
     * v25：把 QEMU 前缀拆成 argv token 插入 cmd（proot 之后、/bin/sh 之前）。
     * 前缀形如 "qemu-x86_64-static -L /usr/x86_64-linux-gnu"。
     */
    /**
     * v28：兼容旧调用点 —— 等价 addQemuArgs（输出 proot 的 "-q <abs>" 两个 token），
     * 不再把 qemu 作为 argv 命令前缀。
     */
    private void addQemuTokens(List<String> cmd, File rootfsDir) {
        addQemuArgs(cmd, rootfsDir);
    }

    /**
     * v23（v25 更新）：根据 QEMU 架构取 guest 动态链接器前缀路径。
     * 不同发行版路径不同，这里用最常见的 Debian/Ubuntu 风格
     * （全部 8 个目标架构映射见 SimConfig.qemuLdPrefix）。
     */
    private String guessQemuLdPrefix(String arch) {
        return SimConfig.qemuLdPrefix(arch);
    }

    /**
     * 在 proot 里执行任意命令（非交互，输出到 stdout/stderr）。
     * 用于安装 ttyd、启动后台服务等。
     *
     * 安全：命令不经过嵌套 shell 字符串拼接（旧版只转义双引号，$()/`/; 等
     * 都能注入）。改为把 command 原样写入 rootfs 内临时脚本，再
     * `sh /tmp/xxx.sh` 执行，命令内容完全不参与外层 shell 解析。
     *
     * v25：跨架构模拟开启时，qemu 前缀作用于外层 /bin/sh（整 rootfs 目标
     * 架构真实运行；qemu-user 透明接管子进程 execve）。
     */
    public String[] buildProotExecCommand(File rootfsDir, String command) {
        return buildProotExecCommand(rootfsDir, command, true);
    }

    /** withQemu=false 用于引导场景（rootfs 尚无 qemu 时执行宿主架构命令）。 */
    public String[] buildProotExecCommand(File rootfsDir, String command, boolean withQemu) {
        List<String> cmd = buildCommonFlags(rootfsDir);
        if (withQemu) {
            addQemuTokens(cmd, rootfsDir);
        }
        String scriptName = writeCommandScript(rootfsDir, command);
        cmd.add("/bin/sh");
        cmd.add("-c");
        // 干净 guest 环境；优先跑临时脚本（脚本路径固定无用户输入，免疫注入），
        // 写脚本失败时降级为旧的内联转义方式（功能不丢）
        String envScript;
        // v24：X11 + pulse + llvmpipe/Wayland 环境一起注入容器
        String guestEnv = X11Support.guestEnvPrefix(context)
            + AudioSupport.guestEnvPrefix()
            + GpuSupport.guestEnvPrefix();
        if (scriptName != null) {
            envScript =
                "export HOME=/root USER=root LANG=C.UTF-8 " +
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "TERM=xterm-256color TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive " + guestEnv + "; " +
                "sh /tmp/" + scriptName + "; rc=$?; rm -f /tmp/" + scriptName + "; exit $rc";
        } else {
            // 降级分支：用单引号包裹并转义（' -> '\''），单引号内 $()/`/;/&&
            // 都不会被外层 sh 求值，command 原样传给内层 /bin/sh -c 执行，
            // 不再存在旧版「只转义双引号导致命令注入」的问题。
            envScript =
                "export HOME=/root USER=root LANG=C.UTF-8 " +
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "TERM=xterm-256color TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive " + guestEnv + "; " +
                "exec /bin/sh -c '" + command.replace("'", "'\\''") + "'";
        }
        cmd.add(envScript);
        return cmd.toArray(new String[0]);
    }

    /**
     * 真实模拟（v24）：按 Tiny Container（https://github.com/Cateners/tiny_container）
     * 的 boot_command 模板构建 proot 启动命令。
     *
     * 模板来自 app 配置文件 files/config/launch.conf 的 `boot_command=` 行，
     * 缺失时用内置默认模板（tiny_container proot-distro.tiny.yaml 简化版）。
     * 占位符：
     *   $BIN_DIR      宿主 bin 目录（$BIN_DIR/proot → 实际 proot 二进制路径）
     *   $CONTAINER_DIR rootfs 目录
     *   $CACHE_DIR    files 目录（tmp/run 挂进容器）
     *   $EXTRA_ARGS   fake /proc|/sys、resolv.conf、X11 socket、存储绑定
     *   $EXTRA_ENV    X11 / pulse / llvmpipe 环境前缀
     *   $EXTRA_PATH   额外 PATH（本项目无）
     */
    public String[] buildTinyContainerCommand(File rootfsDir, String shell) {
        String template = SimConfig.getBootCommand(context);
        if (template == null || template.trim().isEmpty()) {
            template = SimConfig.defaultBootCommand();
        }
        List<String> extraArgs = buildTinyExtraArgs(rootfsDir);
        String extraEnv = X11Support.guestEnvPrefix(context)
            + AudioSupport.guestEnvPrefix()
            + GpuSupport.guestEnvPrefix();
        File filesDir = context.getFilesDir();
        String cacheDir = filesDir.getAbsolutePath();
        String containerDir = rootfsDir.getAbsolutePath();
        String bDir = binDir.getAbsolutePath();
        String shellPath = (shell != null && !shell.isEmpty())
            ? shell : defaultShellPath(rootfsDir);

        // 模板里用到的宿主目录确保存在
        new File(filesDir, "run").mkdirs();
        new File(filesDir, "tmp").mkdirs();

        List<String> cmd = new ArrayList<>();
        for (String tok : tokenize(template)) {
            if (tok.equals("$EXTRA_ARGS")) { cmd.addAll(extraArgs); continue; }
            if (tok.equals("$EXTRA_ENV")) { cmd.addAll(splitEnv(extraEnv)); continue; }
            if (tok.equals("$EXTRA_PATH")) continue;
            if (tok.equals("$BIN_DIR/proot")) {
                cmd.add(getProotBinary().getAbsolutePath());
                continue;
            }
            if (tok.equals("/bin/bash")) { cmd.add(shellPath); continue; }
            tok = tok.replace("$BIN_DIR", bDir)
                     .replace("$CONTAINER_DIR", containerDir)
                     .replace("$CACHE_DIR", cacheDir);
            cmd.add(tok);
        }

        // v25：跨架构模拟 QEMU 前缀（真实模拟）：插入到 proot 之后，
        // 让整 rootfs 按目标架构运行（qemu-user 透明接管子进程 execve）
        List<String> qemuTokens = new ArrayList<>();
        addQemuTokens(qemuTokens, rootfsDir);
        if (!qemuTokens.isEmpty() && cmd.size() > 1) {
            List<String> withQemu = new ArrayList<>();
            withQemu.add(cmd.get(0));
            withQemu.addAll(qemuTokens);
            for (int i = 1; i < cmd.size(); i++) withQemu.add(cmd.get(i));
            cmd = withQemu;
        }
        return cmd.toArray(new String[0]);
    }

    /** 真实模拟的 EXTRA_ARGS：fake /proc|/sys、resolv.conf、X11 socket、存储绑定 */
    private List<String> buildTinyExtraArgs(File rootfsDir) {
        List<String> args = new ArrayList<>();
        String procFakes = new File(configDir, "proc_fakes").getAbsolutePath();
        String sysFakes = new File(configDir, "sys_fakes").getAbsolutePath();
        String resolv = new File(configDir, "resolv.conf").getAbsolutePath();
        args.add("--bind=" + procFakes + "/loadavg:/proc/loadavg");
        args.add("--bind=" + procFakes + "/stat:/proc/stat");
        args.add("--bind=" + procFakes + "/uptime:/proc/uptime");
        args.add("--bind=" + procFakes + "/version:/proc/version");
        args.add("--bind=" + procFakes + "/vmstat:/proc/vmstat");
        args.add("--bind=" + procFakes + "/cap_last_cap:/proc/sys/kernel/cap_last_cap");
        args.add("--bind=" + procFakes + "/max_user_watches:/proc/sys/fs/inotify/max_user_watches");
        args.add("--bind=" + procFakes + "/fips_enabled:/proc/sys/crypto/fips_enabled");
        args.add("--bind=" + sysFakes + "/empty:/sys/fs/selinux");
        args.add("--bind=" + resolv + ":/etc/resolv.conf");
        args.add("--bind=" + homeDir.getAbsolutePath() + ":/root/home");

        // termux-x11 socket（X server 由本 app 启动，socket 落在 files/tmp/.X11-unix）
        File x11Sock = new File(context.getFilesDir(), "tmp/.X11-unix");
        if (x11Sock.isDirectory()) {
            args.add("--bind=" + x11Sock.getAbsolutePath() + ":/tmp/.X11-unix");
        }

        // 存储（与 buildCommonFlags 同一套授权判断）
        boolean hasAccess = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasAccess = Environment.isExternalStorageManager();
        } else {
            File sdcard = Environment.getExternalStorageDirectory();
            hasAccess = sdcard.exists() && sdcard.canRead();
        }
        if (hasAccess) {
            args.add("--bind=/storage:/storage");
            args.add("--bind=/storage/emulated/0:/sdcard");
        }
        return args;
    }

    /** 类 shell 分词：保留双引号内的空格（kernel-release 等），去掉引号本身。 */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { inQ = !inQ; continue; }
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                if (!inQ) {
                    if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                } else {
                    cur.append(c);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 环境前缀按空白拆成多个 token（无带空格引号的值）。 */
    private static List<String> splitEnv(String env) {
        List<String> out = new ArrayList<>();
        for (String t : env.trim().split("\\s+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 把命令写入 rootfs 的 /tmp/proot-cmd-<nano>.sh（不经 shell 转义）。
     *  成功返回脚本名，失败返回 null（调用方降级为内联转义）。 */
    private String writeCommandScript(File rootfsDir, String command) {
        String scriptName = "proot-cmd-" + System.nanoTime() + ".sh";
        File tmpDir = new File(rootfsDir, "tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File script = new File(tmpDir, scriptName);
        try {
            java.nio.file.Files.write(script.toPath(), (command + "\n").getBytes("UTF-8"));
            script.setReadable(true, false);
            script.setExecutable(false, false);
            return scriptName;
        } catch (IOException e) {
            Log.e(TAG, "write command script failed, fallback to inline: " + e.getMessage());
            return null;
        }
    }

    /** 返回 proot 需要的 uname -m 值 */
    private String getUnameMachine() {
        if (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            String abi = Build.SUPPORTED_64_BIT_ABIS[0];
            if ("arm64-v8a".equals(abi)) return "aarch64";
            if ("x86_64".equals(abi)) return "x86_64";
        }
        if (Build.SUPPORTED_32_BIT_ABIS != null && Build.SUPPORTED_32_BIT_ABIS.length > 0) {
            String abi = Build.SUPPORTED_32_BIT_ABIS[0];
            if ("armeabi-v7a".equals(abi)) return "armv7l";
            if ("x86".equals(abi)) return "i686";
        }
        return "aarch64";
    }

    /** Android 原生 shell（proot 完全失败时的兜底） */
    public String[] buildAndroidShellCommand() {
        return new String[] { "/system/bin/sh", "-i" };
    }

    public String[] buildAndroidEnv() {
        List<String> envList = new ArrayList<>();
        envList.add("PATH=" + binDir.getAbsolutePath() + ":/system/bin:/system/xbin:/vendor/bin");
        envList.add("HOME=" + context.getFilesDir().getAbsolutePath());
        envList.add("TERM=xterm-256color");
        envList.add("TMPDIR=" + tmpDir.getAbsolutePath());
        envList.add("ANDROID_DATA=/data");
        envList.add("ANDROID_ROOT=/system");
        envList.add("EXTERNAL_STORAGE=/sdcard");
        return envList.toArray(new String[0]);
    }

    // ================================================================
    // 旧 assets 安装逻辑（保留兼容，但二进制已走 jniLibs，通常不触发）
    // ================================================================

    public void install() throws IOException {
        binDir.mkdirs();
        libDir.mkdirs();
        libexecDir.mkdirs();
        shareDir.mkdirs();
        includeDir.mkdirs();
        tmpDir.mkdirs();
        String abi = getAbi();
        String assetPath = "proot/" + abi;
        Log.d(TAG, "Installing proot for ABI: " + abi + " from " + assetPath);
        copyAssetDir(assetPath + "/bin", binDir, true);
        copyAssetDir(assetPath + "/lib", libDir, false);
        copyAssetDir(assetPath + "/libexec", libexecDir, true);
        copyAssetDir(assetPath + "/share", shareDir, false);
        copyAssetDir(assetPath + "/include", includeDir, false);
        ensureExecutableRecursive(binDir);
        ensureExecutableRecursive(libexecDir);
        Log.d(TAG, "proot installation complete");
    }

    private String getAbi() {
        if (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            String abi = Build.SUPPORTED_64_BIT_ABIS[0];
            if ("arm64-v8a".equals(abi)) return "arm64-v8a";
        }
        if (Build.SUPPORTED_32_BIT_ABIS != null && Build.SUPPORTED_32_BIT_ABIS.length > 0) {
            String abi = Build.SUPPORTED_32_BIT_ABIS[0];
            if ("armeabi-v7a".equals(abi)) return "armeabi-v7a";
        }
        return "arm64-v8a";
    }

    private void copyAssetDir(String assetDir, File destDir, boolean executable) throws IOException {
        destDir.mkdirs();
        String[] files;
        try {
            files = context.getAssets().list(assetDir);
        } catch (IOException e) {
            Log.w(TAG, "Cannot list asset dir: " + assetDir, e);
            return;
        }
        if (files == null) return;
        for (String name : files) {
            String fullAssetPath = assetDir + "/" + name;
            File destFile = new File(destDir, name);
            String[] children = null;
            try {
                children = context.getAssets().list(fullAssetPath);
            } catch (IOException e) { /* ignore */ }
            if (children != null && children.length > 0) {
                copyAssetDir(fullAssetPath, destFile, executable);
            } else {
                copyAssetFile(fullAssetPath, destFile, executable);
            }
        }
    }

    private void copyAssetFile(String assetPath, File dest, boolean executable) throws IOException {
        if (dest.exists()) dest.delete();
        dest.getParentFile().mkdirs();
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        if (executable) {
            dest.setExecutable(true, false);
        }
        dest.setReadable(true, false);
        // 只对 owner 可写：避免 proot 二进制被其他 App 改写（提权面）
        dest.setWritable(true, false);
    }

    private void ensureExecutableRecursive(File dir) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                ensureExecutableRecursive(f);
            } else {
                f.setExecutable(true, false);
                f.setReadable(true, false);
            }
        }
    }
}
