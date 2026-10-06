package com.uppro.nero.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.uppro.nero.data.MediaInfo
import com.uppro.nero.data.NavInfo
import com.uppro.nero.data.NeroState

/**
 * Lê as MediaSessions ativas (música) e as notificações de navegação do Google Maps / Waze.
 * O Android só libera esses dados para apps com "Acesso a notificações".
 */
class NeroListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var lastArtKey: String? = null
    private var lastArt: ImageBitmap? = null
    private var lastArtColor: Int? = null
    private val pausedAt = mutableMapOf<String, Long>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindControllers(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NeroState.setListenerConnected(true)
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = manager
        val component = ComponentName(this, NeroListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            bindControllers(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            NeroState.setListenerConnected(false)
        }
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onListenerDisconnected() {
        clear()
        NeroState.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        clear()
        NeroState.setListenerConnected(false)
        super.onDestroy()
    }

    // ---------------- Navegação (Maps / Waze) ----------------

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NAV_PACKAGES) return
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            ?.toString().orEmpty()
        // Só notificações de navegação em andamento (ignora "toque para navegar" etc.).
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!ongoing || (title.isBlank() && text.isBlank())) return
        val icon = runCatching { iconBitmap(n.getLargeIcon() ?: n.smallIcon) }.getOrNull()
        NeroState.setNav(
            NavInfo(
                packageName = sbn.packageName,
                appLabel = if (sbn.packageName == WAZE) "Waze" else "Maps",
                title = title,
                text = text,
                icon = icon,
                contentIntent = n.contentIntent,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in NAV_PACKAGES) NeroState.clearNav(sbn.packageName)
    }

    private fun iconBitmap(icon: Icon?): ImageBitmap? {
        icon ?: return null
        val d = icon.loadDrawable(this) ?: return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap.asImageBitmap()
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp.asImageBitmap()
    }

    // ---------------- Música ----------------

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

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val candidates = callbacks.keys.filter { c ->
            val st = c.playbackState?.state
            c.metadata != null && st != null &&
                st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_STOPPED && st != PlaybackState.STATE_ERROR
        }
        val chosen = candidates.firstOrNull { it.playbackState?.state.isActivePlayback() } ?: candidates.firstOrNull()
        if (chosen == null) {
            NeroState.publishMedia(null, null)
            return
        }
        val state = chosen.playbackState ?: return
        val meta = chosen.metadata ?: return
        val isPlaying = state.state.isActivePlayback()
        val pkg = chosen.packageName
        if (isPlaying) pausedAt.remove(pkg) else pausedAt.putIfAbsent(pkg, now)

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val artKey = "$pkg|$title|$artist"
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            val bmp: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val small = bmp?.let { runCatching { scaled(it) }.getOrNull() }
            lastArt = small?.asImageBitmap()
            lastArtColor = small?.let { averageColor(it) }
        }

        NeroState.publishMedia(
            MediaInfo(
                packageName = pkg,
                title = title.ifBlank { appLabel(pkg) },
                artist = artist.ifBlank { appLabel(pkg) },
                art = lastArt,
                artColor = lastArtColor,
                isPlaying = isPlaying,
                durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
                positionMs = state.position.coerceAtLeast(0),
                positionUpdatedAt = if (state.lastPositionUpdateTime > 0) state.lastPositionUpdateTime else now,
                speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f,
                pausedAt = if (isPlaying) null else pausedAt[pkg],
            ),
            chosen,
        )
    }

    private fun scaled(src: Bitmap): Bitmap {
        val max = 192
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        if (soft.width <= max && soft.height <= max) return soft
        val ratio = minOf(max.toFloat() / soft.width, max.toFloat() / soft.height)
        return Bitmap.createScaledBitmap(
            soft, (soft.width * ratio).toInt().coerceAtLeast(1), (soft.height * ratio).toInt().coerceAtLeast(1), true,
        )
    }

    /** Cor média (levemente saturada) da capa para tingir o vidro. */
    private fun averageColor(b: Bitmap): Int {
        val tiny = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0L; var g = 0L; var bl = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = tiny.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF
        }
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((r / 64).toInt(), (g / 64).toInt(), (bl / 64).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.7f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private val labels = mutableMapOf<String, String>()
    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    companion object {
        private const val WAZE = "com.waze"
        private val NAV_PACKAGES = setOf("com.google.android.apps.maps", WAZE)

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NeroListenerService::class.java)
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
