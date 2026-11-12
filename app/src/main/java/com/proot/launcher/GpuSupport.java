package com.proot.launcher;

import android.content.Context;

/**
 * GPU 环境注入：纯 mesa llvmpipe 软渲染（稳定、零依赖）。
 *
 * 一律走软渲染，不注入任何硬件 GPU 透传相关环境变量。
 *
 * Wayland：容器内注入 WAYLAND_DISPLAY=wayland-0 + XDG_RUNTIME_DIR=/run/user/0。
 * 用户在 rootfs 里跑 weston（x11 后端）即可得到 Wayland 窗口。
 */
public class GpuSupport {
    private GpuSupport() {}

    /** 注入容器的 GPU/Wayland 环境变量前缀（纯 llvmpipe 软渲染）。 */
    public static String guestEnvPrefix() {
        return "WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/0 "
             + "LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ";
    }
}
