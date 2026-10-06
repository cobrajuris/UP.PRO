package com.uppro.horizon.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.uppro.horizon.R
import com.uppro.horizon.state.Dock
import com.uppro.horizon.state.IslandRepository
import com.uppro.horizon.ui.HorizonTheme
import com.uppro.horizon.ui.IslandContent
import com.uppro.horizon.ui.MainActivity
import com.uppro.horizon.ui.NeoHorizonPill
import com.uppro.horizon.ui.PillActions
import com.uppro.horizon.ui.PillForm
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Desenha a ilha sobre qualquer app (permissão "Sobrepor a outros apps").
 *
 * Regras de UX seguidas:
 * 1. Zonas seguras: base da tela (zona do polegar) ou laterais. Nunca no centro do topo,
 *    onde fica a câmera frontal na maioria dos Androids; recortes de câmera são evitados.
 * 2. Por demanda: só aparece com algo ativo (mídia, cronômetro, alerta); senão some por completo.
 * 3. Sensível a mídia: em tela cheia (vídeo/jogo) ou paisagem vira uma aba translúcida na borda.
 */
class IslandOverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private lateinit var wm: WindowManager
    private var root: DragFrameLayout? = null
    private var tracker: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val expanded = MutableStateFlow(false)
    private val active = MutableStateFlow(false)
    private val formState = MutableStateFlow(FormState(PillForm.COMPACT, true))

    private var fullscreen = false
    private var landscape = false
    private var navInset = 0
    private var statusInset = 0
    private var cutouts: List<Rect> = emptyList()

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var snapAnimator: ValueAnimator? = null
    private var alertJob: Job? = null

    data class FormState(val form: PillForm, val onEnd: Boolean)

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        IslandRepository.loadSettings(this)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) {
            try {
                attachViews()
                startWatchers()
                IslandRepository.setOverlayRunning(true)
            } catch (e: Exception) {
                detachViews()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        snapAnimator?.cancel()
        detachViews()
        IslandRepository.setOverlayRunning(false)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateForm()
        root?.post { applyPosition() }
    }

    // ---------------------------------------------------------------------------------------
    // Janelas
    // ---------------------------------------------------------------------------------------

    private fun overlayParams(width: Int, height: Int, extraFlags: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or extraFlags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun attachViews() {
        // 1) Sensor invisível (1px, não tocável) que recebe os insets do sistema para
        //    descobrir se a barra de status sumiu (vídeo/jogo em tela cheia).
        val t = View(this)
        val tp = overlayParams(
            1,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
        )
        t.setOnApplyWindowInsetsListener { _, insets ->
            readInsets(insets)
            insets
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            t.setOnSystemUiVisibilityChangeListener { vis ->
                setFullscreen(vis and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            }
        }
        wm.addView(t, tp)
        tracker = t

        // 2) A ilha em si.
        params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            windowAnimations = 0
        }

        val frame = DragFrameLayout(this)
        frame.setViewTreeLifecycleOwner(this)
        frame.setViewTreeSavedStateRegistryOwner(this)
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { IslandHost() }
        }
        frame.addView(compose)
        frame.listener = dragListener
        frame.addOnLayoutChangeListener { _, l, t2, r, b, ol, ot, or2, ob ->
            if ((r - l != or2 - ol || b - t2 != ob - ot) && !dragging && snapAnimator?.isRunning != true) {
                applyPosition()
            }
        }
        wm.addView(frame, params)
        root = frame
        updateForm()
    }

    private fun detachViews() {
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        tracker?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
        tracker = null
    }

    @androidx.compose.runtime.Composable
    private fun IslandHost() {
        val media by IslandRepository.media.collectAsState()
        val timer by IslandRepository.timer.collectAsState()
        val alert by IslandRepository.alert.collectAsState()
        val isActive by active.collectAsState()
        val isExpanded by expanded.collectAsState()
        val fs by formState.collectAsState()
        HorizonTheme {
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 400f), initialScale = 0.6f),
                exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.7f),
            ) {
                NeoHorizonPill(
                    content = IslandContent(media, timer, alert),
                    expanded = isExpanded,
                    form = fs.form,
                    tabOnEnd = fs.onEnd,
                    actions = PillActions(
                        onToggleExpand = { setExpanded(!expanded.value) },
                        onPlayPause = IslandRepository::playPause,
                        onNext = IslandRepository::next,
                        onPrevious = IslandRepository::previous,
                        onTimerAddMinute = IslandRepository::addMinute,
                        onTimerPause = IslandRepository::toggleTimerPause,
                        onTimerCancel = IslandRepository::cancelTimer,
                        onDismissAlert = {
                            IslandRepository.dismissAlert()
                            setExpanded(false)
                        },
                    ),
                )
            }
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded.value = value
        root?.dragEnabled = !value
    }

    // ---------------------------------------------------------------------------------------
    // Estado: visibilidade, forma, fim do cronômetro
    // ---------------------------------------------------------------------------------------

    private fun startWatchers() {
        lifecycleScope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val content = IslandContent(
                    IslandRepository.media.value,
                    IslandRepository.timer.value,
                    IslandRepository.alert.value,
                )
                val timer = content.timer
                if (timer != null && !timer.isPaused && timer.remainingAt(now) <= 0L) {
                    onTimerFinished()
                }
                setActive(content.isActive(now))
                delay(if (timer != null) 250L else 600L)
            }
        }
        lifecycleScope.launch {
            IslandRepository.settings.collect {
                updateForm()
                root?.post { applyPosition() }
            }
        }
        lifecycleScope.launch {
            // Reage imediatamente a mudanças (sem esperar o próximo tique).
            IslandRepository.media.collect { refreshActiveNow() }
        }
        lifecycleScope.launch {
            IslandRepository.timer.collect { refreshActiveNow() }
        }
        lifecycleScope.launch {
            IslandRepository.alert.collect { refreshActiveNow() }
        }
    }

    private fun refreshActiveNow() {
        val content = IslandContent(IslandRepository.media.value, IslandRepository.timer.value, IslandRepository.alert.value)
        setActive(content.isActive(SystemClock.elapsedRealtime()))
    }

    private fun setActive(value: Boolean) {
        if (active.value == value) return
        active.value = value
        if (!value) setExpanded(false)
        val r = root ?: return
        val touchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = if (value) params.flags and touchable.inv() else params.flags or touchable
        runCatching { wm.updateViewLayout(r, params) }
    }

    private fun onTimerFinished() {
        IslandRepository.finishTimer()
        vibrate()
        alertJob?.cancel()
        alertJob = lifecycleScope.launch {
            delay(8_000)
            if (IslandRepository.alert.value != null && IslandRepository.timer.value == null) {
                IslandRepository.dismissAlert()
            }
        }
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180, 120, 320), -1))
        }
    }

    private fun isMinimized(): Boolean {
        val s = IslandRepository.settings.value
        return (fullscreen && s.minimizeInFullscreen) || (landscape && s.minimizeInLandscape)
    }

    private fun updateForm() {
        val s = IslandRepository.settings.value
        val form = when {
            isMinimized() -> PillForm.TAB
            s.dock == Dock.BOTTOM -> PillForm.COMPACT
            else -> PillForm.ORB
        }
        val next = FormState(form, s.dock != Dock.LEFT)
        if (next != formState.value) {
            // Ao entrar em tela cheia, recolhe tudo para não atrapalhar o vídeo/jogo.
            if (form == PillForm.TAB && formState.value.form != PillForm.TAB) setExpanded(false)
            formState.value = next
        }
    }

    private fun setFullscreen(value: Boolean) {
        if (fullscreen == value) return
        fullscreen = value
        updateForm()
        root?.post { applyPosition() }
    }

    private fun readInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val status = insets.isVisible(WindowInsets.Type.statusBars())
            navInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            statusInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            setFullscreen(!status)
        } else {
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            if (bottom > 0) navInset = bottom
            if (top > 0) statusInset = top
            setFullscreen(top == 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cutouts = insets.displayCutout?.boundingRects.orEmpty()
        }
        root?.post { applyPosition() }
    }

    // ---------------------------------------------------------------------------------------
    // Posicionamento e arrasto
    // ---------------------------------------------------------------------------------------

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Onde a ilha deve ficar agora, dado o tamanho atual da janela. */
    private fun targetPosition(): Point? {
        val r = root ?: return null
        val w = r.width
        val h = r.height
        if (w <= 0 || h <= 0) return null
        val screen = screenSize()
        val s = IslandRepository.settings.value
        val bottomLimit = screen.y - navInset - dp(6)
        val topLimit = statusInset + dp(6)
        val fs = formState.value

        var x: Int
        var y: Int
        if (fs.form == PillForm.TAB || (isMinimized() && expanded.value)) {
            x = if (fs.onEnd) screen.x - w else 0
            y = (screen.y * 0.30f).toInt() - h / 2
        } else if (s.dock == Dock.BOTTOM) {
            x = (screen.x - w) / 2
            y = bottomLimit - dp(8) - h
        } else {
            x = if (s.dock == Dock.RIGHT) screen.x - w else 0
            y = (screen.y * s.sideY).toInt() - h / 2
        }
        // Nunca sob o recorte da câmera.
        for (c in cutouts) {
            if (Rect.intersects(c, Rect(x, y, x + w, y + h))) {
                y = if (c.centerY() < screen.y / 2) c.bottom + dp(8) else c.top - dp(8) - h
            }
        }
        val maxY = (bottomLimit - h).coerceAtLeast(topLimit)
        y = y.coerceIn(topLimit, maxY)
        x = x.coerceIn(0, (screen.x - w).coerceAtLeast(0))
        return Point(x, y)
    }

    private fun applyPosition() {
        if (dragging || snapAnimator?.isRunning == true) return
        val r = root ?: return
        val p = targetPosition() ?: return
        if (params.x == p.x && params.y == p.y) return
        params.x = p.x
        params.y = p.y
        runCatching { wm.updateViewLayout(r, params) }
    }

    private fun moveTo(x: Int, y: Int) {
        val r = root ?: return
        params.x = x
        params.y = y
        runCatching { wm.updateViewLayout(r, params) }
    }

    private val dragListener = object : DragFrameLayout.Listener {
        override fun onDragStart(rawX: Float, rawY: Float) {
            snapAnimator?.cancel()
            dragging = true
            dragOffsetX = rawX - params.x
            dragOffsetY = rawY - params.y
            root?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onDragMove(rawX: Float, rawY: Float) {
            moveTo((rawX - dragOffsetX).toInt(), (rawY - dragOffsetY).toInt())
        }

        override fun onDragEnd(rawX: Float, rawY: Float) {
            dragging = false
            val screen = screenSize()
            val fx = rawX / screen.x
            val fy = (rawY / screen.y).coerceIn(0.12f, 0.88f)
            val minimized = isMinimized()
            IslandRepository.updateSettings(this@IslandOverlayService) { s ->
                when {
                    // Em tela cheia só troca o lado da aba.
                    minimized -> s.copy(dock = if (fx < 0.5f) Dock.LEFT else if (s.dock == Dock.LEFT) Dock.RIGHT else s.dock)
                    fx < 0.28f -> s.copy(dock = Dock.LEFT, sideY = fy)
                    fx > 0.72f -> s.copy(dock = Dock.RIGHT, sideY = fy)
                    else -> s.copy(dock = Dock.BOTTOM)
                }
            }
            updateForm()
            snapToTarget()
            val r = root ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                r.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                r.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        override fun onOutsideTouch() {
            if (expanded.value) setExpanded(false)
        }
    }

    /** Desliza até o encaixe, perseguindo o alvo enquanto a forma muda de tamanho. */
    private fun snapToTarget() {
        val startX = params.x
        val startY = params.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { a ->
                val t = a.animatedFraction
                val target = targetPosition() ?: return@addUpdateListener
                moveTo(
                    (startX + (target.x - startX) * t).toInt(),
                    (startY + (target.y - startY) * t).toInt(),
                )
            }
            start()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Serviço em primeiro plano
    // ---------------------------------------------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ilha ativa", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Mantém a Horizon Island funcionando sobre outros apps."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, IslandOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle("Horizon Island ativa")
            .setContentText("Aparece só quando há mídia tocando ou um cronômetro.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desligar", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "horizon_island"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.uppro.horizon.STOP"

        fun start(context: Context) {
            val i = Intent(context, IslandOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandOverlayService::class.java))
        }
    }
}

