package com.uppro.nero.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.uppro.nero.R

/**
 * Uma janela flutuante da ilha (barra, cartão, painel ou tracinho).
 * Usa um Dialog de sobreposição porque só janelas com Window próprio conseguem
 * desfocar o que está atrás (vidro fosco real no Android 12+).
 */
class OverlayWindow(
    context: Context,
    private val cornerDp: Float,
    private val glass: Boolean,
    animStyle: Int,
    watchOutside: Boolean = false,
    private val onOutside: (() -> Unit)? = null,
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
        setStroke((density * 0.8f).toInt().coerceAtLeast(1), Color.argb(46, 255, 255, 255))
    }

    private var tint = 0xFF1C1C24.toInt()
    private var blur = false
    var isShowing = false
        private set

    init {
        val w = dialog.window ?: error("Sem janela")
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (watchOutside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        w.addFlags(flags)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setBackgroundDrawable(if (glass) background else ColorDrawable(Color.TRANSPARENT))
        w.setWindowAnimations(animStyle)
        w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(ComposeView(context).apply { setContent(content) })
        applyColor()
    }

    fun layout(widthPx: Int, yPx: Int) {
        val w = dialog.window ?: return
        val lp = w.attributes
        if (lp.width == widthPx && lp.y == yPx && lp.height == WindowManager.LayoutParams.WRAP_CONTENT) return
        lp.width = widthPx
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.y = yPx
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        w.attributes = lp
    }

    /** Cor do vidro. Com desfoque disponível o vidro fica bem mais transparente. */
    fun setTint(argb: Int) {
        if (tint == argb) return
        tint = argb
        applyColor()
    }

    fun setBlurEnabled(enabled: Boolean) {
        if (!glass || blur == enabled) return
        blur = enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dialog.window?.setBackgroundBlurRadius(if (enabled) (34 * density).toInt() else 0)
        }
        applyColor()
    }

    private fun applyColor() {
        if (!glass) return
        val alpha = if (blur) 150 else 228
        // Escurece a cor de contexto para o texto branco sempre ficar legível.
        val r = (Color.red(tint) * 0.62f).toInt()
        val g = (Color.green(tint) * 0.62f).toInt()
        val b = (Color.blue(tint) * 0.62f).toInt()
        background.setColor(Color.argb(alpha, r, g, b))
    }

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
