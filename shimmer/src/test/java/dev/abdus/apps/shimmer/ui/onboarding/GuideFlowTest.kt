package dev.abdus.apps.shimmer.ui.onboarding

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GuideFlowTest {
    @Test fun eachPracticeMapsToOneRealGestureAndCannotAdvanceWithoutProof() {
        val steps = listOf(GuideStep.GESTURE_NEXT, GuideStep.GESTURE_PREVIOUS, GuideStep.GESTURE_EDIT)
        val actions = listOf(dev.abdus.apps.shimmer.p0.P0GestureMachine.DOUBLE_NEXT,
            dev.abdus.apps.shimmer.p0.P0GestureMachine.TRIPLE_PREV, dev.abdus.apps.shimmer.p0.P0GestureMachine.TWO_FINGER_EDIT)
        steps.forEachIndexed { index, step ->
            assertEquals(actions[index], GuideFlow.expectedGesture(step))
            val state = GuideProgress(step = step.ordinal)
            assertSame(state, GuideFlow.advance(state))
        }
        assertNull(GuideFlow.expectedGesture(GuideStep.ORDER))
    }
    @Test fun proofMustMatchSessionTargetStepAndActiveState() {
        val state = GuideProgress(step = GuideStep.GESTURE_NEXT.ordinal, practiceSession = "session")
        val proof = GuidePracticeRequest("session", "HOME", GuideStep.GESTURE_NEXT, 1, true, "b")
        assertTrue(GuideFlow.practiceCompleted(state, proof, "HOME").ready)
        assertFalse(GuideFlow.practiceCompleted(state, proof.copy(completed = false), "HOME").ready)
        assertFalse(GuideFlow.practiceCompleted(state, proof.copy(id = "old"), "HOME").ready)
        assertFalse(GuideFlow.practiceCompleted(state, proof, "LOCK").ready)
        assertFalse(GuideFlow.practiceCompleted(state, proof.copy(step = GuideStep.GESTURE_PREVIOUS), "HOME").ready)
        assertFalse(GuideFlow.practiceCompleted(state.copy(active = false), proof, "HOME").ready)
    }
    @Test fun completingOrSkippingPracticesClearsSessionAndKeepsCounts() {
        val next = GuideProgress(step = GuideStep.GESTURE_NEXT.ordinal, practiceSession = "session")
        val proof = GuidePracticeRequest("session", "HOME", GuideStep.GESTURE_NEXT, 1, true, "b")
        val previous = GuideFlow.advance(GuideFlow.practiceCompleted(next, proof, "HOME"))
        assertEquals(GuideStep.GESTURE_PREVIOUS, previous.current)
        assertNull(previous.practiceSession)
        assertFalse(previous.ready)
        val edit = GuideFlow.advance(previous, skip = true)
        assertEquals(GuideStep.GESTURE_EDIT, edit.current)
        assertEquals(1 shl next.step, edit.completed)
        assertEquals(1 shl previous.step, edit.skipped)
    }
    @Test fun targetPracticeRequiresLeavingAndReturningToOriginalTarget() {
        val initial = GuideFlow.context(GuideProgress(), "LOCK")
        assertFalse(GuideFlow.targetSelected(initial, "LOCK").ready)
        val away = GuideFlow.targetSelected(initial, "HOME")
        assertFalse(away.ready)
        assertTrue(GuideFlow.targetSelected(away, "LOCK").ready)
    }
    @Test fun cancelOrReloadWithoutNewSourceCannotCompletePractice() {
        val source = GuideProgress(step = GuideStep.SOURCE.ordinal)
        val baseline = GuideFlow.sources(source, "HOME", listOf("a"), false)
        val busy = GuideFlow.sources(baseline, "HOME", listOf("a"), true)
        assertFalse(GuideFlow.sources(busy, "HOME", listOf("a"), false).ready)
        assertTrue(GuideFlow.sources(busy, "HOME", listOf("a", "b"), false).ready)
    }
    @Test fun changingTargetCannotMisclassifyItsExistingSourcesAsNewImports() {
        val home = GuideFlow.sources(GuideProgress(step = 1), "HOME", listOf("a"), false)
        val lock = GuideFlow.sources(home, "LOCK", listOf("b", "c"), false)
        assertFalse(lock.ready)
        assertEquals("LOCK", lock.sourceTarget)
    }
    @Test fun existingSourceShortcutRequiresActualNonBusySources() {
        assertFalse(GuideFlow.existingSources(GuideProgress(step = 1)).ready)
        assertFalse(GuideFlow.existingSources(GuideProgress(step = 1, hasSources = true, sourceBusy = true)).ready)
        assertTrue(GuideFlow.existingSources(GuideProgress(step = 1, hasSources = true)).ready)
    }
    @Test fun unrelatedNavigationAndWrongStepEventsAreIgnored() {
        val playback = GuideProgress(step = 2)
        assertFalse(GuideFlow.routeSelected(playback, "settings").ready)
        assertTrue(GuideFlow.routeSelected(playback, "playback").ready)
        assertEquals(playback, GuideFlow.orderApplied(playback))
        assertEquals(playback, GuideFlow.wallpaperPageOpened(playback))
    }
    @Test fun continueCannotBypassPracticeAndSkipIsRecordedSeparately() {
        val first = GuideProgress()
        assertSame(first, GuideFlow.advance(first))
        val skipped = GuideFlow.advance(first, skip = true)
        assertEquals(1, skipped.step)
        assertEquals(0, skipped.completed)
        assertEquals(1, skipped.skipped)
        val done = GuideFlow.advance(skipped.copy(ready = true))
        assertEquals(2, done.completed)
        assertEquals(1, done.skipped)
    }
    @Test fun savedFlowRestoresPendingSourceScopeAndCompletedPractices() {
        val state = GuideProgress(step = 1, initialTarget = "LOCK", visitedOtherTarget = true,
            sourceTarget = "HOME", sourceBaseline = listOf("a", "b"), completed = 1)
        val restored = Json.decodeFromString<GuideProgress>(Json.encodeToString(state))
        assertEquals(state, restored)
        assertFalse(GuideFlow.sources(restored, "HOME", listOf("a", "b"), false).ready)
    }
    @Test fun stoppedFlowRejectsLateOperationResults() {
        val stopped = GuideProgress(active = false, step = 4)
        assertSame(stopped, GuideFlow.wallpaperPageOpened(stopped))
        assertSame(stopped, GuideFlow.advance(stopped, skip = true))
    }
    @Test fun wallpaperPracticeConfirmsOpeningOnlyAtItsOwnStep() {
        assertTrue(GuideFlow.wallpaperPageOpened(GuideProgress(step = 4)).ready)
        assertFalse(GuideFlow.wallpaperPageOpened(GuideProgress(step = 3)).ready)
    }
}
