package com.uppro.nero.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uppro.nero.data.AlertInfo
import com.uppro.nero.data.ChargingInfo
import com.uppro.nero.data.MediaInfo
import com.uppro.nero.data.NavInfo
import com.uppro.nero.data.TimerInfo
import com.uppro.nero.data.TransferDirection
import com.uppro.nero.data.TransferInfo
import com.uppro.nero.data.Upcoming
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** O que o cartão acima da barra está mostrando. */
sealed interface Card {
    val kind: String

    data class Music(val m: MediaInfo) : Card { override val kind = "music" }
    data class Nav(val n: NavInfo) : Card { override val kind = "nav" }
    data class Timer(val t: TimerInfo) : Card { override val kind = "timer" }
    data class Soon(val u: Upcoming) : Card { override val kind = "soon" }
    data class Charging(val c: ChargingInfo) : Card { override val kind = "charging" }
    data class Transfer(val t: TransferInfo, val url: String?) : Card { override val kind = "transfer" }
    data class Alert(val a: AlertInfo) : Card { override val kind = "alert" }

    /** Cor (ARGB) que tinge o vidro. */
    fun tint(): Int = when (this) {
        is Music -> m.artColor ?: Nero.TINT_MUSIC
        is Nav -> Nero.TINT_NAV
        is Timer -> Nero.TINT_TIMER
        is Soon -> Nero.TINT_SOON
        is Charging -> Nero.TINT_CHARGING
        is Transfer -> Nero.TINT_TRANSFER
        is Alert -> Nero.TINT_ALERT
    }
}

class CardActions(
    val playPause: () -> Unit = {},
    val next: () -> Unit = {},
    val open: () -> Unit = {},
    val dismiss: () -> Unit = {},
)

class DockActions(
    val nero: () -> Unit = {},
    val notes: () -> Unit = {},
    val agenda: () -> Unit = {},
    val notebook: () -> Unit = {},
    val alarm: () -> Unit = {},
    val album: () -> Unit = {},
    val openPanel: () -> Unit = {},
)

/** Reflexo de luz no topo, como no vidro do iOS. */
fun Modifier.glassHighlight(corner: Dp): Modifier = this.drawBehind {
    drawRoundRect(
        brush = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.55f to Color.Transparent),
        cornerRadius = CornerRadius(corner.toPx()),
    )
}

@Composable
private fun haptic(): (Int) -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(it) }
}

// ---------------------------------------------------------------------------------------------
// Barra de atalhos
// ---------------------------------------------------------------------------------------------

@Composable
fun DockContent(actions: DockActions, modifier: Modifier = Modifier) {
    val buzz = haptic()
    val density = LocalDensity.current
    val threshold = with(density) { 22.dp.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(30.dp))
            .glassHighlight(30.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged < -threshold) {
                            buzz(HapticFeedbackConstants.CONTEXT_CLICK)
                            actions.openPanel()
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(role = Role.Button, onClickLabel = "Falar com o Nero") {
                    buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                    actions.nero()
                }
                .padding(start = 12.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text("Nero", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        DockIcon(Icons.Rounded.StickyNote2, "Notas") { buzz(HapticFeedbackConstants.VIRTUAL_KEY); actions.notes() }
        DockIcon(Icons.Rounded.CalendarMonth, "Lembretes") { buzz(HapticFeedbackConstants.VIRTUAL_KEY); actions.agenda() }
        DockIcon(Icons.Rounded.LaptopMac, "Notebook") { buzz(HapticFeedbackConstants.VIRTUAL_KEY); actions.notebook() }
        DockIcon(Icons.Rounded.Alarm, "Alarme") { buzz(HapticFeedbackConstants.VIRTUAL_KEY); actions.alarm() }
        DockIcon(Icons.Rounded.Album, "Álbum favorito") { buzz(HapticFeedbackConstants.VIRTUAL_KEY); actions.album() }
    }
}

@Composable
private fun DockIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(23.dp))
    }
}

/** Tracinho de vidro usado em tela cheia. */
@Composable
fun LineContent(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Mostrar o Nero",
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.38f))
                .border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Cartão de atividade
// ---------------------------------------------------------------------------------------------

