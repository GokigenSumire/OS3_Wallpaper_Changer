package dev.abdus.apps.shimmer.p0

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration

/** Main-thread Android adapter; the callback only signals a P0 diagnostic action. */
class P0GestureDetector(context: Context, onGesture: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val viewConfiguration = ViewConfiguration.get(context)
    private val thresholds = P0GestureMachine.Thresholds(
        doubleTapTimeoutMillis = ViewConfiguration.getDoubleTapTimeout().toLong(),
        longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
        pointerJoinTimeoutMillis = ViewConfiguration.getTapTimeout().toLong(),
        touchSlop = viewConfiguration.scaledTouchSlop.toFloat(),
        doubleTapSlop = viewConfiguration.scaledDoubleTapSlop.toFloat(),
    )
    private var closed = false
    private val machine = P0GestureMachine(thresholds) { if (!closed) onGesture(it) }
    private val timeout = Runnable {
        if (!closed) {
            machine.advanceTime(SystemClock.uptimeMillis())
            scheduleDeadline()
        }
    }

    val thresholdSummary: String =
        "doubleTapMs=${thresholds.doubleTapTimeoutMillis}; " +
            "longPressMs=${thresholds.longPressTimeoutMillis}; " +
            "pointerJoinMs=${thresholds.pointerJoinTimeoutMillis}; " +
            "touchSlopPx=${thresholds.touchSlop}; doubleTapSlopPx=${thresholds.doubleTapSlop}"

    fun onTouchEvent(event: MotionEvent) {
        checkMainThread()
        if (closed) return
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> P0GestureMachine.Action.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> P0GestureMachine.Action.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> P0GestureMachine.Action.MOVE
            MotionEvent.ACTION_POINTER_UP -> P0GestureMachine.Action.POINTER_UP
            MotionEvent.ACTION_UP -> P0GestureMachine.Action.UP
            MotionEvent.ACTION_CANCEL -> P0GestureMachine.Action.CANCEL
            else -> return
        }
        // Historical MOVE samples must also reject a drag that returned close to its start.
        if (action == P0GestureMachine.Action.MOVE) {
            for (historyIndex in 0 until event.historySize) {
                machine.onTouchEvent(
                    P0GestureMachine.Touch(
                        P0GestureMachine.Action.MOVE,
                        event.getHistoricalEventTime(historyIndex),
                        (0 until event.pointerCount).map { pointerIndex ->
                            P0GestureMachine.Pointer(
                                event.getPointerId(pointerIndex),
                                event.getHistoricalX(pointerIndex, historyIndex),
                                event.getHistoricalY(pointerIndex, historyIndex),
                            )
                        },
                    ),
                )
            }
        }
        machine.onTouchEvent(
            P0GestureMachine.Touch(
                action,
                event.eventTime,
                (0 until event.pointerCount).map {
                    P0GestureMachine.Pointer(event.getPointerId(it), event.getX(it), event.getY(it))
                },
                if (event.pointerCount == 0) -1 else event.getPointerId(event.actionIndex),
            ),
        )
        scheduleDeadline()
    }

    fun reset() {
        checkMainThread()
        handler.removeCallbacks(timeout)
        machine.reset()
    }

    fun close() {
        checkMainThread()
        closed = true
        reset()
    }

    private fun scheduleDeadline() {
        handler.removeCallbacks(timeout)
        if (!closed) machine.nextDeadlineMillis?.let { handler.postAtTime(timeout, it) }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "P0 gesture probe belongs to the main thread" }
    }
}
