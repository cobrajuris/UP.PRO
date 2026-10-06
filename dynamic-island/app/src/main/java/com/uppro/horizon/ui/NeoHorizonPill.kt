@file:OptIn(ExperimentalFoundationApi::class)

package com.uppro.horizon.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uppro.horizon.state.IslandRepository
import com.uppro.horizon.state.MediaInfo
import com.uppro.horizon.state.TimerInfo
import kotlinx.coroutines.delay

/** Forma da ilha quando recolhida. */
enum class PillForm {
    /** Pílula horizontal na base da tela (zona do polegar). */
    COMPACT,
    /** Orbe circular encaixado na lateral. */
    ORB,
    /** Aba translúcida mínima na borda (tela cheia / paisagem). */
    TAB,
}

private enum class Shape { COMPACT, ORB, TAB, EXPANDED }

/** Tudo que a ilha pode mostrar num instante. */
data class IslandContent(
    val media: MediaInfo?,
    val timer: TimerInfo?,
    val alert: String?,
) {
    fun isActive(now: Long): Boolean {
        if (alert != null || timer != null) return true
        val m = media ?: return false
        if (m.isPlaying) return true
        val paused = m.pausedAt ?: return true
        return now - paused < IslandRepository.PAUSED_LINGER_MS
    }

    fun visibleMedia(now: Long): MediaInfo? {
        val m = media ?: return null
        if (m.isPlaying) return m
        val paused = m.pausedAt ?: return m
        return if (now - paused < IslandRepository.PAUSED_LINGER_MS) m else null
    }
}

class PillActions(
    val onToggleExpand: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onTimerAddMinute: () -> Unit = {},
    val onTimerPause: () -> Unit = {},
    val onTimerCancel: () -> Unit = {},
    val onDismissAlert: () -> Unit = {},
)

/** Relógio de tela que atualiza 4x por segundo enquanto [active]. */
@Composable
fun rememberNow(active: Boolean = true): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        while (active) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    return now
}

/**
 * Componente "Project Horizon": ilha flutuante de vidro fumê com borda neon viva,
 * brilho ambiente que "respira" e transições por mola entre os estados.
 */
