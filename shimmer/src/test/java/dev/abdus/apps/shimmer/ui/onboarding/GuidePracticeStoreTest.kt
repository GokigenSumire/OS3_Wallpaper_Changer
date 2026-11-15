package dev.abdus.apps.shimmer.ui.onboarding

import android.content.Context
import dev.abdus.apps.shimmer.p0.P0GestureMachine
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GuidePracticeStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun onlyMatchingRealGestureCanCaptureAndOnlyChangedPresentationCompletes() {
        val store = GuidePracticeStore(context) { 1000 }
        val request = store.arm(WallpaperTarget.HOME, GuideStep.GESTURE_NEXT)
        assertNull(store.capture(WallpaperTarget.LOCK, P0GestureMachine.DOUBLE_NEXT, "a"))
        assertNull(store.capture(WallpaperTarget.HOME, P0GestureMachine.TRIPLE_PREV, "a"))
        assertNull(store.capture(WallpaperTarget.HOME, P0GestureMachine.DOUBLE_NEXT, null))
        val ticket = store.capture(WallpaperTarget.HOME, P0GestureMachine.DOUBLE_NEXT, "a")!!
        assertFalse(store.current()!!.completed)
        assertFalse(store.presented(ticket, "a"))
        assertTrue(store.presented(ticket, "b"))
        assertEquals(request.id, store.current()!!.id)
        assertTrue(store.current()!!.completed)
        assertFalse(store.presented(ticket, "c"))
    }
    @Test fun cancellationAndNewSessionRejectOldFrameAcknowledgements() {
        val store = GuidePracticeStore(context) { 1000 }
        val old = store.arm(WallpaperTarget.HOME, GuideStep.GESTURE_PREVIOUS)
        val ticket = store.capture(WallpaperTarget.HOME, P0GestureMachine.TRIPLE_PREV, "b")!!
        store.cancel(old.id)
        assertFalse(store.presented(ticket, "a"))
        val current = store.arm(WallpaperTarget.HOME, GuideStep.GESTURE_PREVIOUS)
        store.cancel(old.id)
        assertEquals(current.id, store.current()!!.id)
        assertFalse(store.presented(ticket, "a"))
    }
    @Test fun editorRequiresMatchingSessionAndTargetAndCannotBeCompletedByFrame() {
        val store = GuidePracticeStore(context) { 1000 }
        val request = store.arm(WallpaperTarget.LOCK, GuideStep.GESTURE_EDIT)
        val ticket = store.capture(WallpaperTarget.LOCK, P0GestureMachine.TWO_FINGER_EDIT, "a")!!
        assertFalse(store.presented(ticket, "b"))
        assertFalse(store.editorOpened(null, WallpaperTarget.LOCK, "a"))
        assertFalse(store.editorOpened(request.id, WallpaperTarget.HOME, "a"))
        assertFalse(store.editorOpened("old", WallpaperTarget.LOCK, "a"))
        assertTrue(store.editorOpened(request.id, WallpaperTarget.LOCK, "a"))
    }
    @Test fun expiredFutureAndMalformedRequestsNeverAcknowledge() {
        var now = 1000L
        val store = GuidePracticeStore(context) { now }
        store.arm(WallpaperTarget.HOME, GuideStep.GESTURE_NEXT)
        val ticket = store.capture(WallpaperTarget.HOME, P0GestureMachine.DOUBLE_NEXT, "a")!!
        now = 601001
        assertNull(store.current())
        assertFalse(store.presented(ticket, "b"))
        now = 999
        assertNull(store.current())
        context.getSharedPreferences("guide_actual_practice", Context.MODE_PRIVATE).edit().putString("request", "bad").commit()
        assertNull(store.current())
    }
    @Test fun practiceSurvivesNewStoreOwnerAndCannotArmUnrelatedStep() {
        val store = GuidePracticeStore(context) { 1000 }
        val request = store.arm(WallpaperTarget.HOME, GuideStep.GESTURE_NEXT)
        assertEquals(request, GuidePracticeStore(context) { 1000 }.current())
        assertThrows(IllegalArgumentException::class.java) { store.arm(WallpaperTarget.HOME, GuideStep.ORDER) }
    }
}
