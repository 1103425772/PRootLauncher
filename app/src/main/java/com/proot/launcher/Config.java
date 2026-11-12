package com.proot.launcher;

/**
 * 全局常量收敛（原散落在多个 Activity/Service 中的魔数）。
 */
public final class Config {
    private Config() {}

    /** ttyd Web 终端监听端口（127.0.0.1） */
    public static final int TTYD_PORT = 7681;

    /** 内置终端最多保留的行数，超出后截断 */
    public static final int MAX_TERMINAL_LINES = 5000;

    /** rootfs 压缩包最小合法体积：小于该值视为下载错误页 */
    public static final long MIN_ARCHIVE_SIZE = 1024L * 1024L;

    /** 包管理器安装 ttyd 的超时（秒） */
    public static final int PKG_INSTALL_TIMEOUT_SEC = 600;

    /** ttyd 静态二进制下载超时（秒） */
    public static final int DOWNLOAD_TIMEOUT_SEC = 180;

    /** 快速探测命令（which ttyd 等）超时（秒） */
    public static final int QUICK_TIMEOUT_SEC = 30;

    /** rootfs 目录大小缓存有效期（毫秒），避免列表刷新每次都全量递归 */
    public static final long DIR_SIZE_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Web 终端模式：轮询等待 ttyd 绑定端口的超时（毫秒）。
     *  低端设备 ttyd 启动可能需要 3-5 秒，这里放宽到 15 秒。 */
    public static final long TTYD_START_TIMEOUT_MS = 15 * 1000L;

    // ============ X11（termux-x11 显示端 app；v26 起不再内置 APK） ============

    /** termux-x11 包名（用户手动安装的显示端） */
    public static final String TERMUX_X11_PKG = "com.termux.x11";

    /** X11 服务端显示号（DISPLAY） */
    public static final String X11_DISPLAY = ":0";

    /** SharedPreferences 键：X11 模式是否开启（影响 rootfs 会话注入 DISPLAY） */
    public static final String KEY_X11_ENABLED = "x11_enabled";
}
