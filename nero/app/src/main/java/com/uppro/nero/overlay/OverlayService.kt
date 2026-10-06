package com.uppro.nero.overlay

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.animation.ValueAnimator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.uppro.nero.NeroApp
import com.uppro.nero.R
import com.uppro.nero.assistant.AssistantActivity
import com.uppro.nero.data.ChargingInfo
import com.uppro.nero.data.NeroState
import com.uppro.nero.data.Store
import com.uppro.nero.data.TransferDirection
import com.uppro.nero.data.Upcoming
import com.uppro.nero.reminders.CalendarSync
import com.uppro.nero.reminders.ReminderScheduler
import com.uppro.nero.ui.Card
import com.uppro.nero.ui.CardActions
import com.uppro.nero.ui.MiniPill
import com.uppro.nero.ui.PanelActivity
import com.uppro.nero.ui.MainActivity
import com.uppro.nero.ui.NeroTheme
import com.uppro.nero.ui.PanelActions
import com.uppro.nero.ui.PanelContent
import com.uppro.nero.ui.PanelData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A ilha Nero: barra de vidro fixa na base da tela, cartão de atividade logo acima
 * e painel completo ao deslizar para cima. Em vídeo/jogo em tela cheia vira um tracinho.
 */
class OverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private var pill: OverlayWindow? = null
    private var panel: OverlayWindow? = null
    private var tracker: View? = null

    private val cardState = MutableStateFlow<Card?>(null)
    private val minimizedState = MutableStateFlow(false)
    private val rightState = MutableStateFlow(true)
    private var dragging = false
    private var snapAnimator: ValueAnimator? = null
    private var panelClosedAt = 0L
    private val panelData = MutableStateFlow(PanelData(null, null, "Desligado", null, "Escolha nos Ajustes"))

    private var fullscreen = false
    private var landscape = false
    private var panelOpen = false
    private var peekUntil = 0L
    private var lastCalendarCheck = 0L
    private var calendarUpcoming: Upcoming? = null
    private var blurListener: java.util.function.Consumer<Boolean>? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> NeroState.setCharging(ChargingInfo(batteryLevel(), SystemClock.elapsedRealtime()))
                Intent.ACTION_POWER_DISCONNECTED -> NeroState.setCharging(null)
            }
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Store.updateSettings { it.copy(overlayEnabled = false) }
            startInForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pill == null) {
            try {
                createWindows()
                startWatchers()
                NeroState.setOverlayRunning(true)
            } catch (e: Exception) {
                destroyWindows()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(powerReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { runCatching { wm.removeCrossWindowBlurEnabledListener(it) } }
        }
        destroyWindows()
        NeroState.setOverlayRunning(false)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        layoutAll()
        refresh()
    }

    // ---------------------------------------------------------------------------------------
    // Janelas
    // ---------------------------------------------------------------------------------------

    private fun createWindows() {
        val cardActions = CardActions(
            playPause = { NeroState.playPause() },
            next = { NeroState.next() },
            open = { setPanel(false); openCardTarget(cardState.value) },
            dismiss = { dismissCard(cardState.value) },
        )
        pill = OverlayWindow(this, 27f, animStyle = R.style.NeroPopAnim, outline = false, drag = dragListener) {
            NeroTheme {
                val c by cardState.collectAsState()
                val min by minimizedState.collectAsState()
                val right by rightState.collectAsState()
                MiniPill(
                    card = c,
                    minimized = min,
                    onRight = right,
                    onTap = { onPillTap() },
                    onLongPress = { setPanel(false); openAssistant() },
                )
            }
        }
        panel = OverlayWindow(
            this, 34f, animStyle = R.style.NeroPanelAnim, watchOutside = true,
            onOutside = { if (panelOpen) setPanel(false) },
        ) {
            NeroTheme {
                val data by panelData.collectAsState()
                val c by cardState.collectAsState()
                PanelContent(
                    data = data,
                    actions = PanelActions(
                        assistant = { setPanel(false); openAssistant() },
                        notes = { setPanel(false); openTab("notes") },
                        reminders = { setPanel(false); openTab("reminders") },
                        notebook = { setPanel(false); openTab("notebook", pick = true) },
                        alarm = { setPanel(false); openAlarms() },
                        album = { setPanel(false); openAlbum() },
                        close = { setPanel(false) },
                    ),
                    activity = c?.let { current -> { PanelActivity(current, cardActions) } },
                )
            }
        }
        setupBlur()
        attachTracker()
        layoutAll()
        refresh()
    }

    private fun destroyWindows() {
        snapAnimator?.cancel()
        listOf(pill, panel).forEach { it?.destroy() }
        pill = null; panel = null
        tracker?.let { runCatching { wm.removeViewImmediate(it) } }
        tracker = null
    }

    private fun setupBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val apply = { enabled: Boolean -> listOf(pill, panel).forEach { it?.setBlurSupported(enabled) } }
        apply(wm.isCrossWindowBlurEnabled)
        val listener = java.util.function.Consumer<Boolean> { apply(it) }
        blurListener = listener
        runCatching { wm.addCrossWindowBlurEnabledListener(mainExecutor, listener) }
    }

    private fun onPillTap() {
        if (minimizedState.value) {
            peekUntil = SystemClock.elapsedRealtime() + 6_000
            refresh()
            return
        }
        // O toque na pílula também conta como "toque fora" do painel; evita reabrir na hora.
        if (SystemClock.elapsedRealtime() - panelClosedAt < 350) return
        setPanel(!panelOpen)
    }

    /** Sensor invisível: descobre se a barra de status sumiu (vídeo/jogo em tela cheia). */
    private fun attachTracker() {
        val t = View(this)
        val lp = WindowManager.LayoutParams(
            1, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        t.setOnApplyWindowInsetsListener { _, insets ->
            val hidden = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                !insets.isVisible(WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop == 0
            }
            if (hidden != fullscreen) {
                fullscreen = hidden
                if (hidden) setPanel(false)
                refresh()
            }
            insets
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            t.setOnSystemUiVisibilityChangeListener { vis ->
                val hidden = vis and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
                if (hidden != fullscreen) {
                    fullscreen = hidden
                    refresh()
                }
            }
        }
        wm.addView(t, lp)
        tracker = t
    }

    private fun screenWidth(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.currentWindowMetrics.bounds.width()
    } else {
        resources.displayMetrics.widthPixels
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun screenHeight(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.currentWindowMetrics.bounds.height()
    } else {
        resources.displayMetrics.heightPixels
    }

    private fun layoutAll() {
        val sw = screenWidth()
        val width = (sw - dp(24f)).coerceAtMost(dp(460f))
        panel?.layout(width)
        placePill()
    }

    private fun OverlayWindow.layout(widthPx: Int) =
        place(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(76f), widthPx)

    /** Pílula no canto de baixo (esquerdo ou direito), na altura salva. */
    private fun placePill() {
        val p = pill ?: return
        if (dragging || snapAnimator?.isRunning == true) return
        val s = Store.settings.value
        val h = p.height.takeIf { it > 0 } ?: dp(54f)
        val side = if (s.pillRight) Gravity.END else Gravity.START
        val min = minimizedState.value
        val margin = if (min) dp(4f) else dp(10f)
        val y = if (min) 0 else clampBottom((s.pillY * screenHeight()).toInt(), h)
        p.place(Gravity.BOTTOM or side, margin, y)
    }

    /** Distância até a base: de rente à barra de gestos até 80% da tela. */
    private fun clampBottom(y: Int, h: Int): Int {
        val max = (screenHeight() * 0.8f).toInt() - h
        return y.coerceIn(dp(6f), max.coerceAtLeast(dp(6f)))
    }

    // ---------------------------------------------------------------------------------------
    // Arrastar a pílula (qualquer altura, lado esquerdo ou direito)
    // ---------------------------------------------------------------------------------------

    private val dragListener = object : DragFrameLayout.Listener {
        override fun onDragStart() {
            val p = pill ?: return
            snapAnimator?.cancel()
            dragging = true
            if (panelOpen) setPanel(false)
            // Passa para coordenadas absolutas a partir da esquerda.
            val hg = p.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
            val absX = if (hg == Gravity.END || hg == Gravity.RIGHT) screenWidth() - p.width - p.x else p.x
            p.place(Gravity.BOTTOM or Gravity.START, absX, p.y)
            p.performHaptic(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onDragMove(dx: Float, dy: Float) {
            val p = pill ?: return
            // Com a âncora na base, arrastar para baixo diminui a distância até a base.
            p.place(Gravity.BOTTOM or Gravity.START, p.x + dx.toInt(), clampBottom(p.y - dy.toInt(), p.height))
        }

        override fun onDragEnd() {
            val p = pill ?: return
            val sw = screenWidth()
            val right = p.x + p.width / 2 > sw / 2
            val margin = if (minimizedState.value) dp(4f) else dp(10f)
            val startX = p.x
            val targetX = if (right) sw - p.width - margin else margin
            val y = p.y
            Store.updateSettings {
                it.copy(pillRight = right, pillY = (y.toFloat() / screenHeight()).coerceIn(0f, 0.8f))
            }
            rightState.value = right
            snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    p.place(Gravity.BOTTOM or Gravity.START, (startX + (targetX - startX) * t).toInt(), y)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        dragging = false
                        p.place(Gravity.BOTTOM or (if (right) Gravity.END else Gravity.START), margin, y)
                    }
                })
                start()
            }
            p.performHaptic(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.view.HapticFeedbackConstants.CONFIRM
                else android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Estado
    // ---------------------------------------------------------------------------------------

    private fun startWatchers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerReceiver, filter)
        }
        lifecycleScope.launch {
            while (true) {
                refresh()
                delay(500)
            }
        }
        listOf(NeroState.media, NeroState.nav, NeroState.timer, NeroState.alert, NeroState.transfer, NeroState.charging)
            .forEach { flow -> lifecycleScope.launch { flow.collect { refresh() } } }
        lifecycleScope.launch { Store.settings.collect { refresh() } }
    }

    private fun minimized(): Boolean {
        val s = Store.settings.value
        val wantsMin = (fullscreen && s.minimizeInFullscreen) || (landscape && s.minimizeInLandscape)
        return wantsMin && SystemClock.elapsedRealtime() > peekUntil
    }

    /** Recalcula o que mostrar e em qual janela. */
    private fun refresh() {
        if (pill == null) return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()

        // Próximo compromisso (lembretes do app + Google Agenda) nos próximos 15 min.
        if (now - lastCalendarCheck > 20_000) {
            lastCalendarCheck = now
            calendarUpcoming = CalendarSync.nextEvent(this, wall - 60_000, wall + 15 * 60_000)
        }
        val reminder = Store.reminders.value.firstOrNull { !it.done && it.at in (wall - 60_000)..(wall + 15 * 60_000) }
        val upcoming = reminder?.let { Upcoming(it.title, it.at, "") }
            ?: calendarUpcoming?.takeIf { it.at >= wall - 60_000 }
        NeroState.setUpcoming(upcoming)

        val alert = NeroState.alert.value?.takeIf { wall - it.at < 60_000 }
        val transfer = NeroState.transfer.value?.takeIf {
            !it.finished && it.direction != TransferDirection.READY && wall - it.updatedAt < 30_000 ||
                (it.finished || it.direction == TransferDirection.READY) && wall - it.updatedAt < 6_000
        }
        val charging = NeroState.charging.value?.takeIf { now - it.since < 6_000 }
        val nav = NeroState.nav.value
        val timer = NeroState.timer.value?.takeIf { it.remainingAt(now) > 0 }
        val media = NeroState.media.value?.takeIf { m ->
            m.isPlaying || (m.pausedAt?.let { now - it < 45_000 } ?: true)
        }
        val c: Card? = when {
            alert != null -> Card.Alert(alert)
            transfer != null -> Card.Transfer(transfer, NeroState.notebook.value.url)
            charging != null -> Card.Charging(charging)
            nav != null -> Card.Nav(nav)
            timer != null -> Card.Timer(timer)
            upcoming != null -> Card.Soon(upcoming)
            media != null -> Card.Music(media)
            else -> null
        }
        if (cardState.value != c) cardState.value = c
        val p = pill ?: return
        // A pílula é sempre escura; a cor do contexto vai no contorno de progresso.
        p.setTint(0xFF141418.toInt())

        val min = minimized()
        if (min != minimizedState.value) {
            minimizedState.value = min
            p.setGlassVisible(!min)
            if (min && panelOpen) setPanel(false)
        }
        rightState.value = Store.settings.value.pillRight
        placePill()
        p.show()
        if (panelOpen && !min) panel?.show() else panel?.hide()
    }

    private fun setPanel(open: Boolean) {
        if (open) panelData.value = buildPanelData()
        if (!open && panelOpen) panelClosedAt = SystemClock.elapsedRealtime()
        panelOpen = open
        refresh()
    }

    private fun buildPanelData(): PanelData {
        val nb = NeroState.notebook.value
        val nextReminder = Store.reminders.value.firstOrNull { !it.done && it.at > System.currentTimeMillis() }
        val alarm = (getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock
        val fmtDay = SimpleDateFormat("EEE HH:mm", Locale("pt", "BR"))
        val fmtTime = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        val s = Store.settings.value
        return PanelData(
            lastNote = Store.notes.value.firstOrNull()?.text,
            nextReminder = nextReminder?.let { "${it.title} · ${fmtDay.format(Date(it.at))}" },
            notebook = when {
                nb.running && nb.url != null -> nb.url.removePrefix("http://")
                nb.running -> "Sem Wi‑Fi"
                else -> "Toque para ligar"
            },
            nextAlarm = alarm?.let { fmtTime.format(Date(it.triggerTime)) },
            album = if (s.albumLink.isBlank()) "Escolha nos Ajustes" else s.albumName.ifBlank { "Tocar agora" },
        )
    }

    private fun dismissCard(c: Card?) {
        when (c) {
            is Card.Timer -> {
                NeroState.setTimer(null)
                ReminderScheduler.cancelTimer(this)
            }
            is Card.Alert -> NeroState.setAlert(null)
            else -> Unit
        }
        refresh()
    }

    private fun openCardTarget(c: Card?) {
        when (c) {
            is Card.Music -> packageManager.getLaunchIntentForPackage(c.m.packageName)?.let { launch(it) }
            is Card.Nav -> {
                val pi = c.n.contentIntent
                if (pi != null) {
                    runCatching { pi.send() }
                } else {
                    packageManager.getLaunchIntentForPackage(c.n.packageName)?.let { launch(it) }
                }
            }
            is Card.Soon, is Card.Alert -> {
                NeroState.setAlert(null)
                openTab("reminders")
            }
            is Card.Transfer -> openTab("notebook")
            is Card.Timer, is Card.Charging, null -> setPanel(true)
        }
    }

    private fun batteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    // ---------------------------------------------------------------------------------------
    // Ações
    // ---------------------------------------------------------------------------------------

    private fun launch(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openAssistant() = launch(Intent(this, AssistantActivity::class.java))

    private fun openTab(tab: String, pick: Boolean = false) = launch(
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .putExtra(MainActivity.EXTRA_PICK, pick)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    private fun openAlarms() = launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    private fun openAlbum() {
        val link = Store.settings.value.albumLink.trim()
        if (link.isBlank()) {
            openTab("settings")
            return
        }
        val uri = runCatching { Uri.parse(if (link.contains("://")) link else "https://$link") }.getOrNull()
        if (uri == null) {
            openTab("settings")
            return
        }
        launch(Intent(Intent.ACTION_VIEW, uri))
    }

    // ---------------------------------------------------------------------------------------

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NeroApp.createChannels(this)
        val n = Notification.Builder(this, NeroApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_nero)
            .setContentTitle("Nero ativo")
            .setContentText("A barra do Nero está na base da tela.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desligar", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.uppro.nero.OVERLAY_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
