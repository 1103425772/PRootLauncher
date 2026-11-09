# PRoot Launcher

在 Android 上不 root 运行 Linux 容器（Debian / Alpine / 任意 rootfs）的启动器。

- 内置 proot，通过 proot 绑定 rootfs 进容器
- X11：用户自装 [Termux:X11](https://github.com/termux/termux-x11)（未装时 App 跳浏览器 releases 页），
  App 用 `app_process` 启动 X server。v26 起采用新版官方方式：
  `CLASSPATH=<termux-x11 APK> app_process / --nice-name=termux-x11 com.termux.x11.CmdEntryPoint :0`，
  旧版 termux-x11 自动回退内置 loader.apk（修复 Android 12+ 上旧 loader 的 exit code 134 崩溃）
- 内置 pulseaudio 17.0（Termux 构建），匿名 TCP 4713 给容器出声
- Java AudioTrack 桥接（v27 重做，修复 Android 16 无声 / Android 11 正常）：
  先起 `AudioService`（mediaPlayback 前台服务）→ 建 MediaSession → **先拿音频焦点再起播** →
  焦点丢失/闪避回调 → 部分写入补齐 → 播放头可播性探测（被系统静默丢弃时如实报错，不再装作在跑）
- 音频降级链（v27）：A = pipe-sink(AudioTrack) → B = **aaudio-sink(AAudio)** → C = sles-sink(仅 API<26) → D = null-sink；
  Android 16 上 OpenSL ES 初始化报 `Failed to initialize OpenSL ES: error 12`，v26 的 B 档必失败并落到
  只有 null-sink 的"假成功"，现改为 AAudio（模块已随包附带，无需重新编译 native 组件）
- GPU：纯 llvmpipe 软渲染（v24 起彻底移除 VirGL）
- 神秘入口（长按容器项）：开启「运行带有容器检测的二进制」后使用
  [Tiny Container](https://github.com/Cateners/tiny_container) 的 proot 启动命令
  （命令模板来自 app 配置文件 `files/config/launch.conf`）；跨架构模拟（QEMU 8 架构，
  qemu-user 来自 [tur-packages](https://github.com/termux-user-repository/tur)）
- UI：Material 3（v26 起弃用 Miuix），用户可自定义主题色（12 种预设 + 任意 HEX）与
  浅色/深色/跟随系统模式

## 构建（Gradle）

依赖：JDK 17、Android SDK（platform 36 / build-tools 34.0.0）。

```bash
# 构建 hi（targetSdk 34，Android 12+）
gradle :app:assembleHiRelease

# 构建 lo（targetSdk 28，Android 9-11）
gradle :app:assembleLoRelease

# 或一次性两个
gradle :app:assembleHiRelease :app:assembleLoRelease
```

产物在 `app/build/outputs/apk/hi/release/` 与 `app/build/outputs/apk/lo/release/`。

签名：`keystore/debug.keystore`（storepass/keypass = `android`），v14 起固定项目内
keystore，保证覆盖升级不丢 rootfs。证书 SHA-256：
`84e5e6eafea116099ccfe41af44a03590fd43ee4022fcde9bb552eb8ea0cc0ce`。

## 目录

```
app/src/
  main/java/com/proot/launcher/
    MainActivity.kt       主页 / rootfs 管理 / 神秘入口 / 外观设置（Compose Material 3）
    ThemeSettings.kt      主题：模式 + 种子色 → ColorScheme（纯 Kotlin，minSdk 24）
    TerminalActivity.java 内置终端（默认 proot 命令 或 真实模拟 Tiny Container 命令）
    WebTerminalActivity.java  ttyd web 终端
    ProotInstaller.java   proot 命令拼装 + env 注入（PULSE_SERVER / DISPLAY / QEMU / real-sim）
    SimConfig.java        app 配置文件 files/config/launch.conf（真实模拟开关 + 跨架构 + 命令模板）
    ContainerService.java 前台服务：容器会话后台保活（防冻结断网）
    X11Support.java       X11 双模式启动（CmdEntryPoint / 内置 loader 回退）+ XKB + 版本检测
    AudioSupport.java     pulseaudio 启停 + TCP 4713（多策略 + 落盘日志）
    AudioBridge.java      AudioTrack ↔ FIFO PCM 桥（v27：焦点先行 + MediaSession + 可播性探测）
    AudioService.java     mediaPlayback 前台服务（Android 14+ 后台出声的合法身份）
    GpuSupport.java       llvmpipe / Wayland env（无 virgl）
    QemuSupport.java      qemu-user 跨架构引导
    TtydService.java      ttyd web 终端服务
  main/assets/audio/<abi>/   pulseaudio 17.0 运行时（从 Termux deb 解包）
  main/assets/x11/loader.apk 旧版 loader stub（旧 termux-x11 回退用）
  main/assets/proot/         proot 静态二进制（.so 命名，安装后 exec）
  main/assets/termux-exec/   termux-exec（容器内命令包装）
  main/jniLibs/<abi>/        libproot / libproot_loader / libpulseaudio
  main/res/                  资源（XML drawable / strings / styles）
  hi/AndroidManifest.xml     高版本清单（targetSdk 34）
  lo/AndroidManifest.xml     低版本清单（targetSdk 28）
```

## 神秘入口 / 真实模拟 / 跨架构

长按 rootfs 列表项 → 高级设置：

- **运行带有容器检测的二进制**：proot 改用 Tiny Container 的启动命令
  （伪造 /proc、/sys、selinux 环境，绕过后台检测），命令模板存于
  `files/config/launch.conf`。
- **跨架构模拟（QEMU）**：可选 x86_64 / i386 / arm / aarch64 / m68k / ppc / ppc64 / riscv64，
  启动时自动引导对应 `qemu-user-*` 模拟器（tur-packages 包），并注入
  `QEMU_LD_PREFIX` 等环境。已自动排除与本机架构相同的选项。

## 容器内使用

```bash
# 音频（env 已由 App 注入，无需手动 export）
pactl list short sinks
speaker-test -c2 -t wav -l1

# X11
DISPLAY=:0 xterm

# GPU（默认 llvmpipe 软渲染）
LIBGL_ALWAYS_SOFTWARE=1 glxgears
```

## 致谢 / 引用

本项目打包/借鉴了以下开源项目的二进制与思路：

- **[Termux](https://github.com/termux/termux-app)** — `assets/audio/` 内的
  pulseaudio 17.0、libpulse 从 Termux APT 源（`https://packages.termux.dev/apt/`）
  对应 deb 包解出，遵循 Termux 的 GPLv2/LGPL 许可。
- **[proot](https://github.com/proot/proot)** — 用户空间 chroot 工具（静态编译，GPLv2）。
- **[pulseaudio](https://gitlab.freedesktop.org/pulseaudio/pulseaudio)** — 声音服务器，LGPLv2.1。
- **[Termux:X11](https://github.com/termux/termux-x11)** — X server 实现与 loader 启动方式参考。
- **[tur-packages](https://github.com/termux-user-repository/tur)** — qemu-user 跨架构模拟包。
- **[Tiny Container](https://github.com/Cateners/tiny_container)** — 「运行带有容器检测的二进制」
  模式的 proot 启动命令参考。
- **[PCL](https://github.com/Meloong-Git/PCL)** — 蓝色 UI 风格参考。
- **[material3](https://developer.android.com/jetpack/androidx/releases/compose-material3)** —
  UI 组件（AndroidX），Apache 2.0。
- **[XZ for Java](https://tukaani.org/xz/java.html)** — tar.xz 解压，公有领域。

容器内 rootfs 由用户自行下载（Debian / Alpine / Ubuntu 等），本仓库不附带 rootfs 二进制。

## 许可

代码部分 MIT；`assets/audio/`、`assets/proot/` 内第三方二进制遵循其上游许可。
