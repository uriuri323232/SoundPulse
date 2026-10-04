package com.focusaudio

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** A named accent trio. Backgrounds and surfaces are derived from the chosen skin. */
data class Palette(val id: String, val label: String, val c1: Long, val c2: Long, val c3: Long)

val PALETTES: List<Palette> = listOf(
    Palette("aurora", "Aurora", 0xFF2DD4BF, 0xFF6366F1, 0xFF22D3EE),
    Palette("forest", "Forest", 0xFF1ED760, 0xFF34D399, 0xFF10B981),
    Palette("nocturne", "Nocturne", 0xFF38BDF8, 0xFF818CF8, 0xFF22D3EE),
    Palette("ocean", "Ocean", 0xFF2DD4BF, 0xFF3B82F6, 0xFF06B6D4),
    Palette("sunset", "Sunset", 0xFFFB7185, 0xFFF59E0B, 0xFFFB923C),
    Palette("crimson", "Crimson", 0xFFFB3B5C, 0xFFF97316, 0xFFE11D48),
    Palette("grape", "Grape", 0xFFA78BFA, 0xFFF0A6FF, 0xFF8B5CF6),
    Palette("rose", "Rose", 0xFFF472B6, 0xFFA78BFA, 0xFFFB7185),
    Palette("ice", "Ice", 0xFF7DD3FC, 0xFFC4B5FD, 0xFFA5F3FC),
    Palette("gold", "Gold", 0xFFF5C451, 0xFFFB923C, 0xFFFDE68A),
    Palette("magma", "Magma", 0xFFFF7A18, 0xFFFF3D7F, 0xFFFFB020),
    Palette("graphite", "Graphite", 0xFFCBD5E1, 0xFF64748B, 0xFF94A3B8)
)

fun paletteOf(id: String): Palette = PALETTES.firstOrNull { it.id == id } ?: PALETTES[0]

enum class Skin(val id: String, val label: String) {
    SOUNDPULSE("soundpulse", "SoundPulse"),
    SPOTIFY("spotify", "Spotify"),
    YTM("ytm", "YouTube Music");
}

fun skinOf(id: String): Skin = Skin.values().firstOrNull { it.id == id } ?: Skin.SOUNDPULSE

private fun mix(a: Long, bR: Int, bG: Int, bB: Int, t: Float): Color {
    val ar = (a shr 16 and 0xFF).toInt(); val ag = (a shr 8 and 0xFF).toInt(); val ab = (a and 0xFF).toInt()
    return Color(
        ((ar + (bR - ar) * t).toInt()) / 255f,
        ((ag + (bG - ag) * t).toInt()) / 255f,
        ((ab + (bB - ab) * t).toInt()) / 255f
    )
}

/** Base background color for a skin, tinted slightly by the accent. */
fun skinBg(skin: Skin, p: Palette): Color = when (skin) {
    Skin.SPOTIFY -> mix(p.c1, 10, 14, 12, 0.90f)   // near-black with a hint of accent
    Skin.YTM -> Color(0xFF0F0F0F)
    Skin.SOUNDPULSE -> mix(p.c1, 11, 15, 18, 0.92f)
}

/** Top color of the ambient gradient behind the player for a skin. */
fun skinTop(skin: Skin, p: Palette): Color = when (skin) {
    Skin.SPOTIFY -> mix(p.c1, 18, 34, 26, 0.55f)
    Skin.YTM -> mix(p.c3, 40, 20, 30, 0.55f)
    Skin.SOUNDPULSE -> mix(p.c2, 14, 20, 26, 0.70f)
}

fun schemeFor(p: Palette, skin: Skin): ColorScheme {
    val c1 = Color(p.c1); val c2 = Color(p.c2); val c3 = Color(p.c3)
    val bg = skinBg(skin, p)
    val surface = mix(p.c1, 255, 255, 255, 0.965f).copy(alpha = 1f).let {
        // slightly lifted surface over bg
        Color(
            (bg.red + 0.06f).coerceAtMost(1f),
            (bg.green + 0.065f).coerceAtMost(1f),
            (bg.blue + 0.07f).coerceAtMost(1f)
        )
    }
    val surfaceHi = Color(
        (bg.red + 0.11f).coerceAtMost(1f),
        (bg.green + 0.115f).coerceAtMost(1f),
        (bg.blue + 0.12f).coerceAtMost(1f)
    )
    return darkColorScheme(
        primary = c1, onPrimary = Color(0xFF07130F),
        secondary = c2, onSecondary = Color(0xFF0B0A14),
        tertiary = c3, onTertiary = Color(0xFF14110A),
        background = bg, onBackground = Color(0xFFECF1F0),
        surface = bg, onSurface = Color(0xFFECF1F0),
        surfaceVariant = surface, onSurfaceVariant = Color(0xFF9FB0AF),
        surfaceContainer = surfaceHi, surfaceContainerHigh = surfaceHi,
        outline = Color(0xFF5B6B6A), outlineVariant = Color(0x33FFFFFF),
        primaryContainer = c1.copy(alpha = .22f), onPrimaryContainer = Color(0xFFECF1F0)
    )
}
