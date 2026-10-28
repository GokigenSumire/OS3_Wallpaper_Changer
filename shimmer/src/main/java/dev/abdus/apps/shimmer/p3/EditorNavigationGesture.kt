package dev.abdus.apps.shimmer.p3

import android.content.Context
import android.view.MotionEvent
import dev.abdus.apps.shimmer.p0.P0GestureDetector
import dev.abdus.apps.shimmer.p0.P0GestureMachine

/** Editor-only adapter: the desktop recognizer and its system timing/slop remain unchanged. */
internal class EditorNavigationGesture(
    context: Context,
    private val canNavigate: () -> Boolean,
    onNavigate: (forward: Boolean) -> Unit,
) {
    private var requireNewDown = true
    private val detector = P0GestureDetector(context) { gesture ->
        if (canNavigate()) when (gesture) {
            P0GestureMachine.DOUBLE_NEXT -> onNavigate(true)
            P0GestureMachine.TRIPLE_PREV -> onNavigate(false)
            // A two-finger edit gesture belongs only to the desktop, not a nested editor.
            P0GestureMachine.TWO_FINGER_EDIT -> Unit
        }
    }

    fun onTouchEvent(event: MotionEvent) {
        if (!canNavigate() || event.actionMasked == MotionEvent.ACTION_CANCEL ||
            event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP
        ) {
            reset()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requireNewDown = false
        if (requireNewDown) return
        detector.onTouchEvent(event)
    }

    /** A control, modal dialog, busy state or lost focus must cancel an outstanding double. */
    fun reset() {
        detector.reset()
        requireNewDown = true
    }

    fun close() {
        detector.close()
        requireNewDown = true
    }
}
