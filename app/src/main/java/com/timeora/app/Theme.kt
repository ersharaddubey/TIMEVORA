package com.timevora.app

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * The one colour pattern of timevora: a calm professional blue on slate neutrals, in a light and a dark version.
 * One plain [Palette] (ARGB ints) feeds both the Compose UI ([ThemeState]) and the home-screen widgets
 * ([ThemeStore.resolve]), so the app and its widgets always match. To change the look, change only [AppColors].
 */
data class Palette(
    val dark: Boolean,
    val accent: Int,
    val accent2: Int,
    val canvas: Int,
    val card: Int,
    val ink: Int,
    val hero: Int
) {
    /** Soft tint of the accent, used for chips and highlighted rows. */
    val soft: Int get() = ColorUtils.blendARGB(accent, card, if (dark) 0.84f else 0.88f)
    val soft2: Int get() = ColorUtils.blendARGB(accent2, card, if (dark) 0.84f else 0.88f)
    /** Background of the pastel info panels (weekly summary etc.). */
    val panel: Int get() = if (dark) ColorUtils.blendARGB(accent, card, 0.88f) else 0xFFEFF4FF.toInt()
    /** Secondary text colour that works on [card] and [canvas]. */
    val muted: Int get() = ColorUtils.blendARGB(ink, card, 0.45f)
    /** Readable text colour on top of the accent colour. */
    val onAccent: Int get() = if (ColorUtils.calculateLuminance(accent) > 0.6) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
}

private fun rgb(hex: Long): Int = (0xFF000000L or hex).toInt()

object AppColors {
    val light = Palette(
        dark = false, accent = rgb(0x2563EB), accent2 = rgb(0x0891B2),
        canvas = rgb(0xF3F5F8), card = rgb(0xFFFFFF), ink = rgb(0x1E293B), hero = rgb(0x1E3A5F)
    )
    val dark = Palette(
        dark = true, accent = rgb(0x3B82F6), accent2 = rgb(0x22D3EE),
        canvas = rgb(0x0F172A), card = rgb(0x1E293B), ink = rgb(0xE2E8F0), hero = rgb(0x1E3A5F)
    )
}

/** Only the light / dark switch is stored; there are no theme packs, accent choices or festival themes any more. */
object ThemeStore {
    private const val KEY_DARK = "dark_mode"

    private fun p(c: Context) = c.getSharedPreferences("timevora", Context.MODE_PRIVATE)

    fun darkMode(c: Context): Boolean = p(c).getBoolean(KEY_DARK, false)
    fun setDark(c: Context, on: Boolean) { p(c).edit().putBoolean(KEY_DARK, on).apply() }

    /** The palette that is applied right now. */
    fun resolve(c: Context): Palette = if (darkMode(c)) AppColors.dark else AppColors.light
}

/** Compose-side holder. Reading any of the colour getters below inside a composable makes it recompose when the mode changes. */
object ThemeState {
    var palette by mutableStateOf(AppColors.light)
        private set

    fun reload(c: Context) {
        val next = ThemeStore.resolve(c)
        if (next != palette) palette = next
    }

    fun colorScheme(): ColorScheme {
        val p = palette
        val accent = Color(p.accent)
        return if (p.dark) darkColorScheme(
            primary = accent, onPrimary = Color(p.onAccent), secondary = Color(p.accent2),
            background = Color(p.canvas), onBackground = Color(p.ink),
            surface = Color(p.card), onSurface = Color(p.ink),
            surfaceVariant = Color(p.soft), onSurfaceVariant = Color(p.muted),
            secondaryContainer = Color(p.soft), onSecondaryContainer = accent
        ) else lightColorScheme(
            primary = accent, onPrimary = Color(p.onAccent), secondary = Color(p.accent2),
            background = Color(p.canvas), onBackground = Color(p.ink),
            surface = Color(p.card), onSurface = Color(p.ink),
            surfaceVariant = Color(p.soft), onSurfaceVariant = Color(p.muted),
            secondaryContainer = Color(p.soft), onSecondaryContainer = accent
        )
    }
}
