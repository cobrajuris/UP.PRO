package com.uppro.nero.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.uppro.nero.data.NeroState
import com.uppro.nero.data.Store
import com.uppro.nero.data.TimerInfo
import com.uppro.nero.reminders.ReminderScheduler
import com.uppro.nero.ui.Nero
import com.uppro.nero.ui.NeroTheme
import com.uppro.nero.ui.Orb
import com.uppro.nero.ui.glassHighlight
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Estados da conversa com o Nero. */
private sealed interface Phase {
    data object Idle : Phase
    data class Listening(val partial: String) : Phase
    data class Done(val title: String, val detail: String, val undo: (() -> Unit)?) : Phase
    data class AskTime(val title: String) : Phase
    data class NotUnderstood(val text: String) : Phase
    data class Problem(val message: String) : Phase
}

/**
 * Assistente do Nero: folha de vidro na base da tela com a esfera colorida.
 * Ouve pelo reconhecimento de voz do Android (pt-BR) ou recebe texto digitado,
 * e entende os pedidos offline com o [CommandParser].
 */
class AssistantActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null
    private var phase by mutableStateOf<Phase>(Phase.Idle)
    private var level by mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 48 }
        }
        setContent {
            NeroTheme {
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    if (ok) startListening() else phase = Phase.Problem("Sem acesso ao microfone. Você pode digitar o pedido.")
                }
                LaunchedEffect(Unit) {
                    if (hasMic()) startListening() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                Sheet(
                    phase = phase,
                    level = level,
                    onMic = { if (hasMic()) startListening() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onSend = { text -> stopListening(); handle(text) },
                    onClose = { finish() },
                    onPickTime = { title, at -> createReminder(title, at) },
                    onSaveNote = { text -> saveNote(text) },
                )
            }
        }
    }

    override fun onPause() {
        stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------------- Voz ----------------

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            phase = Phase.Problem("O reconhecimento de voz não está disponível neste celular. Digite o pedido.")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                phase = Phase.Listening("")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                level = 0f
            }
            override fun onError(error: Int) {
                level = 0f
                phase = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        Phase.Problem("Não ouvi nada. Toque no microfone e fale de novo.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        Phase.Problem("A voz precisa do pacote de idioma offline ou de internet. Você pode digitar.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Phase.Problem("Sem acesso ao microfone.")
                    else -> Phase.Problem("Não consegui ouvir. Toque no microfone para tentar de novo.")
                }
            }
            override fun onResults(results: Bundle?) {
                level = 0f
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) phase = Phase.Problem("Não ouvi nada. Toque no microfone e fale de novo.") else handle(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) phase = Phase.Listening(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        phase = Phase.Listening("")
        runCatching { r.startListening(intent) }.onFailure {
            phase = Phase.Problem("Não consegui abrir o microfone. Digite o pedido.")
        }
    }

    private fun stopListening() {
        runCatching { recognizer?.cancel() }
        level = 0f
        if (phase is Phase.Listening) phase = Phase.Idle
    }

    // ---------------- Execução ----------------

    private fun handle(text: String) {
        when (val cmd = CommandParser.parse(text)) {
            is Command.AddReminder -> createReminder(cmd.title, cmd.at)
            is Command.ReminderNeedsTime -> phase = Phase.AskTime(cmd.title)
            is Command.AddNote -> saveNote(cmd.text)
            is Command.StartTimer -> {
                val label = cmd.label
                NeroState.setTimer(TimerInfo(cmd.durationMs, SystemClock.elapsedRealtime() + cmd.durationMs, label))
                ReminderScheduler.scheduleTimer(this, System.currentTimeMillis() + cmd.durationMs, label)
                phase = Phase.Done("Timer iniciado", describeDuration(cmd.durationMs) + if (label.isNotBlank()) " · $label" else "") {
                    NeroState.setTimer(null)
                    ReminderScheduler.cancelTimer(this)
                }
            }
            is Command.SetAlarm -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { if (cmd.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, cmd.label) }
                val ok = runCatching { startActivity(intent) }.isSuccess
                phase = if (ok) {
                    Phase.Done("Alarme criado", "%02d:%02d".format(cmd.hour, cmd.minute) + " · no app Relógio", null)
                } else {
                    Phase.Problem("Não encontrei um app de relógio para criar o alarme.")
                }
            }
            is Command.Unknown -> phase = Phase.NotUnderstood(text)
        }
    }

    private fun createReminder(title: String, at: LocalDateTime) {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val r = ReminderScheduler.create(this, title, millis)
        val synced = if (r.calendarEventId != null) " · Google Agenda ✓" else ""
        phase = Phase.Done("Lembrete criado", "$title · ${describeWhen(at)}$synced") {
            Store.reminder(r.id)?.let { ReminderScheduler.delete(this, it) }
        }
    }

    private fun saveNote(text: String) {
        val note = Store.addNote(text)
        phase = Phase.Done("Nota salva", text) { Store.deleteNote(note.id) }
    }

    private fun describeDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val sec = (ms / 1000) % 60
        return when {
            totalMin >= 60 -> "${totalMin / 60} h" + if (totalMin % 60 > 0) " ${totalMin % 60} min" else ""
            totalMin > 0 -> "$totalMin min" + if (sec > 0) " $sec s" else ""
            else -> "$sec s"
        }
    }

    private fun describeWhen(at: LocalDateTime): String {
        val today = LocalDateTime.now().toLocalDate()
        val day = when (at.toLocalDate()) {
            today -> "Hoje"
            today.plusDays(1) -> "Amanhã"
            today.plusDays(2) -> "Depois de amanhã"
            else -> at.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale("pt", "BR")))
                .replaceFirstChar { it.uppercase() }
        }
        return "$day, " + at.format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

