package dev.abdus.apps.shimmer.ui.onboarding

import org.junit.Assert.*
import org.junit.Test

class GuideProgressCodecTest {
    @Test fun pendingGestureAndCompletedProgressSurviveRecreation() {
        val state = GuideProgress(step = GuideStep.GESTURE_EDIT.ordinal, ready = true,
            initialTarget = "LOCK", practiceSession = "actual-session", completed = 63, skipped = 4)
        val encoded = GuideProgressCodec.encode(state)
        assertTrue(encoded.contains("\"flowVersion\":4"))
        assertEquals(state, GuideProgressCodec.restore(encoded, false))
    }
    @Test fun defaultSchemaIsAlwaysSavedIncludingFirstStep() {
        val state = GuideProgress()
        assertEquals(state, GuideProgressCodec.restore(GuideProgressCodec.encode(state), false))
    }
    @Test fun oldBooleanAndOrdinalSchemaResetUsingCurrentAcknowledgement() {
        assertEquals(GuideProgress(), GuideProgressCodec.restore(false, true))
        assertEquals(GuideProgress(active = false), GuideProgressCodec.restore("{\"step\":5}", false))
        assertEquals(GuideProgress(), GuideProgressCodec.restore("{\"flowVersion\":2,\"step\":5}", true))
    }
    @Test fun previousSimulationSchemaResetsForActualPractice() {
        assertEquals(GuideProgress(), GuideProgressCodec.restore("{\"flowVersion\":3,\"step\":5}", true))
    }
    @Test fun malformedFutureAndOutOfRangeStatesResetSafely() {
        listOf("bad", "[]", "{\"flowVersion\":99}", "{\"flowVersion\":4,\"step\":99}",
            "{\"flowVersion\":4,\"step\":-1}").forEach {
            assertEquals(GuideProgress(), GuideProgressCodec.restore(it, true))
        }
    }
}
