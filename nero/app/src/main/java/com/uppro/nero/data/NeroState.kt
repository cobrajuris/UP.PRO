package com.uppro.nero.data

import android.app.PendingIntent
import android.media.session.MediaController
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    /** Cor média da capa (ARGB), usada para tingir o vidro. */
    val artColor: Int?,
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val pausedAt: Long?,
) {
    fun positionAt(now: Long): Long {
        val pos = if (isPlaying) positionMs + ((now - positionUpdatedAt) * speed).toLong() else positionMs
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }
}

/** Instrução de navegação lida da notificação do Google Maps ou Waze. */
data class NavInfo(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val icon: ImageBitmap?,
    val contentIntent: PendingIntent?,
)

data class TimerInfo(val totalMs: Long, val endAt: Long, val label: String) {
    fun remainingAt(now: Long): Long = (endAt - now).coerceAtLeast(0)
}

data class ChargingInfo(val level: Int, val since: Long)

enum class TransferDirection { TO_NOTEBOOK, FROM_NOTEBOOK, READY }

data class TransferInfo(
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val direction: TransferDirection,
    val finished: Boolean,
    val updatedAt: Long,
)

data class AlertInfo(val title: String, val subtitle: String, val at: Long)

data class Upcoming(val title: String, val at: Long, val where: String)

data class NotebookStatus(val running: Boolean, val url: String?, val error: String? = null)

/** Estado ao vivo do sistema (não persistido). */
object NeroState {

    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    private val _nav = MutableStateFlow<NavInfo?>(null)
    val nav: StateFlow<NavInfo?> = _nav.asStateFlow()

    private val _timer = MutableStateFlow<TimerInfo?>(null)
    val timer: StateFlow<TimerInfo?> = _timer.asStateFlow()

    private val _charging = MutableStateFlow<ChargingInfo?>(null)
    val charging: StateFlow<ChargingInfo?> = _charging.asStateFlow()

    private val _transfer = MutableStateFlow<TransferInfo?>(null)
    val transfer: StateFlow<TransferInfo?> = _transfer.asStateFlow()

    private val _alert = MutableStateFlow<AlertInfo?>(null)
    val alert: StateFlow<AlertInfo?> = _alert.asStateFlow()

    private val _upcoming = MutableStateFlow<Upcoming?>(null)
    val upcoming: StateFlow<Upcoming?> = _upcoming.asStateFlow()

    private val _notebook = MutableStateFlow(NotebookStatus(false, null))
    val notebook: StateFlow<NotebookStatus> = _notebook.asStateFlow()

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    @Volatile
    private var controller: MediaController? = null

    fun publishMedia(info: MediaInfo?, controller: MediaController?) {
        this.controller = controller
        _media.value = info
    }

    fun playPause() {
        val c = controller ?: return
        if (_media.value?.isPlaying == true) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun setNav(info: NavInfo?) {
        _nav.value = info
    }

    fun clearNav(packageName: String) {
        if (_nav.value?.packageName == packageName) _nav.value = null
    }

    fun setTimer(info: TimerInfo?) {
        _timer.value = info
    }

    fun setCharging(info: ChargingInfo?) {
        _charging.value = info
    }

    fun setTransfer(info: TransferInfo?) {
        _transfer.value = info
    }

    fun updateTransfer(transform: (TransferInfo?) -> TransferInfo?) {
        _transfer.update(transform)
    }

    fun setAlert(info: AlertInfo?) {
        _alert.value = info
    }

    fun setUpcoming(info: Upcoming?) {
        _upcoming.value = info
    }

    fun setNotebook(status: NotebookStatus) {
        _notebook.value = status
    }

    fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
        if (!connected) {
            controller = null
            _media.value = null
            _nav.value = null
        }
    }

    fun now(): Long = SystemClock.elapsedRealtime()
}
