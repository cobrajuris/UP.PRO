package com.uppro.horizon.state

import android.content.Context
import android.media.session.MediaController
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Mídia ativa lida da MediaSession do sistema (Spotify, YouTube Music, etc.). */
data class MediaInfo(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** Base de tempo em [SystemClock.elapsedRealtime] da última posição informada. */
    val positionUpdatedAt: Long,
    val speed: Float,
    /** Quando a mídia foi pausada (elapsedRealtime), para recolher a ilha depois de um tempo. */
    val pausedAt: Long?,
) {
    fun positionAt(now: Long): Long {
        val pos = if (isPlaying) positionMs + ((now - positionUpdatedAt) * speed).toLong() else positionMs
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }
}

/** Cronômetro regressivo. Enquanto pausado, [pausedRemainingMs] guarda o restante. */
data class TimerInfo(
    val totalMs: Long,
    val endAt: Long,
    val pausedRemainingMs: Long? = null,
) {
    val isPaused: Boolean get() = pausedRemainingMs != null
    fun remainingAt(now: Long): Long = pausedRemainingMs ?: (endAt - now).coerceAtLeast(0)
}

enum class Dock { BOTTOM, LEFT, RIGHT }

data class IslandSettings(
    val minimizeInFullscreen: Boolean = true,
    val minimizeInLandscape: Boolean = true,
    val dock: Dock = Dock.BOTTOM,
    /** Posição vertical (fração da altura da tela) quando encaixada na lateral. */
    val sideY: Float = 0.62f,
)

/**
 * Estado global da ilha. Compartilhado entre o serviço de notificações (fonte da mídia),
 * o serviço de overlay (desenha a pílula) e a tela do app.
 */
object IslandRepository {

    /** Tempo que a ilha continua visível depois de pausar a mídia. */
    const val PAUSED_LINGER_MS = 45_000L

    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    private val _timer = MutableStateFlow<TimerInfo?>(null)
    val timer: StateFlow<TimerInfo?> = _timer.asStateFlow()

    /** Alerta curto (ex.: "Tempo esgotado"). */
    private val _alert = MutableStateFlow<String?>(null)
    val alert: StateFlow<String?> = _alert.asStateFlow()

    private val _settings = MutableStateFlow(IslandSettings())
    val settings: StateFlow<IslandSettings> = _settings.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    @Volatile
    private var controller: MediaController? = null
    private var demoActive = false
    private var prefsLoaded = false

    // ---------- Mídia ----------

    internal fun publishMedia(info: MediaInfo?, controller: MediaController?) {
        if (demoActive && info == null) return
        if (info != null) demoActive = false
        this.controller = controller
        _media.value = info
    }

    internal fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
        if (!connected && !demoActive) {
            controller = null
            _media.value = null
        }
    }

    fun playPause() {
        val c = controller
        val m = _media.value ?: return
        if (c != null) {
            if (m.isPlaying) c.transportControls.pause() else c.transportControls.play()
        } else if (demoActive) {
            val now = SystemClock.elapsedRealtime()
            _media.value = m.copy(
                isPlaying = !m.isPlaying,
                positionMs = m.positionAt(now),
                positionUpdatedAt = now,
                pausedAt = if (m.isPlaying) now else null,
            )
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    /** Mídia de demonstração para testar a ilha sem nenhum player aberto. */
    fun startDemo() {
        if (controller != null && _media.value?.isPlaying == true) return
        demoActive = true
        controller = null
        val now = SystemClock.elapsedRealtime()
        _media.value = MediaInfo(
            packageName = "demo",
            appLabel = "Horizon",
            title = "Neural Sync Active",
            artist = "Audio Stream • 48kHz",
            art = null,
            isPlaying = true,
            durationMs = 3 * 60_000L + 24_000L,
            positionMs = 0,
            positionUpdatedAt = now,
            speed = 1f,
            pausedAt = null,
        )
    }

    fun stopDemo() {
        if (!demoActive) return
        demoActive = false
        _media.value = null
    }

    val isDemo: Boolean get() = demoActive

    // ---------- Cronômetro ----------

    fun startTimer(minutes: Int) {
        val total = minutes * 60_000L
        _alert.value = null
        _timer.value = TimerInfo(totalMs = total, endAt = SystemClock.elapsedRealtime() + total)
    }

    fun addMinute() {
        _timer.update { t ->
            t ?: return@update null
            if (t.pausedRemainingMs != null) {
                t.copy(totalMs = t.totalMs + 60_000L, pausedRemainingMs = t.pausedRemainingMs + 60_000L)
            } else {
                t.copy(totalMs = t.totalMs + 60_000L, endAt = t.endAt + 60_000L)
            }
        }
    }

    fun toggleTimerPause() {
        val now = SystemClock.elapsedRealtime()
        _timer.update { t ->
            t ?: return@update null
            if (t.pausedRemainingMs != null) {
                t.copy(endAt = now + t.pausedRemainingMs, pausedRemainingMs = null)
            } else {
                t.copy(pausedRemainingMs = t.remainingAt(now))
            }
        }
    }

    fun cancelTimer() {
        _timer.value = null
    }

    /** Chamado pelo serviço quando o tempo zera. */
    internal fun finishTimer() {
        _timer.value = null
        _alert.value = "Tempo esgotado"
    }

    fun dismissAlert() {
        _alert.value = null
    }

    // ---------- Configurações ----------

    fun loadSettings(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val p = context.getSharedPreferences("island", Context.MODE_PRIVATE)
        _settings.value = IslandSettings(
            minimizeInFullscreen = p.getBoolean("fullscreen", true),
            minimizeInLandscape = p.getBoolean("landscape", true),
            dock = runCatching { Dock.valueOf(p.getString("dock", Dock.BOTTOM.name)!!) }.getOrDefault(Dock.BOTTOM),
            sideY = p.getFloat("sideY", 0.62f),
        )
    }

    fun updateSettings(context: Context, transform: (IslandSettings) -> IslandSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        context.getSharedPreferences("island", Context.MODE_PRIVATE).edit()
            .putBoolean("fullscreen", s.minimizeInFullscreen)
            .putBoolean("landscape", s.minimizeInLandscape)
            .putString("dock", s.dock.name)
            .putFloat("sideY", s.sideY)
            .apply()
    }

    internal fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }
}