@Composable
fun CardContent(card: Card?, now: Long, actions: CardActions) {
    val buzz = haptic()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(26.dp))
            .glassHighlight(26.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) {
                buzz(HapticFeedbackConstants.CONTEXT_CLICK)
                actions.open()
            },
    ) {
        AnimatedContent(
            targetState = card,
            contentKey = { it?.kind },
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = 420f)) { it / 3 })
                    .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 4 })
            },
            label = "card",
            modifier = Modifier.fillMaxSize(),
        ) { c ->
            when (c) {
                null -> Spacer(Modifier.fillMaxSize())
                is Card.Music -> MusicCard(c.m, now, actions, buzz)
                is Card.Nav -> NavCard(c.n)
                is Card.Timer -> TimerCard(c.t, now, actions, buzz)
                is Card.Soon -> SoonCard(c.u, now)
                is Card.Charging -> ChargingCard(c.c)
                is Card.Transfer -> TransferCard(c.t, c.url)
                is Card.Alert -> AlertCard(c.a, actions, buzz)
            }
        }
    }
}

@Composable
private fun CardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Titles(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, tint: Color = Color.White, bg: Color = Color.White.copy(alpha = 0.16f)) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, ring: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (ring) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.75f), CircleShape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun BottomProgress(fraction: Float, color: Color = Color.White) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(start = 18.dp, end = 18.dp, bottom = 6.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
                .drawBehind {
                    drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
                },
        )
    }
}

@Composable
private fun MusicCard(m: MediaInfo, now: Long, actions: CardActions, buzz: (Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // A capa desfocada tinge o vidro (Android 12+); antes disso fica só a cor média da capa.
        val art = m.art
        if (art != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(28.dp)
                    .alpha(0.55f),
            )
        }
        CardRow {
            RoundButton(if (m.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.isPlaying) "Pausar" else "Tocar") {
                buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                actions.playPause()
            }
            Titles(m.title, m.artist, Modifier.weight(1f))
            Waveform(playing = m.isPlaying)
            RoundButton(Icons.Rounded.SkipNext, "Próxima", ring = true) {
                buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                actions.next()
            }
        }
        if (m.durationMs > 0) BottomProgress(m.positionAt(now).toFloat() / m.durationMs)
    }
}

@Composable
private fun NavCard(n: NavInfo) {
    CardRow {
        val icon = n.icon
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon, null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(30.dp))
            } else {
                Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Titles(n.title, n.text, Modifier.weight(1f))
        Text(n.appLabel, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun TimerCard(t: TimerInfo, now: Long, actions: CardActions, buzz: (Int) -> Unit) {
    val remaining = t.remainingAt(now)
    Box(Modifier.fillMaxSize()) {
        CardRow {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val fraction = if (t.totalMs > 0) remaining.toFloat() / t.totalMs else 0f
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val s = 2.5.dp.toPx()
                            drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s))
                            drawArc(Nero.Amber, -90f, 360f * fraction, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
                        },
                )
                Icon(Icons.Rounded.Timer, null, tint = Nero.Amber, modifier = Modifier.size(20.dp))
            }
            Titles("Timer", t.label, Modifier.weight(1f))
            Text(
                formatClock(remaining),
                color = Nero.Amber,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
            RoundButton(Icons.Rounded.Close, "Cancelar timer") {
                buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                actions.dismiss()
            }
        }
    }
}

