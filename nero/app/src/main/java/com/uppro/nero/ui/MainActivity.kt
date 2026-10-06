package com.uppro.nero.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.uppro.nero.assistant.AssistantActivity
import com.uppro.nero.assistant.Command
import com.uppro.nero.assistant.CommandParser
import com.uppro.nero.data.NeroState
import com.uppro.nero.data.Note
import com.uppro.nero.data.Reminder
import com.uppro.nero.data.Store
import com.uppro.nero.media.NeroListenerService
import com.uppro.nero.notebook.NotebookService
import com.uppro.nero.notebook.Outbox
import com.uppro.nero.overlay.OverlayService
import com.uppro.nero.reminders.CalendarSync
import com.uppro.nero.reminders.ReminderScheduler
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var tab by mutableStateOf("home")
    private var pickRequest by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.init(this)
        readIntent(intent)
        setContent {
            NeroTheme {
                App(tab = tab, onTab = { tab = it }, pickRequest = pickRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TAB)?.let { tab = it }
        if (intent?.getBooleanExtra(EXTRA_PICK, false) == true) pickRequest = System.currentTimeMillis()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_PICK = "pick"
    }
}

private data class TabItem(val id: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("home", "Início", Icons.Rounded.Home),
    TabItem("notes", "Notas", Icons.Rounded.StickyNote2),
    TabItem("reminders", "Lembretes", Icons.Rounded.CalendarMonth),
    TabItem("notebook", "Notebook", Icons.Rounded.LaptopMac),
    TabItem("settings", "Ajustes", Icons.Rounded.Settings),
)

@Composable
private fun App(tab: String, onTab: (String) -> Unit, pickRequest: Long) {
    BackHandler(enabled = tab != "home") { onTab("home") }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF15131F), Nero.Bg, Nero.Bg)),
            ),
    ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "tab",
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) { t ->
            when (t) {
                "notes" -> NotesScreen()
                "reminders" -> RemindersScreen()
                "notebook" -> NotebookScreen(pickRequest)
                "settings" -> SettingsScreen()
                else -> HomeScreen(onTab)
            }
        }
        TabBar(tab, onTab, Modifier.align(Alignment.BottomCenter))
    }
}

