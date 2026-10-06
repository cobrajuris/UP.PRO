package com.uppro.horizon.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Raiz da janela flutuante. Usa coordenadas absolutas da tela (rawX/rawY) para arrastar,
 * o que evita o tremor clássico de arrastar uma janela que se move junto com o dedo.
 * Toques curtos seguem normalmente para o Compose.
 */
@SuppressLint("ViewConstructor")
class DragFrameLayout(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onDragStart(rawX: Float, rawY: Float)
        fun onDragMove(rawX: Float, rawY: Float)
        fun onDragEnd(rawX: Float, rawY: Float)
        fun onOutsideTouch()
    }

    var listener: Listener? = null
    var dragEnabled: Boolean = true

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                listener?.onOutsideTouch()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                tracking = true
                dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && tracking && dragEnabled && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    dragging = true
                    // Cancela o toque nos filhos para o clique não disparar ao soltar.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    listener?.onDragStart(downX, downY)
                }
                if (dragging) {
                    listener?.onDragMove(ev.rawX, ev.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (dragging) {
                    dragging = false
                    listener?.onDragEnd(ev.rawX, ev.rawY)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
