package com.uppro.horizon.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Paleta "Project Horizon": vidro fumê escuro com acentos neon por contexto. */
object Horizon {
    val Bg = Color(0xFF07080C)
    val GlassTop = Color(0xFF161A24)
    val GlassBottom = Color(0xFF07080C)
    val Surface = Color(0xFF12141C)
    val SurfaceHigh = Color(0xFF1C2030)
    val Hairline = Color(0xFF2A3042)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA0A0AB)
    val TextMuted = Color(0xFF6B6F7E)

    val Cyan = Color(0xFF00F0FF)
    val Violet = Color(0xFF7A5CFF)
    val Electric = Color(0xFF2E6BFF)
    val Amber = Color(0xFFFFB020)
    val Ember = Color(0xFFFF5E3A)
    val Mint = Color(0xFF3DFFB0)

    val Mono = FontFamily.Monospace
}

/** Cor de acento conforme o que a ilha está mostrando. */
@Immutable
data class Accent(val primary: Color, val secondary: Color) {
    companion object {
        val Media = Accent(Horizon.Cyan, Horizon.Violet)
        val Timer = Accent(Horizon.Amber, Horizon.Ember)
        val Mixed = Accent(Horizon.Cyan, Horizon.Amber)
        val Alert = Accent(Horizon.Ember, Horizon.Amber)
        val Idle = Accent(Horizon.Electric, Horizon.Violet)
    }
}

@Composable
fun HorizonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Horizon.Cyan,
            secondary = Horizon.Violet,
            tertiary = Horizon.Amber,
            background = Horizon.Bg,
            surface = Horizon.Surface,
            onPrimary = Color.Black,
            onBackground = Horizon.TextPrimary,
            onSurface = Horizon.TextPrimary,
        ),
        content = content,
    )
}