/** Barra de abas em vidro, com a aba atual em pílula (como na referência). */
@Composable
private fun TabBar(current: String, onTab: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(31.dp))
            .background(Color(0xF21C1C24))
            .glassHighlight(31.dp)
            .border(0.6.dp, Nero.Line, RoundedCornerShape(31.dp))
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TABS.forEach { t ->
            val selected = t.id == current
            Row(
                Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = t.label) { onTab(t.id) }
                    .padding(horizontal = if (selected) 14.dp else 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(t.icon, t.label, tint = if (selected) Color.White else Nero.Ink2, modifier = Modifier.size(22.dp))
                if (selected) Text(t.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

private val ScreenPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp)

// ---------------------------------------------------------------------------------------------
// Início
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(onTab: (String) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var pendingStart by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val canOverlay = remember(tick) { Settings.canDrawOverlays(context) }
    val hasListener = remember(tick) { NeroListenerService.isEnabled(context) }
    val hasMic = remember(tick) { granted(context, Manifest.permission.RECORD_AUDIO) }
    val hasCalendar = remember(tick) { CalendarSync.canWrite(context) }
    val hasNotif = remember(tick) { Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS) }
    val running by NeroState.overlayRunning.collectAsState()

    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    LaunchedEffect(canOverlay, pendingStart) {
        if (pendingStart && canOverlay) {
            pendingStart = false
            Store.updateSettings { it.copy(overlayEnabled = true) }
            OverlayService.start(context)
        }
    }

    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF3A2E8C), Color(0xFFB0466E), Color(0xFF1B7A8C)))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Nero", color = Nero.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                    Text(if (running) "Ativo na lateral da tela" else "Desligado", color = if (running) Nero.Green else Nero.Ink2, fontSize = 14.sp)
                }
            }
        }
        item {
            GlassCard {
                Text(
                    "Uma pílula de vidro pequena na lateral da tela. Ela cresce quando há música, timer ou caminho do Maps, e um toque abre o painel com tudo.",
                    color = Nero.Ink2, fontSize = 15.sp, lineHeight = 21.sp,
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = when {
                        running -> "Desligar a pílula"
                        canOverlay -> "Ligar a pílula do Nero"
                        else -> "Permitir e ligar a pílula"
                    },
                    dark = running,
                ) {
                    when {
                        running -> {
                            Store.updateSettings { it.copy(overlayEnabled = false) }
                            OverlayService.stop(context)
                        }
                        canOverlay -> {
                            Store.updateSettings { it.copy(overlayEnabled = true) }
                            OverlayService.start(context)
                        }
                        else -> {
                            pendingStart = true
                            context.openUri(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Falar com o Nero", dark = true) {
                    context.startActivity(Intent(context, AssistantActivity::class.java))
                }
            }
        }
        item { SectionLabel("Permissões") }
        item {
            GlassCard {
                PermissionRow(Icons.Rounded.Layers, "Sobrepor a outros apps", "Necessária para a pílula aparecer em qualquer tela.", canOverlay) {
                    context.openUri(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}")
                }
                PermissionRow(Icons.Rounded.MusicNote, "Acesso a notificações", "Mostra a música tocando e o caminho do Maps/Waze.", hasListener) {
                    context.openUri(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, null)
                }
                if (!hasListener) {
                    Text(
                        "Opção bloqueada? No Android 13+, apps instalados por APK precisam de liberação: Informações do app → ⋮ → “Permitir configurações restritas”.",
                        color = Nero.Ink3, fontSize = 12.5.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(start = 54.dp, bottom = 6.dp),
                    )
                    Text(
                        "Abrir informações do app",
                        color = Nero.Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(start = 54.dp, bottom = 8.dp)
                            .clickable { context.openUri(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}") },
                    )
                }
                PermissionRow(Icons.Rounded.Mic, "Microfone", "Para falar com o Nero.", hasMic) {
                    single.launch(Manifest.permission.RECORD_AUDIO)
                }
                PermissionRow(Icons.Rounded.CalendarMonth, "Agenda", "Salva seus lembretes no Google Agenda e mostra o próximo compromisso.", hasCalendar) {
                    multi.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    PermissionRow(Icons.Rounded.Notifications, "Notificações", "Avisos de lembretes e do fim do timer.", hasNotif) {
                        single.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        item { SectionLabel("Como usar") }
        item {
            GlassCard {
                Tip("Toque na pílula para abrir o painel. Segure para falar com o Nero: “lembre que amanhã às 15h tenho dentista”.")
                Tip("Arraste a pílula para cima, para baixo ou para o outro lado da tela. Ela encaixa sozinha na lateral.")
                Tip("Em qualquer app: Compartilhar → “Enviar ao notebook”.")
                Tip("Em vídeo ou jogo em tela cheia a pílula vira um tracinho na borda. Toque nele para ela voltar.")
            }
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !ok, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(icon, tint = Nero.Blue)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Nero.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Nero.Ink2, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (ok) Icon(Icons.Rounded.CheckCircle, "Concedida", tint = Nero.Green, modifier = Modifier.size(24.dp))
        else Text("Permitir", color = Nero.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tip(text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(Nero.Blue),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, color = Nero.Ink2, fontSize = 14.5.sp, lineHeight = 20.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Notas
// ---------------------------------------------------------------------------------------------

@Composable
private fun NotesScreen() {
    val notes by Store.notes.collectAsState()
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }

    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.imePadding()) {
        item { ScreenTitle("Notas", "Também dá para falar: “Nero, anota comprar pão”.") }
        item {
            GlassField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Nova nota…",
                imeAction = ImeAction.Done,
                onSubmit = {
                    if (draft.isNotBlank()) {
                        Store.addNote(draft); draft = ""
                    }
                },
            ) {
                RoundAction(Icons.Rounded.Add, "Adicionar", enabled = draft.isNotBlank()) {
                    Store.addNote(draft); draft = ""
                }
            }
        }
        if (notes.isEmpty()) {
            item { EmptyState("Nenhuma nota ainda", "Escreva acima ou peça ao Nero.") }
        }
        items(notes, key = { it.id }) { note ->
            if (editing == note.id) {
                GlassCard {
                    GlassField(
                        value = editText,
                        onValueChange = { editText = it },
                        placeholder = "Nota",
                        singleLine = false,
                        imeAction = ImeAction.Default,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("Salvar", Nero.Green) {
                            if (editText.isNotBlank()) Store.updateNote(note.id, editText)
                            editing = null
                        }
                        SmallButton("Cancelar") { editing = null }
                        Spacer(Modifier.weight(1f))
                        SmallButton("Apagar", Nero.Red) {
                            Store.deleteNote(note.id)
                            editing = null
                        }
                    }
                }
            } else {
                NoteCard(note) {
                    editing = note.id
                    editText = note.text
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    GlassCard(onClick = onClick) {
        Text(note.text, color = Nero.Ink, fontSize = 16.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(dateLabel(note.createdAt), color = Nero.Ink3, fontSize = 12.5.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Lembretes
// ---------------------------------------------------------------------------------------------

@Composable
private fun RemindersScreen() {
    val context = LocalContext.current
    val reminders by Store.reminders.collectAsState()
    var draft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val calendarOk = remember { CalendarSync.canWrite(context) }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        when (val cmd = CommandParser.parse(if (text.startsWith("lembr", true)) text else "lembre $text")) {
            is Command.AddReminder -> {
                val millis = cmd.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val r = ReminderScheduler.create(context, cmd.title, millis)
                message = "Criado: ${r.title} · ${dateLabel(r.at)}" + if (r.calendarEventId != null) " · Google Agenda ✓" else ""
                draft = ""
            }
            is Command.ReminderNeedsTime -> message = "Diga também quando. Ex.: “${cmd.title.lowercase()} amanhã às 9h”."
            else -> message = "Não entendi. Ex.: “amanhã às 15h dentista”."
        }
    }

    val now = System.currentTimeMillis()
    val upcoming = reminders.filter { !it.done && it.at > now }
    val past = reminders.filter { it.done || it.at <= now }.sortedByDescending { it.at }

    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.imePadding()) {
        item {
            ScreenTitle(
                "Lembretes",
                if (calendarOk) "Salvos aqui e no Google Agenda." else "Permita a Agenda no Início para salvar também no Google Agenda.",
            )
        }
        item {
            GlassField(
                value = draft,
                onValueChange = { draft = it; message = null },
                placeholder = "Ex.: amanhã às 15h dentista",
                imeAction = ImeAction.Done,
                onSubmit = { submit() },
            ) {
                RoundAction(Icons.Rounded.ArrowUpward, "Criar", enabled = draft.isNotBlank()) { submit() }
            }
            message?.let {
                Text(it, color = Nero.Ink2, fontSize = 13.5.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
            }
        }
        if (upcoming.isEmpty()) item { EmptyState("Nada agendado", "Crie acima ou fale com o Nero.") }
        if (upcoming.isNotEmpty()) item { SectionLabel("Próximos") }
        items(upcoming, key = { it.id }) { r -> ReminderRow(r) }
        if (past.isNotEmpty()) item { SectionLabel("Concluídos") }
        items(past, key = { "p" + it.id }) { r -> ReminderRow(r) }
    }
}

@Composable
private fun ReminderRow(r: Reminder) {
    val context = LocalContext.current
    val done = r.done || r.at <= System.currentTimeMillis()
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Rounded.CalendarMonth, tint = if (done) Nero.Ink3 else Color.White, bg = if (done) Nero.SurfaceHigh else Nero.Red.copy(alpha = 0.85f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, color = if (done) Nero.Ink2 else Nero.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    dateLabel(r.at) + if (r.calendarEventId != null) " · Google Agenda" else "",
                    color = Nero.Ink3, fontSize = 13.sp,
                )
            }
            RoundAction(Icons.Rounded.Close, "Apagar") { ReminderScheduler.delete(context, r) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Notebook
// ---------------------------------------------------------------------------------------------

@Composable
private fun NotebookScreen(pickRequest: Long) {
    val context = LocalContext.current
    val status by NeroState.notebook.collectAsState()
    val outbox by Outbox.items.collectAsState()
    val received by Outbox.received.collectAsState()
    val settings by Store.settings.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) NotebookService.queue(context, uris)
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { Outbox.load(context) }
    LaunchedEffect(pickRequest) {
        if (pickRequest > 0 && System.currentTimeMillis() - pickRequest < 5_000) picker.launch(arrayOf("*/*"))
    }

    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Notebook", "Troque arquivos com o notebook pelo Wi‑Fi, sem cabo e sem instalar nada.") }
        item {
            GlassCard {
                ToggleRow(
                    "Modo notebook",
                    if (status.running) "Ligado. Desligue quando terminar para poupar bateria." else "Liga um servidor só na sua rede Wi‑Fi.",
                    status.running,
                ) { on ->
                    if (on) {
                        if (Build.VERSION.SDK_INT < 29 && !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                        NotebookService.start(context)
                    } else {
                        NotebookService.stop(context)
                    }
                }
                if (status.running) {
                    Spacer(Modifier.height(14.dp))
                    val url = status.url
                    if (url != null) {
                        Text("No navegador do notebook, abra:", color = Nero.Ink2, fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text(url.removePrefix("http://"), color = Nero.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            RoundAction(Icons.Rounded.ContentCopy, "Copiar") { context.copy(url) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PIN", color = Nero.Ink2, fontSize = 14.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                settings.notebookPin.toCharArray().joinToString(" "),
                                color = Nero.Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            )
                        }
                    } else {
                        Text(status.error ?: "Conecte o celular ao mesmo Wi‑Fi do notebook.", color = Nero.Amber, fontSize = 14.sp)
                    }
                } else if (status.error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(status.error ?: "", color = Nero.Amber, fontSize = 14.sp)
                }
            }
        }
        item {
            PrimaryButton("Escolher arquivos para enviar") { picker.launch(arrayOf("*/*")) }
        }
        item { SectionLabel("Esperando o notebook baixar") }
        if (outbox.isEmpty()) {
            item { EmptyState("Fila vazia", "Use o botão acima ou Compartilhar → “Enviar ao notebook” em qualquer app.") }
        }
        items(outbox, key = { it.id }) { item ->
            FileRow(item.name, "${formatSize(item.size)} · ${dateLabel(item.addedAt)}", Icons.Rounded.InsertDriveFile) {
                Outbox.remove(item.id)
            }
        }
        if (received.isNotEmpty()) {
            item { SectionLabel("Recebidos (Downloads/Nero)") }
            items(received, key = { "r" + it.at + it.name }) { item ->
                FileRow(item.name, "${formatSize(item.size)} · ${dateLabel(item.at)}", Icons.Rounded.Download, onRemove = null)
            }
        }
    }
}

@Composable
private fun FileRow(name: String, info: String, icon: ImageVector, onRemove: (() -> Unit)?) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, tint = Nero.Blue)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Nero.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(info, color = Nero.Ink3, fontSize = 13.sp)
            }
            if (onRemove != null) RoundAction(Icons.Rounded.Close, "Remover", onClick = onRemove)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ajustes
// ---------------------------------------------------------------------------------------------

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val s by Store.settings.collectAsState()
    var albumName by remember { mutableStateOf(s.albumName) }
    var albumLink by remember { mutableStateOf(s.albumLink) }

    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.imePadding()) {
        item { ScreenTitle("Ajustes") }
        item { SectionLabel("Álbum favorito") }
        item {
            GlassCard {
                Text(
                    "No Spotify ou YouTube Music, abra o álbum → Compartilhar → Copiar link, e cole aqui. O botão de álbum da barra abre ele direto.",
                    color = Nero.Ink2, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                GlassField(albumName, { albumName = it }, "Nome (ex.: Currents)")
                Spacer(Modifier.height(8.dp))
                GlassField(albumLink, { albumLink = it }, "Link do álbum")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Salvar", Nero.Green) {
                        Store.updateSettings { it.copy(albumName = albumName.trim(), albumLink = albumLink.trim()) }
                        Toast.makeText(context, "Álbum salvo", Toast.LENGTH_SHORT).show()
                    }
                    if (albumLink.isNotBlank()) {
                        SmallButton("Abrir agora") {
                            val link = albumLink.trim()
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (link.contains("://")) link else "https://$link")))
                            }.onFailure { Toast.makeText(context, "Link inválido", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }
        }
        item { SectionLabel("Pílula") }
        item {
            GlassCard {
                Text("Lado da tela", color = Nero.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Esquerda", if (!s.pillRight) Nero.Green else Nero.Ink) { Store.updateSettings { it.copy(pillRight = false) } }
                    SmallButton("Direita", if (s.pillRight) Nero.Green else Nero.Ink) { Store.updateSettings { it.copy(pillRight = true) } }
                }
                Spacer(Modifier.height(14.dp))
                ToggleRow("Encolher em tela cheia", "Em vídeos e jogos vira um tracinho fino.", s.minimizeInFullscreen) { v ->
                    Store.updateSettings { it.copy(minimizeInFullscreen = v) }
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow("Encolher na horizontal", "Com o celular deitado, também vira o tracinho.", s.minimizeInLandscape) { v ->
                    Store.updateSettings { it.copy(minimizeInLandscape = v) }
                }
            }
        }
        item { SectionLabel("Lembretes") }
        item {
            GlassCard {
                ToggleRow("Salvar no Google Agenda", "Cada lembrete vira um evento na sua agenda.", s.syncCalendar) { v ->
                    Store.updateSettings { it.copy(syncCalendar = v) }
                }
            }
        }
        item { SectionLabel("Notebook") }
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PIN do notebook", color = Nero.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(s.notebookPin, color = Nero.Gold, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    }
                    SmallButton("Gerar novo") {
                        Store.updateSettings { it.copy(notebookPin = (1000..9999).random().toString()) }
                    }
                }
            }
        }
        item {
            Text(
                "Nero 1.0 · tudo fica salvo no seu celular.",
                color = Nero.Ink3, fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun RoundAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) Nero.Ink else Nero.Ink3, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(0.8.dp, Nero.Line, RoundedCornerShape(22.dp))
            .padding(vertical = 26.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Nero.Ink2, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Nero.Ink3, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openUri(action: String, data: String?) {
    val intent = Intent(action).apply { if (data != null) this.data = Uri.parse(data) }
    runCatching { startActivity(intent) }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
}

private fun Context.copy(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Nero", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
}

private fun dateLabel(epoch: Long): String {
    val today = java.time.LocalDate.now()
    val date = java.time.Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
    val time = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(epoch))
    return when (date) {
        today -> "Hoje, $time"
        today.plusDays(1) -> "Amanhã, $time"
        today.minusDays(1) -> "Ontem, $time"
        else -> SimpleDateFormat("EEE, d MMM · HH:mm", Locale("pt", "BR")).format(Date(epoch)).replaceFirstChar { it.uppercase() }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}
