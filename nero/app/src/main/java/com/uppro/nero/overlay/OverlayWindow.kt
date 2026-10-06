package com.uppro.nero.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.uppro.nero.R

/**
 * Uma janela flutuante do Nero (a pílula lateral ou o painel).
 * Usa um Dialog de sobreposição porque só janelas com Window próprio conseguem
 * desfocar o que está atrás (vidro fosco real no Android 12+).
 */
class OverlayWindow(
    context: Context,
    private val cornerDp: Float,
    animStyle: Int,
    watchOutside: Boolean = false,
    /** Fio branco fino em volta do vidro (desligado na pílula, que tem contorno próprio). */
    private val outline: Boolean = true,
    private val onOutside: (() -> Unit)? = null,
    drag: DragFrameLayout.Listener? = null,
    content: @Composable () -> Unit,
) {
    private val density = context.resources.displayMetrics.density

    private val dialog = object : ComponentDialog(context, R.style.Theme_Nero_Overlay) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside?.invoke()
                return false
            }
            return super.onTouchEvent(event)
        }
    }

    private val background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerDp * density
    }

    private var tint = 0xFF1C1C24.toInt()
    private var blurSupported = false
    private var glassVisible = true
    var isShowing = false
        private set

    private val root: View

    init {
        val w = dialog.window ?: error("Sem janela")
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (watchOutside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        w.addFlags(flags)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setBackgroundDrawable(background)
        w.setWindowAnimations(animStyle)
        w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        val compose = ComposeView(context).apply { setContent(content) }
        root = if (drag != null) DragFrameLayout(context, drag).apply { addView(compose) } else compose
        dialog.setContentView(root)
        applyGlass()
    }

    // ---------------- Posição ----------------

    /** Ancora a janela: [gravity] do Android, deslocamentos em px. */
    fun place(gravity: Int, x: Int, y: Int, widthPx: Int = WindowManager.LayoutParams.WRAP_CONTENT) {
        val w = dialog.window ?: return
        val lp = w.attributes
        if (lp.gravity == gravity && lp.x == x && lp.y == y && lp.width == widthPx &&
            lp.height == WindowManager.LayoutParams.WRAP_CONTENT
        ) return
        lp.gravity = gravity
        lp.x = x
        lp.y = y
        lp.width = widthPx
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        w.attributes = lp
    }

    val gravity: Int get() = dialog.window?.attributes?.gravity ?: 0
    val x: Int get() = dialog.window?.attributes?.x ?: 0
    val y: Int get() = dialog.window?.attributes?.y ?: 0
    val width: Int get() = root.width
    val height: Int get() = root.height

    fun performHaptic(constant: Int) {
        root.performHapticFeedback(constant)
    }

    // ---------------- Vidro ----------------

    /** Cor do vidro. Com desfoque disponível o vidro fica bem mais transparente. */
    fun setTint(argb: Int) {
        if (tint == argb) return
        tint = argb
        applyGlass()
    }

    fun setBlurSupported(enabled: Boolean) {
        if (blurSupported == enabled) return
        blurSupported = enabled
        applyGlass()
    }

    /** Desliga o vidro (fundo totalmente transparente), usado no tracinho de tela cheia. */
    fun setGlassVisible(visible: Boolean) {
        if (glassVisible == visible) return
        glassVisible = visible
        applyGlass()
    }

    private fun applyGlass() {
        val blurOn = blurSupported && glassVisible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dialog.window?.setBackgroundBlurRadius(if (blurOn) (30 * density).toInt() else 0)
        }
        if (!glassVisible) {
            background.setColor(Color.TRANSPARENT)
            background.setStroke(0, Color.TRANSPARENT)
            return
        }
        val alpha = if (blurOn) 150 else 228
        // Escurece a cor de contexto para o texto branco sempre ficar legível.
        val r = (Color.red(tint) * 0.62f).toInt()
        val g = (Color.green(tint) * 0.62f).toInt()
        val b = (Color.blue(tint) * 0.62f).toInt()
        background.setColor(Color.argb(alpha, r, g, b))
        if (outline) {
            background.setStroke((density * 0.8f).toInt().coerceAtLeast(1), Color.argb(46, 255, 255, 255))
        } else {
            background.setStroke(0, Color.TRANSPARENT)
        }
    }

    // ---------------- Visibilidade ----------------

    fun show() {
        if (isShowing) return
        runCatching { dialog.show() }.onSuccess { isShowing = true }
    }

    fun hide() {
        if (!isShowing) return
        runCatching { dialog.hide() }
        isShowing = false
    }

    fun destroy() {
        runCatching { dialog.dismiss() }
        isShowing = false
    }
}
