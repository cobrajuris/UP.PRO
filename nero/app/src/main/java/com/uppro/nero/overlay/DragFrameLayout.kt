package com.uppro.nero.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Raiz arrastável da pílula. Mede o arrasto em coordenadas da tela (rawX/rawY),
 * o que evita o tremor de arrastar uma janela que se move junto com o dedo.
 * Toques curtos e toques longos seguem para o Compose normalmente.
 */
@SuppressLint("ViewConstructor")
class DragFrameLayout(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onDragStart()
        fun onDragMove(dx: Float, dy: Float)
        fun onDragEnd()
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.4f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private var dragging = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                lastX = downX; lastY = downY
                tracking = true
                dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && tracking && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    dragging = true
                    // Cancela o toque nos filhos para não virar clique ao soltar.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    listener.onDragStart()
                    listener.onDragMove(ev.rawX - downX, ev.rawY - downY)
                    lastX = ev.rawX; lastY = ev.rawY
                    return true
                }
                if (dragging) {
                    listener.onDragMove(ev.rawX - lastX, ev.rawY - lastY)
                    lastX = ev.rawX; lastY = ev.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (dragging) {
                    dragging = false
                    listener.onDragEnd()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
