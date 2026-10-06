package com.uppro.horizon.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.ui.graphics.asImageBitmap
import com.uppro.horizon.state.IslandRepository
import com.uppro.horizon.state.MediaInfo

/**
 * O Android só libera a lista de MediaSessions ativas para apps com acesso a notificações.
 * Este serviço observa as sessões e publica a mídia atual no [IslandRepository].
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var lastArtKey: String? = null
    private var lastArt: androidx.compose.ui.graphics.ImageBitmap? = null
    private val pausedAt = mutableMapOf<String, Long>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindControllers(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandRepository.setListenerConnected(true)
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = manager
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            bindControllers(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            IslandRepository.setListenerConnected(false)
        }
    }

    override fun onListenerDisconnected() {
        clear()
        IslandRepository.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        clear()
        IslandRepository.setListenerConnected(false)
        super.onDestroy()
    }

    private fun clear() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        sessionManager = null
    }

    private fun bindControllers(controllers: List<MediaController>) {
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        controllers.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() = publish()
            }
            c.registerCallback(cb, handler)
            callbacks[c] = cb
        }
        publish()
    }

    /** Escolhe a sessão mais relevante: tocando agora > pausada há menos tempo. */
    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val candidates = callbacks.keys.filter { c ->
            val st = c.playbackState?.state
            c.metadata != null && st != null &&
                st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_STOPPED &&
                st != PlaybackState.STATE_ERROR
        }
        val playing = candidates.firstOrNull { it.playbackState?.state.isActivePlayback() }
        val chosen = playing ?: candidates.firstOrNull()
        if (chosen == null) {
            IslandRepository.publishMedia(null, null)
            return
        }
        val state = chosen.playbackState!!
        val meta = chosen.metadata!!
        val isPlaying = state.state.isActivePlayback()
        val pkg = chosen.packageName
        if (isPlaying) pausedAt.remove(pkg) else pausedAt.putIfAbsent(pkg, now)

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val artKey = "$pkg|$title|$artist"
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            val bmp: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            lastArt = bmp?.let { runCatching { scaled(it).asImageBitmap() }.getOrNull() }
        }

        val info = MediaInfo(
            packageName = pkg,
            appLabel = appLabel(pkg),
            title = title.ifBlank { appLabel(pkg) },
            artist = artist,
            art = lastArt,
            isPlaying = isPlaying,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = state.position.coerceAtLeast(0),
            positionUpdatedAt = if (state.lastPositionUpdateTime > 0) state.lastPositionUpdateTime else now,
            speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f,
            pausedAt = if (isPlaying) null else pausedAt[pkg],
        )
        IslandRepository.publishMedia(info, chosen)
    }

    private fun scaled(src: Bitmap): Bitmap {
        val max = 192
        if (src.width <= max && src.height <= max) return src
        val ratio = minOf(max.toFloat() / src.width, max.toFloat() / src.height)
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private val labels = mutableMapOf<String, String>()
    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, MediaListenerService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

private fun Int?.isActivePlayback(): Boolean = when (this) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_CONNECTING -> true
    else -> false
}