@Composable
fun NeoHorizonPill(
    content: IslandContent,
    expanded: Boolean,
    form: PillForm,
    modifier: Modifier = Modifier,
    tabOnEnd: Boolean = true,
    actions: PillActions = PillActions(),
) {
    val now = rememberNow()
    val media = content.visibleMedia(now)
    val timer = content.timer
    val alert = content.alert
    val accent = when {
        alert != null -> Accent.Alert
        media != null && timer != null -> Accent.Mixed
        timer != null -> Accent.Timer
        media != null -> Accent.Media
        else -> Accent.Idle
    }
    val target = if (expanded) Shape.EXPANDED else when (form) {
        PillForm.COMPACT -> Shape.COMPACT
        PillForm.ORB -> Shape.ORB
        PillForm.TAB -> Shape.TAB
    }
    val view = LocalView.current
    val toggle = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        actions.onToggleExpand()
    }

    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 380f), initialScale = 0.86f))
                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.92f))
                .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) })
        },
        label = "horizon-morph",
    ) { shape ->
        when (shape) {
            Shape.TAB -> EdgeTab(accent = accent, onEnd = tabOnEnd, onClick = toggle)
            Shape.ORB -> GlassShell(accent = accent, corner = 30.dp, onClick = toggle) {
                OrbContent(media = media, timer = timer, alert = alert, now = now, accent = accent)
            }
            Shape.COMPACT -> GlassShell(accent = accent, corner = 26.dp, onClick = toggle) {
                CompactContent(media = media, timer = timer, alert = alert, now = now, accent = accent)
            }
            Shape.EXPANDED -> GlassShell(accent = accent, corner = 30.dp, onClick = null) {
                ExpandedContent(media = media, timer = timer, alert = alert, now = now, accent = accent, actions = actions, onCollapse = toggle)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Casca de vidro: fundo translúcido, reflexo superior, borda gradiente em movimento e brilho.
// ---------------------------------------------------------------------------------------------

@Composable
private fun GlassShell(
    accent: Accent,
    corner: Dp,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "glass")
    val breath by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val flow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "flow",
    )
    val shape = RoundedCornerShape(corner)
    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
    } else Modifier

    Box(
        modifier = Modifier
            .padding(10.dp)
            // Brilho ambiente difuso que pulsa lentamente ("respiro").
            .drawBehind {
                val r = corner.toPx()
                for (i in 1..4) {
                    val grow = i * 2.2.dp.toPx()
                    drawRoundRect(
                        color = accent.primary.copy(alpha = breath * 0.11f / i),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(r + grow, r + grow),
                    )
                }
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Horizon.GlassTop.copy(alpha = 0.82f), Horizon.GlassBottom.copy(alpha = 0.90f)),
                ),
            )
            .drawWithContent {
                // Reflexo de luz no topo, simulando vidro escovado.
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.07f),
                        0.45f to Color.Transparent,
                    ),
                )
                drawContent()
                // Borda neon de 1dp com gradiente que percorre o contorno.
                val stroke = 1.dp.toPx()
                val w = size.width.coerceAtLeast(1f)
                val shift = flow * w * 2f
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(accent.primary, accent.secondary.copy(alpha = 0.55f), accent.primary.copy(alpha = 0.25f), accent.primary),
                        start = Offset(shift - w, 0f),
                        end = Offset(shift, size.height),
                        tileMode = TileMode.Mirror,
                    ),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(corner.toPx() - stroke / 2),
                    style = Stroke(width = stroke),
                )
            }
            .then(clickMod),
    ) {
        content()
    }
}

