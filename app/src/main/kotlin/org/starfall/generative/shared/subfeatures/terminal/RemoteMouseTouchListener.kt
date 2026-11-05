package org.starfall.generative.shared.subfeatures.terminal

import android.view.MotionEvent
import com.termux.view.TerminalView

/**
 * Touch adapter for terminal applications that use mouse tracking. A plain shell keeps
 * TerminalView's own scrollback and text selection behavior.
 */
internal class RemoteMouseTouchListener(
    context: android.content.Context,
    private val view: TerminalView,
    private val bridge: RemoteTerminalBridge
) : android.view.View.OnTouchListener {
    private var scrollRemainder = 0f
    private var tapDetected = false

    private val detector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapDetected = true
                val (column, row) = cellAt(e)
                bridge.sendMouseReport(MOUSE_LEFT, column, row, pressed = true)
                bridge.sendMouseReport(MOUSE_LEFT, column, row, pressed = false)
                bridge.onSingleTapUp(e)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val rows = view.mEmulator?.mRows?.takeIf { it > 0 } ?: return true
                val lineHeight = view.height.toFloat() / rows
                scrollRemainder += distanceY
                val steps = (scrollRemainder / lineHeight).toInt()
                if (steps == 0) return true
                scrollRemainder -= steps * lineHeight
                val (column, row) = cellAt(e2)
                // Finger moving up (positive distance) scrolls the content up, i.e. wheel down.
                val button = if (steps > 0) MOUSE_WHEEL_DOWN else MOUSE_WHEEL_UP
                repeat(kotlin.math.abs(steps).coerceAtMost(MAX_WHEEL_STEPS)) {
                    bridge.sendMouseReport(button, column, row, pressed = true)
                }
                return true
            }
        }
    ).apply { setIsLongpressEnabled(false) }

    private fun cellAt(event: MotionEvent): Pair<Int, Int> {
        val cell = view.getColumnAndRow(event, false)
        return (cell[0] + 1) to (cell[1] + 1)
    }

    override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
        val emulator = view.mEmulator
        val takeOver = bridge.forceMouse && emulator != null && !view.isSelectingText &&
            (emulator.isAlternateBufferActive || emulator.isMouseTrackingActive) &&
            event.pointerCount == 1 && event.actionMasked != MotionEvent.ACTION_POINTER_DOWN
        if (!takeOver) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scrollRemainder = 0f
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && tapDetected) {
            tapDetected = false
            v.performClick()
        }
        return true
    }

    private companion object {
        const val MOUSE_LEFT = 0
        const val MOUSE_WHEEL_UP = 64
        const val MOUSE_WHEEL_DOWN = 65
        const val MAX_WHEEL_STEPS = 10
    }
}