@Composable
private fun SoonCard(u: Upcoming, now: Long) {
    val minutes = ((u.at - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    val whenText = if (minutes <= 0) "agora" else "em $minutes min"
    CardRow {
        Badge(Icons.Rounded.CalendarMonth, bg = Nero.Red)
        Titles("${u.title} $whenText", listOf(timeOf(u.at), u.where).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
    }
}

@Composable
private fun ChargingCard(c: ChargingInfo) {
    CardRow {
        Badge(Icons.Rounded.Bolt, tint = Nero.Green)
        Titles("Carregando", if (c.level >= 100) "Bateria cheia" else "Bateria em ${c.level}%", Modifier.weight(1f))
        Text("${c.level}%", color = Nero.Green, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier
                .size(width = 40.dp, height = 20.dp)
                .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(c.level.coerceIn(0, 100) / 100f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Nero.Green),
            )
        }
    }
}

@Composable
private fun TransferCard(t: TransferInfo, url: String?) {
    val fraction = if (t.totalBytes > 0) t.doneBytes.toFloat() / t.totalBytes else 0f
    val (title, sub) = when {
        t.direction == TransferDirection.READY -> "Pronto para o notebook" to (url?.let { "${t.name} · abra $it" } ?: t.name)
        t.direction == TransferDirection.TO_NOTEBOOK && t.finished -> "Chegou no notebook" to t.name
        t.direction == TransferDirection.TO_NOTEBOOK -> "Enviando ${(fraction * 100).toInt()}%" to t.name
        t.finished -> "Recebido do notebook" to "${t.name} · Downloads/Nero"
        else -> "Recebendo ${(fraction * 100).toInt()}%" to t.name
    }
    Box(Modifier.fillMaxSize()) {
        CardRow {
            Badge(
                when {
                    t.finished -> Icons.Rounded.CheckCircle
                    t.direction == TransferDirection.FROM_NOTEBOOK -> Icons.Rounded.Download
                    else -> Icons.Rounded.LaptopMac
                },
            )
            Titles(title, sub, Modifier.weight(1f))
        }
        if (t.direction != TransferDirection.READY) BottomProgress(if (t.finished) 1f else fraction)
    }
}

@Composable
private fun AlertCard(a: AlertInfo, actions: CardActions, buzz: (Int) -> Unit) {
    val pulse = rememberInfiniteTransition(label = "alert")
    val scale by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    CardRow {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { Badge(Icons.Rounded.NotificationsActive, bg = Nero.Red) }
        Titles(a.title, a.subtitle, Modifier.weight(1f))
        RoundButton(Icons.Rounded.Close, "Dispensar", ring = true) {
            buzz(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.dismiss()
        }
    }
}

/** Barrinhas de áudio que dançam enquanto a música toca. */
@Composable
fun Waveform(playing: Boolean, bars: Int = 4, color: Color = Color.White) {
    val infinite = rememberInfiniteTransition(label = "wave")
    val durations = intArrayOf(460, 620, 400, 540, 500)
    Row(Modifier.height(20.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(bars) { i ->
            val level by infinite.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(durations[i % durations.size], delayMillis = i * 60, easing = LinearEasing), RepeatMode.Reverse),
                label = "b$i",
            )
            val h = if (playing) level else 0.22f
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        val bh = size.height * h
                        drawRoundRect(color, Offset(0f, (size.height - bh) / 2), Size(size.width, bh), CornerRadius(size.width / 2))
                    },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Painel (deslizar para cima)
// ---------------------------------------------------------------------------------------------

data class PanelData(
    val lastNote: String?,
    val nextReminder: String?,
    val notebook: String,
    val nextAlarm: String?,
    val album: String,
)

class PanelActions(
    val assistant: () -> Unit = {},
    val notes: () -> Unit = {},
    val reminders: () -> Unit = {},
    val notebook: () -> Unit = {},
    val alarm: () -> Unit = {},
    val album: () -> Unit = {},
    val close: () -> Unit = {},
)

@Composable
fun PanelContent(data: PanelData, actions: PanelActions, activity: (@Composable () -> Unit)? = null) {
    val buzz = haptic()
    val density = LocalDensity.current
    val threshold = with(density) { 40.dp.toPx() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(34.dp))
            .glassHighlight(34.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > threshold) actions.close() },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 38.dp, height = 5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f)),
        )
        // O que está acontecendo agora (música, timer...) com os controles completos.
        activity?.invoke()
        fun go(a: () -> Unit): () -> Unit = {
            buzz(HapticFeedbackConstants.VIRTUAL_KEY)
            a()
        }
        WideTile(onClick = go(actions.assistant)) {
            Orb(44.dp)
            Column(Modifier.weight(1f)) {
                Text("Fale com o Nero", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("“Lembre que amanhã às 15h tenho dentista”", color = Color.White.copy(alpha = 0.66f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Icons.Rounded.StickyNote2, "Notas", data.lastNote ?: "Nenhuma nota ainda", Modifier.weight(1f), go(actions.notes))
            Tile(Icons.Rounded.CalendarMonth, "Lembretes", data.nextReminder ?: "Nada agendado", Modifier.weight(1f), go(actions.reminders))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Icons.Rounded.LaptopMac, "Notebook", data.notebook, Modifier.weight(1f), go(actions.notebook))
            Tile(Icons.Rounded.Alarm, data.nextAlarm ?: "Alarme", if (data.nextAlarm != null) "Próximo alarme" else "Nenhum alarme ativo", Modifier.weight(1f), go(actions.alarm), big = data.nextAlarm != null)
        }
        WideTile(onClick = go(actions.album)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFB36B), Color(0xFFE05A7A), Color(0xFF5B4BD6)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Álbum favorito", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(data.album, color = Color.White.copy(alpha = 0.66f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.PlayArrow, "Tocar", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun WideTile(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
private fun Tile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit, big: Boolean = false) {
    Column(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(
            title,
            color = Color.White,
            fontSize = if (big) 24.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(subtitle, color = Color.White.copy(alpha = 0.66f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
    }
}

/** Esfera colorida do assistente (gira devagar; [level] amplia com a voz). */
@Composable
fun Orb(size: Dp, level: Float = 0f) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val angle by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "a")
    val breathe by infinite.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val scale = breathe + level.coerceIn(0f, 1f) * 0.18f
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                rotationZ = angle
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Brush.sweepGradient(listOf(Nero.Blue, Nero.Violet, Nero.Pink, Nero.Gold, Nero.Blue))),
    )
}

fun formatClock(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun timeOf(epoch: Long): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(epoch))

/** Relógio de tela (elapsedRealtime) que atualiza 4x por segundo. */
@Composable
fun rememberNow(): Long {
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(250)
        }
    }
    return now
}