@Composable
private fun Sheet(
    phase: Phase,
    level: Float,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    onPickTime: (String, LocalDateTime) -> Unit,
    onSaveNote: (String) -> Unit,
) {
    val view = LocalView.current
    var text by remember { mutableStateOf("") }

    // Fecha sozinho depois de confirmar.
    LaunchedEffect(phase) {
        if (phase is Phase.Done) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            delay(2600)
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(34.dp))
                .background(Color(0xE61C1C24))
                .glassHighlight(34.dp)
                .border(0.8.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(34.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedContent(
                targetState = phase,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { it / 4 })
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "phase",
            ) { p ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    when (p) {
                        Phase.Idle -> {
                            Orb(64.dp)
                            Hint("Toque no microfone ou digite.\nEx.: “anota comprar pão”")
                        }
                        is Phase.Listening -> {
                            Orb(72.dp, level)
                            Text(
                                p.partial.ifBlank { "Ouvindo…" },
                                color = if (p.partial.isBlank()) Nero.Ink2 else Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                        }
                        is Phase.Done -> {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Nero.Green, modifier = Modifier.size(54.dp))
                            Text(p.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.detail, color = Nero.Ink2, fontSize = 15.sp, textAlign = TextAlign.Center)
                            p.undo?.let { undo ->
                                Chip("Desfazer") {
                                    undo()
                                    onClose()
                                }
                            }
                        }
                        is Phase.AskTime -> {
                            Text("Para quando?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.title, color = Nero.Ink2, fontSize = 15.sp, textAlign = TextAlign.Center)
                            val now = LocalDateTime.now()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Em 1 hora") { onPickTime(p.title, now.plusHours(1).withSecond(0).withNano(0)) }
                                if (now.hour < 18) Chip("Hoje 18:00") { onPickTime(p.title, now.toLocalDate().atTime(18, 0)) }
                                Chip("Amanhã 9:00") { onPickTime(p.title, now.toLocalDate().plusDays(1).atTime(9, 0)) }
                            }
                        }
                        is Phase.NotUnderstood -> {
                            Icon(Icons.Rounded.HelpOutline, null, tint = Nero.Gold, modifier = Modifier.size(44.dp))
                            Text("Não entendi como pedido", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("“${p.text}”", color = Nero.Ink2, fontSize = 15.sp, textAlign = TextAlign.Center)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Salvar como nota") { onSaveNote(p.text) }
                                Chip("Falar de novo", onClick = onMic)
                            }
                        }
                        is Phase.Problem -> {
                            Orb(52.dp)
                            Hint(p.message)
                        }
                    }
                }
            }

            // Campo de texto + microfone
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(27.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("Digite um pedido…", color = Nero.Ink3, fontSize = 16.sp)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Nero.Blue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (text.isNotBlank()) {
                                onSend(text)
                                text = ""
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val sending = text.isNotBlank()
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (sending) Color.White else Color.White.copy(alpha = 0.14f))
                        .clickable(role = Role.Button, onClickLabel = if (sending) "Enviar" else "Falar") {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (sending) {
                                onSend(text)
                                text = ""
                            } else {
                                onMic()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (sending) Icons.Rounded.ArrowUpward else Icons.Rounded.Mic,
                        if (sending) "Enviar" else "Falar",
                        tint = if (sending) Color(0xFF0B0B0F) else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Nero.Ink2, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
