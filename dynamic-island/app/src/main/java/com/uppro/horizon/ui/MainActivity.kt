package com.uppro.horizon.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.uppro.horizon.media.MediaListenerService
import com.uppro.horizon.overlay.IslandOverlayService
import com.uppro.horizon.state.Dock
import com.uppro.horizon.state.IslandRepository
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        IslandRepository.loadSettings(this)
        setContent {
            HorizonTheme {
                HomeScreen()
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    var pendingStart by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val canOverlay = remember(resumeTick) { Settings.canDrawOverlays(context) }
    val hasListener = remember(resumeTick) { MediaListenerService.isEnabled(context) }
    val hasNotif = remember(resumeTick) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val running by IslandRepository.overlayRunning.collectAsState()
    val settings by IslandRepository.settings.collectAsState()
    val media by IslandRepository.media.collectAsState()
    val timer by IslandRepository.timer.collectAsState()
    val alert by IslandRepository.alert.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    // Volta das configurações com a permissão concedida: liga a ilha automaticamente.
    LaunchedEffect(canOverlay, pendingStart) {
        if (pendingStart && canOverlay) {
            pendingStart = false
            IslandOverlayService.start(context)
        }
    }
    // Sem o serviço rodando, a própria tela encerra o cronômetro da prévia.
    LaunchedEffect(running, timer) {
        while (!running) {
            val t = IslandRepository.timer.value ?: break
            if (!t.isPaused && t.remainingAt(SystemClock.elapsedRealtime()) <= 0L) {
                IslandRepository.finishTimer()
                break
            }
            delay(250)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0E16), Horizon.Bg))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column {
                Text("PROJECT HORIZON", color = Horizon.Cyan, fontFamily = Horizon.Mono, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text("Ilha dinâmica", color = Horizon.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Flutua na base ou nas laterais, longe da câmera. Só aparece com música ou cronômetro ativos e vira uma aba discreta em vídeos e jogos.",
                    color = Horizon.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }

            PreviewStage(
                content = IslandContent(media, timer, alert),
                dock = settings.dock,
            )

            // Botão principal
            val ready = canOverlay
            PrimaryButton(
                text = when {
                    running -> "Desligar ilha"
                    ready -> "Ligar ilha sobre os apps"
                    else -> "Permitir e ligar a ilha"
                },
                active = running,
            ) {
                when {
                    running -> IslandOverlayService.stop(context)
                    ready -> IslandOverlayService.start(context)
                    else -> {
                        pendingStart = true
                        context.openOverlaySettings()
                    }
                }
            }

            SectionTitle("Permissões")
            Card {
                PermissionRow(
                    icon = Icons.Rounded.Layers,
                    title = "Sobrepor a outros apps",
                    subtitle = "Necessária para a ilha flutuar em qualquer tela.",
                    granted = canOverlay,
                    onClick = { context.openOverlaySettings() },
                )
                Divider()
                PermissionRow(
                    icon = Icons.Rounded.MusicNote,
                    title = "Acesso a notificações",
                    subtitle = "Lê a música que está tocando (Spotify, YouTube Music, etc.) e permite controlar.",
                    granted = hasListener,
                    onClick = { context.openListenerSettings() },
                )
                if (!hasListener) {
                    Text(
                        "Opção bloqueada? No Android 13+ apps instalados por APK precisam de liberação: abra as informações do app, toque em ⋮ e em \"Permitir configurações restritas\".",
                        color = Horizon.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(start = 52.dp, end = 4.dp, bottom = 4.dp),
                    )
                    Text(
                        "Abrir informações do app",
                        color = Horizon.Cyan,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(start = 52.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { context.openAppDetails() }
                            .padding(vertical = 6.dp),
                    )
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    Divider()
                    PermissionRow(
                        icon = Icons.Rounded.Notifications,
                        title = "Notificações",
                        subtitle = "Mostra o aviso fixo de que a ilha está ligada (opcional).",
                        granted = hasNotif,
                        onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
            }

            SectionTitle("Testar")
            Card {
                Text("Cronômetro", color = Horizon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 10, 25).forEach { min ->
                        Chip("$min min", Horizon.Amber, Modifier.weight(1f)) { IslandRepository.startTimer(min) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Música de demonstração", color = Horizon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Para ver a ilha sem abrir um player.", color = Horizon.TextSecondary, fontSize = 12.sp)
                    }
                    val demoOn = media?.packageName == "demo"
                    HorizonSwitch(demoOn) { on -> if (on) IslandRepository.startDemo() else IslandRepository.stopDemo() }
                }
            }

            SectionTitle("Comportamento")
            Card {
                Text("Posição", color = Horizon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Você também pode arrastar a ilha até a lateral.", color = Horizon.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Dock.LEFT to "Esquerda", Dock.BOTTOM to "Base", Dock.RIGHT to "Direita").forEach { (d, label) ->
                        Chip(label, Horizon.Cyan, Modifier.weight(1f), selected = settings.dock == d) {
                            IslandRepository.updateSettings(context) { it.copy(dock = d) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    "Minimizar em tela cheia",
                    "Vídeos e jogos: vira uma aba translúcida na borda.",
                    settings.minimizeInFullscreen,
                ) { v -> IslandRepository.updateSettings(context) { it.copy(minimizeInFullscreen = v) } }
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    "Minimizar na horizontal",
                    "Com o celular deitado, a ilha encolhe para o canto.",
                    settings.minimizeInLandscape,
                ) { v -> IslandRepository.updateSettings(context) { it.copy(minimizeInLandscape = v) } }
            }

            Text(
                "Toque na ilha para expandir · arraste para mover · toque fora para recolher.",
                color = Horizon.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}

/** Prévia interativa: uma "tela" escura com a ilha no mesmo lugar onde ela vai aparecer. */
@Composable
private fun PreviewStage(content: IslandContent, dock: Dock) {
    var expanded by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = normal, 1 = tela cheia
    val now = rememberNow()
    val active = content.isActive(now)
    LaunchedEffect(active) { if (!active) expanded = false }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mode == 1) 220.dp else 300.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1B2140), Color(0xFF0E1222), Color(0xFF2A1240)),
                    ),
                )
                .border(1.dp, Horizon.Hairline, RoundedCornerShape(28.dp)),
        ) {
            // Furo de câmera (para mostrar que a ilha fica longe dele).
            if (mode == 0) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Black),
                )
            }
            if (!active) {
                Text(
                    "Nada ativo — a ilha fica escondida.\nInicie um cronômetro ou a música demo.",
                    color = Horizon.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                val form = when {
                    mode == 1 -> PillForm.TAB
                    dock == Dock.BOTTOM -> PillForm.COMPACT
                    else -> PillForm.ORB
                }
                val align = when {
                    expanded && mode == 1 -> if (dock == Dock.LEFT) Alignment.CenterStart else Alignment.CenterEnd
                    expanded -> Alignment.BottomCenter
                    form == PillForm.COMPACT -> Alignment.BottomCenter
                    dock == Dock.LEFT -> Alignment.CenterStart
                    else -> Alignment.CenterEnd
                }
                NeoHorizonPill(
                    content = content,
                    expanded = expanded,
                    form = form,
                    tabOnEnd = dock != Dock.LEFT,
                    modifier = Modifier.align(align).padding(4.dp),
                    actions = PillActions(
                        onToggleExpand = { expanded = !expanded },
                        onPlayPause = IslandRepository::playPause,
                        onNext = IslandRepository::next,
                        onPrevious = IslandRepository::previous,
                        onTimerAddMinute = IslandRepository::addMinute,
                        onTimerPause = IslandRepository::toggleTimerPause,
                        onTimerCancel = IslandRepository::cancelTimer,
                        onDismissAlert = {
                            IslandRepository.dismissAlert()
                            expanded = false
                        },
                    ),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Tela normal", Horizon.Cyan, Modifier.weight(1f), selected = mode == 0) { mode = 0 }
            Chip("Vídeo / jogo", Horizon.Cyan, Modifier.weight(1f), selected = mode == 1) { mode = 1; expanded = false }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Horizon.TextMuted, fontFamily = Horizon.Mono, fontSize = 11.sp, letterSpacing = 1.6.sp)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Horizon.Surface.copy(alpha = 0.85f))
            .border(1.dp, Horizon.Hairline.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(Horizon.Hairline.copy(alpha = 0.6f)),
    )
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !granted, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Horizon.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Horizon.Cyan, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Horizon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Horizon.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Icon(Icons.Rounded.CheckCircle, "Concedida", tint = Horizon.Mint, modifier = Modifier.size(24.dp))
        } else {
            Text("Conceder", color = Horizon.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, active: Boolean, onClick: () -> Unit) {
    val brush = if (active) {
        Brush.horizontalGradient(listOf(Horizon.SurfaceHigh, Horizon.SurfaceHigh))
    } else {
        Brush.horizontalGradient(listOf(Horizon.Cyan, Horizon.Electric, Horizon.Violet))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(brush)
            .border(1.dp, if (active) Horizon.Hairline else Color.White.copy(alpha = 0.2f), RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) Horizon.TextPrimary else Color(0xFF05060A),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Chip(label: String, tint: Color, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) tint.copy(alpha = 0.16f) else Horizon.SurfaceHigh)
            .border(1.dp, if (selected) tint.copy(alpha = 0.7f) else Horizon.Hairline, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) tint else Horizon.TextPrimary, fontSize = 13.sp, fontFamily = Horizon.Mono)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Horizon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Horizon.TextSecondary, fontSize = 12.sp)
        }
        HorizonSwitch(checked, onChange)
    }
}

@Composable
private fun HorizonSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF05060A),
            checkedTrackColor = Horizon.Cyan,
            uncheckedThumbColor = Horizon.TextSecondary,
            uncheckedTrackColor = Horizon.SurfaceHigh,
            uncheckedBorderColor = Horizon.Hairline,
        ),
    )
}

// ---------------------------------------------------------------------------------------------

private fun Context.openOverlaySettings() {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
    runCatching { startActivity(intent) }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }
}

private fun Context.openListenerSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        .onFailure { openAppDetails() }
}

private fun Context.openAppDetails() {
    runCatching {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