// ---------------------------------------------------------------------------------------------
// Pílula lateral em miniatura
// ---------------------------------------------------------------------------------------------

enum class PillMode { IDLE, ACTIVE, MIN }

/** Laranja do contorno de progresso, como na referência. */
private val PillOrange = Color(0xFFFF8A4C)
private val PillTrack = Color(0xFF6E6E78)

/**
 * A pílula do Nero no canto de baixo. Recolhida é uma pílula escura pequena;
 * com algo acontecendo cresce, mostra o número grande no meio e o contorno
 * vira uma barra de progresso (cor = o que falta, cinza = o que já passou).
 * Em tela cheia vira um tracinho.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPill(
    card: Card?,
    minimized: Boolean,
    onRight: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val buzz = haptic()
    val now = rememberNow()
    val mode = when {
        minimized -> PillMode.MIN
        card == null -> PillMode.IDLE
        else -> PillMode.ACTIVE
    }
    Box(
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClickLabel = "Abrir o Nero",
            onLongClickLabel = "Falar com o Nero",
            onLongClick = {
                buzz(HapticFeedbackConstants.LONG_PRESS)
                onLongPress()
            },
            onClick = {
                buzz(HapticFeedbackConstants.CONTEXT_CLICK)
                onTap()
            },
        ),
    ) {
        AnimatedContent(
            targetState = mode to card?.kind,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 70)) togetherWith fadeOut(tween(90)))
                    .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.68f, stiffness = 380f) })
            },
            contentAlignment = if (onRight) Alignment.BottomEnd else Alignment.BottomStart,
            label = "pill",
        ) { (m, _) ->
            when (m) {
                PillMode.MIN -> Box(
                    Modifier.size(width = 52.dp, height = 18.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .padding(bottom = 4.dp)
                            .size(width = 38.dp, height = 5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.45f)),
                    )
                }
                PillMode.IDLE -> Box(
                    Modifier
                        .size(width = 64.dp, height = 34.dp)
                        .clip(CircleShape)
                        .glassHighlight(17.dp)
                        .pillOutline(1f, PillTrack.copy(alpha = 0.55f), PillTrack.copy(alpha = 0.55f), stroke = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, "Nero", tint = Color.White.copy(alpha = 0.95f), modifier = Modifier.size(17.dp))
                }
                PillMode.ACTIVE -> {
                    val c = card
                    if (c != null) {
                        val look = pillLook(c, now)
                        Row(
                            Modifier
                                .height(54.dp)
                                .widthIn(min = 118.dp)
                                .clip(CircleShape)
                                .pillOutline(look.fraction, look.accent, PillTrack)
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            look.leading?.invoke()
                            if (look.value.isNotEmpty()) Text(
                                look.value,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                            if (look.label.isNotEmpty()) {
                                Text(
                                    look.label,
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Como cada atividade aparece na pílula: ícone opcional, número grande, legenda e progresso. */
private class PillLook(
    val value: String,
    val label: String,
    val fraction: Float,
    val accent: Color,
    val leading: (@Composable () -> Unit)? = null,
)

