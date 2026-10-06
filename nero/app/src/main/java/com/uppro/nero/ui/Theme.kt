package com.uppro.nero.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta do Nero: vidro fosco escuro estilo iOS, acentos suaves por contexto. */
object Nero {
    val Bg = Color(0xFF0B0B0F)
    val Surface = Color(0xFF17171D)
    val SurfaceHigh = Color(0xFF22222A)
    val Glass = Color(0x33FFFFFF)
    val GlassSoft = Color(0x1AFFFFFF)
    val Line = Color(0x1FFFFFFF)
    val Ink = Color(0xFFF5F5F7)
    val Ink2 = Color(0xFFA0A0AB)
    val Ink3 = Color(0xFF6B6B76)

    val Blue = Color(0xFF8FB4FF)
    val Violet = Color(0xFFC58CFF)
    val Pink = Color(0xFFFF8FB1)
    val Gold = Color(0xFFFFD28F)
    val Green = Color(0xFF5FE3A1)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5B4D)

    /** Cores base (ARGB) para tingir o vidro de cada atividade. */
    val TINT_DOCK = 0xFF1C1C24.toInt()
    val TINT_NAV = 0xFF1F5FD0.toInt()
    val TINT_TIMER = 0xFF8A5410.toInt()
    val TINT_SOON = 0xFFA8332B.toInt()
    val TINT_CHARGING = 0xFF12784A.toInt()
    val TINT_TRANSFER = 0xFF3A55C8.toInt()
    val TINT_ALERT = 0xFFB0402F.toInt()
    val TINT_MUSIC = 0xFF5A3E52.toInt()
}

@Composable
fun NeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Nero.Ink,
            onPrimary = Color(0xFF0B0B0F),
            secondary = Nero.Blue,
            background = Nero.Bg,
            surface = Nero.Surface,
            onBackground = Nero.Ink,
            onSurface = Nero.Ink,
            surfaceVariant = Nero.SurfaceHigh,
            onSurfaceVariant = Nero.Ink2,
            outline = Nero.Line,
        ),
        content = content,
    )
}
