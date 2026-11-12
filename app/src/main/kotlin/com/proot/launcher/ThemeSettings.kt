package com.proot.launcher

import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * v26：主题设置（Material 3）。
 *
 * 用户可自定义两项：
 *  - 模式：跟随系统 / 浅色 / 深色（SharedPreferences "theme_mode"）
 *  - 主题色：预设色板或自定义 HEX（SharedPreferences "theme_seed"，如 "#3D8BFD"）
 *
 * 主题色经 HSL 调整生成一套近 Material tonal palette 的 ColorScheme，
 * 纯 Kotlin 实现，不依赖 Android 12 动态取色，minSdk 24 全兼容。
 */
object ThemeSettings {

    const val KEY_MODE = "theme_mode"        // "system" | "light" | "dark"
    const val KEY_SEED = "theme_seed"        // "#RRGGBB"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    /** 默认主题色：v24 起的 PCL 蓝 */
    const val DEFAULT_SEED = "#3D8BFD"

    /** 预设色板（名称 to 颜色） */
    val PRESETS: List<Pair<String, Color>> = listOf(
        "PCL 蓝" to Color(0xFF3D8BFD),
        "靛蓝" to Color(0xFF3F51B5),
        "青" to Color(0xFF00ACC1),
        "绿" to Color(0xFF43A047),
        "琥珀" to Color(0xFFFFA000),
        "橙" to Color(0xFFFF7043),
        "玫红" to Color(0xFFEC407A),
        "红" to Color(0xFFE53935),
        "紫" to Color(0xFF8E24AA),
        "棕" to Color(0xFF795548),
        "蓝灰" to Color(0xFF546E7A),
        "石墨" to Color(0xFF37474F),
    )

    fun mode(prefs: SharedPreferences): String =
        prefs.getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM

    fun seedColor(prefs: SharedPreferences): Color {
        val hex = prefs.getString(KEY_SEED, DEFAULT_SEED) ?: DEFAULT_SEED
        return parseHex(hex) ?: Color(0xFF3D8BFD)
    }

    fun saveMode(prefs: SharedPreferences, mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    fun saveSeed(prefs: SharedPreferences, color: Color) {
        prefs.edit().putString(KEY_SEED, toHex(color)).apply()
    }

    /** 解析 "#RRGGBB" / "#RGB"；失败返回 null */
    fun parseHex(s: String): Color? {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6) return null
        val v = h.toLongOrNull(16) ?: return null
        return Color(0xFF000000L or v)
    }

    fun toHex(c: Color): String {
        val r = (c.red * 255 + 0.5f).toInt().coerceIn(0, 255)
        val g = (c.green * 255 + 0.5f).toInt().coerceIn(0, 255)
        val b = (c.blue * 255 + 0.5f).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    // ---------------- HSL ----------------

    private fun Color.toHsl(): FloatArray {
        val r = red
        val g = green
        val b = blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        var h = when (max) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        h /= 6f
        return floatArrayOf(h, s, l)
    }

    private fun hslToColor(hue01: Float, sat: Float, light: Float): Color {
        val h = ((hue01 % 1f) + 1f) % 1f
        val s = sat.coerceIn(0f, 1f)
        val l = light.coerceIn(0f, 1f)
        fun hue2rgb(p: Float, q: Float, t: Float): Float {
            var tt = t
            if (tt < 0f) tt += 1f
            if (tt > 1f) tt -= 1f
            if (tt < 1f / 6f) return p + (q - p) * 6f * tt
            if (tt < 1f / 2f) return q
            if (tt < 2f / 3f) return p + (q - p) * (2f / 3f - tt) * 6f
            return p
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        return Color(
            hue2rgb(p, q, h + 1f / 3f),
            hue2rgb(p, q, h),
            hue2rgb(p, q, h - 1f / 3f),
        )
    }

    // ---------------- seed → ColorScheme ----------------

    /**
     * 由种子色生成一套近 Material 3 的 ColorScheme。
     * 简化 tonal palette：主色取 40/80 色调，容器色取 90/30 色调，
     * 次要/第三色做色相偏移，中性色按浅深模式取带轻微色相的灰。
     */
    fun colorSchemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
        val (h01, s, _) = seed.toHsl()
        val sat = s.coerceIn(0.15f, 1f)
        fun t(h: Float, s2: Float, l: Float) = hslToColor(h, s2, l)

        return if (dark) {
            darkColorScheme(
                primary = t(h01, sat, 0.80f),
                onPrimary = t(h01, sat, 0.12f),
                primaryContainer = t(h01, sat.coerceAtLeast(0.35f), 0.28f),
                onPrimaryContainer = t(h01, sat, 0.90f),
                secondary = t(h01 + 0.06f, sat.coerceAtMost(0.45f), 0.76f),
                onSecondary = t(h01 + 0.06f, 0.4f, 0.14f),
                secondaryContainer = t(h01 + 0.06f, sat.coerceAtMost(0.35f), 0.26f),
                onSecondaryContainer = t(h01 + 0.06f, sat, 0.86f),
                tertiary = t(h01 - 0.08f, sat.coerceAtMost(0.5f), 0.78f),
                onTertiary = t(h01 - 0.08f, 0.4f, 0.14f),
                tertiaryContainer = t(h01 - 0.08f, sat.coerceAtMost(0.4f), 0.26f),
                onTertiaryContainer = t(h01 - 0.08f, sat, 0.88f),
                background = t(h01, 0.10f, 0.08f),
                onBackground = t(h01, 0.05f, 0.92f),
                surface = t(h01, 0.10f, 0.08f),
                onSurface = t(h01, 0.05f, 0.92f),
                surfaceVariant = t(h01, 0.12f, 0.14f),
                onSurfaceVariant = t(h01, 0.05f, 0.65f),
                surfaceContainerHighest = t(h01, 0.12f, 0.16f),
                outline = t(h01, 0.08f, 0.45f),
                outlineVariant = t(h01, 0.10f, 0.22f),
                error = Color(0xFFF2B8B5),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
            )
        } else {
            lightColorScheme(
                primary = t(h01, sat, 0.42f),
                onPrimary = Color.White,
                primaryContainer = t(h01, sat.coerceAtLeast(0.3f), 0.90f),
                onPrimaryContainer = t(h01, sat, 0.18f),
                secondary = t(h01 + 0.06f, sat.coerceAtMost(0.45f), 0.38f),
                onSecondary = Color.White,
                secondaryContainer = t(h01 + 0.06f, sat.coerceAtMost(0.3f), 0.88f),
                onSecondaryContainer = t(h01 + 0.06f, sat, 0.15f),
                tertiary = t(h01 - 0.08f, sat.coerceAtMost(0.5f), 0.40f),
                onTertiary = Color.White,
                tertiaryContainer = t(h01 - 0.08f, sat.coerceAtMost(0.35f), 0.88f),
                onTertiaryContainer = t(h01 - 0.08f, sat, 0.15f),
                background = t(h01, 0.06f, 0.98f),
                onBackground = t(h01, 0.15f, 0.14f),
                surface = t(h01, 0.06f, 0.98f),
                onSurface = t(h01, 0.15f, 0.14f),
                surfaceVariant = t(h01, 0.08f, 0.93f),
                onSurfaceVariant = t(h01, 0.08f, 0.42f),
                surfaceContainerHighest = t(h01, 0.08f, 0.90f),
                outline = t(h01, 0.08f, 0.55f),
                outlineVariant = t(h01, 0.10f, 0.85f),
                error = Color(0xFFB3261E),
                onError = Color.White,
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
            )
        }
    }
}