/** Aba lateral translúcida para tela cheia: quase invisível, mas fácil de tocar. */
@Composable
private fun EdgeTab(accent: Accent, onEnd: Boolean, onClick: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "tab")
    val breath by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tabBreath",
    )
    Box(
        modifier = Modifier
            .size(width = 22.dp, height = 68.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = if (onEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(width = 5.dp, height = 52.dp)
                .graphicsLayer { alpha = breath }
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(accent.primary, accent.secondary))),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Conteúdos
// ---------------------------------------------------------------------------------------------

@Composable
private fun CompactContent(media: MediaInfo?, timer: TimerInfo?, alert: String?, now: Long, accent: Accent) {
    Row(
        modifier = Modifier
            .height(46.dp)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            alert != null -> {
                GlyphBadge(Icons.Rounded.NotificationsActive, accent.primary)
                Text(alert, color = Horizon.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            media != null -> {
                Artwork(media, size = 30.dp, corner = 15.dp, accent = accent)
                Text(
                    text = media.title,
                    color = Horizon.TextPrimary,
                    fontSize = 13.sp,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .widthIn(max = 150.dp)
                        .basicMarquee(iterations = Int.MAX_VALUE),
                )
                AudioWaveform(playing = media.isPlaying, color = accent.primary, bars = 4, height = 16.dp)
                if (timer != null) {
                    Box(Modifier.size(width = 1.dp, height = 18.dp).background(Horizon.Hairline))
                    Text(
                        formatClock(timer.remainingAt(now), ceil = true),
                        color = Horizon.Amber,
                        fontFamily = Horizon.Mono,
                        fontSize = 13.sp,
                    )
                }
            }
            timer != null -> {
                GlyphBadge(Icons.Rounded.Timer, accent.primary)
                Text("Timer", color = Horizon.TextSecondary, fontSize = 13.sp)
                Text(
                    formatClock(timer.remainingAt(now), ceil = true),
                    color = if (timer.isPaused) Horizon.TextSecondary else Horizon.TextPrimary,
                    fontFamily = Horizon.Mono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            else -> {
                GlyphBadge(Icons.Rounded.MusicNote, accent.primary)
                Text("Horizon", color = Horizon.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun OrbContent(media: MediaInfo?, timer: TimerInfo?, alert: String?, now: Long, accent: Accent) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (timer != null) {
            val progress = if (timer.totalMs > 0) timer.remainingAt(now).toFloat() / timer.totalMs else 0f
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .drawBehind {
                        val s = 2.dp.toPx()
                        drawArc(
                            color = Horizon.Hairline,
                            startAngle = 0f, sweepAngle = 360f, useCenter = false,
                            topLeft = Offset(s / 2, s / 2), size = Size(size.width - s, size.height - s),
                            style = Stroke(s),
                        )
                        drawArc(
                            color = Horizon.Amber,
                            startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                            topLeft = Offset(s / 2, s / 2), size = Size(size.width - s, size.height - s),
                            style = Stroke(s, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        )
                    },
            )
        }
        when {
            alert != null -> Icon(Icons.Rounded.NotificationsActive, null, tint = accent.primary, modifier = Modifier.size(24.dp))
            media != null -> Box(contentAlignment = Alignment.Center) {
                Artwork(media, size = 38.dp, corner = 19.dp, accent = accent)
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AudioWaveform(playing = media.isPlaying, color = Color.White, bars = 3, height = 14.dp)
                }
            }
            timer != null -> Text(
                formatMinutes(timer.remainingAt(now)),
                color = Horizon.TextPrimary,
                fontFamily = Horizon.Mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            else -> Icon(Icons.Rounded.MusicNote, null, tint = accent.primary)
        }
    }
}

@Composable
private fun ExpandedContent(
    media: MediaInfo?,
    timer: TimerInfo?,
    alert: String?,
    now: Long,
    accent: Accent,
    actions: PillActions,
    onCollapse: () -> Unit,
) {
    val view = LocalView.current
    fun tap(action: () -> Unit): () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }
    Column(
        modifier = Modifier
            .width(296.dp)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                alert != null -> "ALERTA"
                media != null -> "TOCANDO AGORA · ${media.appLabel.uppercase()}"
                timer != null -> "CRONÔMETRO"
                else -> "HORIZON"
            }
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent.primary))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = accent.primary,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontFamily = Horizon.Mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RoundIcon(Icons.Rounded.ExpandMore, "Recolher", Horizon.TextSecondary, size = 30.dp, onClick = onCollapse)
        }

        if (alert != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlyphBadge(Icons.Rounded.NotificationsActive, accent.primary, size = 40.dp)
                Text(alert, color = Horizon.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ChipButton("OK", accent.primary, tap(actions.onDismissAlert))
            }
        }

        if (media != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Artwork(media, size = 58.dp, corner = 16.dp, accent = accent)
                Column(Modifier.weight(1f)) {
                    Text(
                        media.title,
                        color = Horizon.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    if (media.artist.isNotBlank()) {
                        Text(media.artist, color = Horizon.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                AudioWaveform(playing = media.isPlaying, color = accent.primary, bars = 5, height = 22.dp)
            }
            if (media.durationMs > 0) {
                val pos = media.positionAt(now)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProgressLine(pos.toFloat() / media.durationMs, Accent.Media)
                    Row {
                        Text(formatClock(pos), color = Horizon.TextMuted, fontFamily = Horizon.Mono, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text("-" + formatClock(media.durationMs - pos), color = Horizon.TextMuted, fontFamily = Horizon.Mono, fontSize = 11.sp)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.Rounded.SkipPrevious, "Anterior", Horizon.TextPrimary, size = 42.dp, onClick = tap(actions.onPrevious))
                PlayButton(playing = media.isPlaying, accent = Accent.Media, onClick = tap(actions.onPlayPause))
                RoundIcon(Icons.Rounded.SkipNext, "Próxima", Horizon.TextPrimary, size = 42.dp, onClick = tap(actions.onNext))
            }
        }

        if (media != null && timer != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Horizon.Hairline, Color.Transparent))),
            )
        }

        if (timer != null) {
            val remaining = timer.remainingAt(now)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (media != null) {
                        Text("CRONÔMETRO", color = Horizon.Amber, fontSize = 10.sp, letterSpacing = 1.6.sp, fontFamily = Horizon.Mono)
                    }
                    Text(
                        formatClock(remaining, ceil = true),
                        color = if (timer.isPaused) Horizon.TextSecondary else Horizon.TextPrimary,
                        fontFamily = Horizon.Mono,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChipButton("+1 min", Horizon.Amber, tap(actions.onTimerAddMinute))
                    RoundIcon(
                        if (timer.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (timer.isPaused) "Continuar" else "Pausar",
                        Horizon.Amber,
                        size = 36.dp,
                        bordered = true,
                        onClick = tap(actions.onTimerPause),
                    )
                    RoundIcon(Icons.Rounded.Close, "Cancelar", Horizon.TextSecondary, size = 36.dp, bordered = true, onClick = tap(actions.onTimerCancel))
                }
            }
            ProgressLine(if (timer.totalMs > 0) remaining.toFloat() / timer.totalMs else 0f, Accent.Timer)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Peças
// ---------------------------------------------------------------------------------------------

/** Ondas de espectro minimalistas: cada barra com seu próprio ritmo; congelam ao pausar. */
@Composable
fun AudioWaveform(playing: Boolean, color: Color, bars: Int = 4, height: Dp = 16.dp) {
    val infinite = rememberInfiniteTransition(label = "wave")
    val durations = intArrayOf(420, 560, 380, 640, 500, 450)
    Row(
        modifier = Modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(bars) { i ->
            val level by infinite.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durations[i % durations.size], delayMillis = i * 70, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            val h = if (playing) level else 0.22f
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        val bh = size.height * h
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(0f, (size.height - bh) / 2),
                            size = Size(size.width, bh),
                            cornerRadius = CornerRadius(size.width / 2),
                        )
                    },
            )
        }
    }
}

@Composable
private fun Artwork(media: MediaInfo, size: Dp, corner: Dp, accent: Accent) {
    val shape = RoundedCornerShape(corner)
    val art = media.art
    if (art != null) {
        Image(
            bitmap = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(accent.primary.copy(alpha = 0.35f), accent.secondary.copy(alpha = 0.25f))))
                .border(0.5.dp, accent.primary.copy(alpha = 0.4f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
private fun GlyphBadge(icon: ImageVector, tint: Color, size: Dp = 30.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .border(0.5.dp, tint.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    size: Dp,
    bordered: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (bordered) Modifier.background(Horizon.SurfaceHigh).border(0.5.dp, tint.copy(alpha = 0.4f), CircleShape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.58f))
    }
}

@Composable
private fun PlayButton(playing: Boolean, accent: Accent, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(accent.primary.copy(alpha = 0.22f), Horizon.SurfaceHigh)))
            .border(1.dp, Brush.linearGradient(listOf(accent.primary, accent.secondary)), CircleShape)
            .clickable(role = Role.Button, onClickLabel = if (playing) "Pausar" else "Tocar", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pausar" else "Tocar",
            tint = accent.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun ChipButton(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(0.5.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontSize = 12.sp, fontFamily = Horizon.Mono)
    }
}

@Composable
private fun ProgressLine(fraction: Float, accent: Accent) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(Horizon.Hairline)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(accent.secondary, accent.primary), endX = size.width * f.coerceAtLeast(0.01f)),
                    size = Size(size.width * f, size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            },
    )
}

fun formatClock(ms: Long, ceil: Boolean = false): String {
    val totalSec = if (ceil) (ms + 999) / 1000 else ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatMinutes(ms: Long): String {
    val sec = (ms + 999) / 1000
    return if (sec >= 60) "${(sec + 59) / 60}m" else "${sec}s"
}
