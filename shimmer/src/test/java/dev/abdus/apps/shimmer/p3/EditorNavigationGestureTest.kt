package dev.abdus.apps.shimmer.p3

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class EditorNavigationGestureTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val calls = mutableListOf<Boolean>()
    private var permitted = true
    private val gesture = EditorNavigationGesture(context, { permitted }) { calls += it }
    private val deadline = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var downTime = 0L

    @After fun close() { gesture.close(); idle(deadline + 1) }

    @Test fun singleTapDoesNotNavigate() { tap(); idle(deadline + 1); assertTrue(calls.isEmpty()) }
    @Test fun doubleWaitsForTheThirdWindowAndDispatchesOnce() {
        taps(2)
        assertTrue(calls.isEmpty())
        idle(deadline + 1)
        assertEquals(listOf(true), calls)
        idle(deadline * 2)
        assertEquals(listOf(true), calls)
    }
    @Test fun tripleDispatchesPreviousWithoutAnEarlierNext() {
        taps(3)
        assertEquals(listOf(false), calls)
        idle(deadline * 2)
        assertEquals(listOf(false), calls)
    }
    @Test fun resetAbortsAnAlreadyPendingDouble() {
        taps(2); gesture.reset(); idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun closeAbortsTheDeadlineAndAllLaterTouches() {
        taps(2); gesture.close(); idle(deadline + 1); taps(3); assertTrue(calls.isEmpty())
    }
    @Test fun cancelledTouchInvalidatesTheSequence() {
        tap(); touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_CANCEL)
        tap(); idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun dragCannotBecomeADoubleTap() {
        tap(); touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_MOVE, 400f)
        touch(MotionEvent.ACTION_UP, 400f); idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun dragReturningToOriginStillRejectsNavigation() {
        tap(); touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_MOVE, 400f)
        touch(MotionEvent.ACTION_MOVE); touch(MotionEvent.ACTION_UP)
        idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun historicalDragReturningToOriginStillRejectsNavigation() {
        tap(); touch(MotionEvent.ACTION_DOWN)
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, 400f, 100f, 0).also {
            it.addBatch(now + 1, 100f, 100f, 1f, 1f, 0)
            gesture.onTouchEvent(it); it.recycle()
        }
        idle(2); touch(MotionEvent.ACTION_UP); idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun longPressInvalidatesThePendingDouble() {
        tap(); touch(MotionEvent.ACTION_DOWN)
        idle(ViewConfiguration.getLongPressTimeout().toLong() + 1)
        touch(MotionEvent.ACTION_UP); idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun joiningSecondFingerClearsThePendingSingleSequence() {
        tap(); touch(MotionEvent.ACTION_DOWN); pointers(MotionEvent.ACTION_POINTER_DOWN)
        pointers(MotionEvent.ACTION_POINTER_UP); touch(MotionEvent.ACTION_UP)
        tap(); idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun twoFingerDoubleDoesNotNavigateOrOpenAnEditor() {
        repeat(2) {
            touch(MotionEvent.ACTION_DOWN); pointers(MotionEvent.ACTION_POINTER_DOWN)
            pointers(MotionEvent.ACTION_POINTER_UP); touch(MotionEvent.ACTION_UP); idle(40)
        }
        idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun remainingFingerAfterMultitouchMustStartANewDown() {
        touch(MotionEvent.ACTION_DOWN); pointers(MotionEvent.ACTION_POINTER_DOWN)
        pointers(MotionEvent.ACTION_POINTER_UP)
        touch(MotionEvent.ACTION_UP); touch(MotionEvent.ACTION_UP)
        idle(deadline + 1); assertTrue(calls.isEmpty())
        taps(2); idle(deadline + 1); assertEquals(listOf(true), calls)
    }
    @Test fun tinySingleFingerMovementIsStillATap() {
        repeat(2) {
            touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_MOVE, 101f)
            touch(MotionEvent.ACTION_UP, 101f); idle(40)
        }
        idle(deadline + 1); assertEquals(listOf(true), calls)
    }
    @Test fun tapsOutsideSystemIntervalDoNotCombine() {
        tap(); idle(deadline + 1); tap(); idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun spatiallySeparatedTapsDoNotCombine() {
        tap(); touch(MotionEvent.ACTION_DOWN, 1000f); touch(MotionEvent.ACTION_UP, 1000f)
        idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun busyAtDeadlineRejectsThePendingDouble() {
        taps(2); permitted = false; idle(deadline + 1); assertTrue(calls.isEmpty())
    }
    @Test fun busyTouchRequiresANewDownAfterCompletion() {
        permitted = false; touch(MotionEvent.ACTION_DOWN)
        permitted = true; touch(MotionEvent.ACTION_UP); tap()
        idle(deadline + 1); assertTrue(calls.isEmpty())
        taps(2); idle(deadline + 1); assertEquals(listOf(true), calls)
    }
    @Test fun freshSequenceAfterResetUsesOriginalThresholds() {
        taps(2); gesture.reset(); taps(3); assertEquals(listOf(false), calls)
    }
    @Test fun twoIndependentDoubleActionsAreNotMergedAfterTheirDeadlines() {
        taps(2); idle(deadline + 1); taps(2); idle(deadline + 1)
        assertEquals(listOf(true, true), calls)
    }
    private fun taps(count: Int) { repeat(count) { tap(); if (it < count - 1) idle(40) } }
    private fun tap() { touch(MotionEvent.ACTION_DOWN); idle(20); touch(MotionEvent.ACTION_UP) }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(action: Int, x: Float = 100f) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        MotionEvent.obtain(downTime, now, action, x, 100f, 0).also { gesture.onTouchEvent(it); it.recycle() }
    }
    private fun pointers(action: Int) {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { index -> MotionEvent.PointerCoords().apply { x = 100f + index * 100f; y = 100f; pressure = 1f; size = 1f } }
        val encoded = action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), encoded, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0).also {
            gesture.onTouchEvent(it); it.recycle()
        }
    }
}