private fun pillLook(card: Card, now: Long): PillLook = when (card) {
    is Card.Timer -> {
        val remaining = card.t.remainingAt(now)
        val secs = (remaining + 999) / 1000
        val fraction = if (card.t.totalMs > 0) remaining.toFloat() / card.t.totalMs else 0f
        if (secs < 60) PillLook("$secs", if (secs == 1L) "segundo" else "segundos", fraction, PillOrange)
        else PillLook(formatClock(remaining), "min", fraction, PillOrange)
    }
    is Card.Music -> {
        val m = card.m
        val accent = m.artColor?.let { brighten(Color(it)) } ?: Nero.Pink
        val fraction = if (m.durationMs > 0) 1f - m.positionAt(now).toFloat() / m.durationMs else 1f
        PillLook(
            value = "",
            label = "",
            fraction = fraction,
            accent = accent,
            leading = {
                val art = m.art
                if (art != null) {
                    Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.size(30.dp).clip(CircleShape))
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Waveform(playing = m.isPlaying, bars = 4, color = accent)
            },
        )
    }
    is Card.Nav -> PillLook(
        value = card.n.title,
        label = "",
        fraction = 1f,
        accent = Nero.Blue,
        leading = {
            val icon = card.n.icon
            if (icon != null) {
                Image(icon, null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(24.dp))
            } else {
                Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        },
    )
    is Card.Soon -> {
        val minutes = ((card.u.at - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        PillLook(
            value = if (minutes <= 0) "agora" else "$minutes",
            label = if (minutes <= 0) "" else "min",
            fraction = (minutes / 15f).coerceIn(0.02f, 1f),
            accent = Nero.Red,
            leading = { Icon(Icons.Rounded.CalendarMonth, null, tint = Nero.Red, modifier = Modifier.size(20.dp)) },
        )
    }
    is Card.Charging -> PillLook(
        value = "${card.c.level}",
        label = "%",
        fraction = card.c.level / 100f,
        accent = Nero.Green,
        leading = { Icon(Icons.Rounded.Bolt, null, tint = Nero.Green, modifier = Modifier.size(20.dp)) },
    )
    is Card.Transfer -> {
        val t = card.t
        val pct = if (t.totalBytes > 0) (t.doneBytes * 100 / t.totalBytes).toInt() else 0
        PillLook(
            value = when {
                t.direction == TransferDirection.READY -> "pronto"
                t.finished -> "ok"
                else -> "$pct"
            },
            label = if (!t.finished && t.direction != TransferDirection.READY) "%" else "",
            fraction = if (t.finished || t.direction == TransferDirection.READY) 1f else pct / 100f,
            accent = Nero.Blue,
            leading = {
                Icon(
                    if (t.direction == TransferDirection.FROM_NOTEBOOK) Icons.Rounded.Download else Icons.Rounded.LaptopMac,
                    null, tint = Color.White, modifier = Modifier.size(20.dp),
                )
            },
        )
    }
    is Card.Alert -> PillLook(
        value = card.a.title,
        label = "",
        fraction = 1f,
        accent = Nero.Red,
        leading = { Icon(Icons.Rounded.NotificationsActive, null, tint = Nero.Red, modifier = Modifier.size(20.dp)) },
    )
}

private fun brighten(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv,
    )
    hsv[1] = hsv[1].coerceIn(0.45f, 0.9f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Contorno da pílula usado como barra de progresso: começa no meio da base,
 * sobe pela direita, passa pelo topo e desce pela esquerda. [fraction] é a parte
 * colorida (o que falta); o resto fica cinza.
 */
fun Modifier.pillOutline(fraction: Float, accent: Color, track: Color, stroke: Dp = 3.5.dp): Modifier = this.drawBehind {
    val sw = stroke.toPx()
    val inset = sw / 2 + 1.5.dp.toPx()
    val w = size.width
    val h = size.height
    val r = (h / 2 - inset).coerceAtLeast(1f)
    val left = inset
    val right = w - inset
    val top = inset
    val bottom = h - inset
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w / 2, bottom)
        lineTo(right - r, bottom)
        arcTo(androidx.compose.ui.geometry.Rect(right - 2 * r, top, right, bottom), 90f, -180f, false)
        lineTo(left + r, top)
        arcTo(androidx.compose.ui.geometry.Rect(left, top, left + 2 * r, bottom), 270f, -180f, false)
        lineTo(w / 2, bottom)
    }
    val f = fraction.coerceIn(0f, 1f)
    val measure = androidx.compose.ui.graphics.PathMeasure()
    measure.setPath(path, false)
    val total = measure.length
    val strokeStyle = Stroke(width = sw, cap = StrokeCap.Round)
    // Parte cinza: o que já passou (do fim do progresso até o ponto de partida).
    if (f < 1f) {
        val rest = androidx.compose.ui.graphics.Path()
        measure.getSegment(total * f, total, rest, true)
        drawPath(rest, track, style = strokeStyle)
    }
    if (f > 0f) {
        val done = androidx.compose.ui.graphics.Path()
        measure.getSegment(0f, total * f, done, true)
        drawPath(done, accent, style = strokeStyle)
    }
}

/** A atividade atual dentro do painel, com fundo na cor do contexto. */
@Composable
fun PanelActivity(card: Card, actions: CardActions) {
    val now = rememberNow()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(card.tint()).copy(alpha = 0.55f)),
    ) {
        CardContent(card, now, actions)
    }
}
