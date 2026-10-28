package dev.abdus.apps.shimmer.p2

import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTimingTest {
    @Test fun normalPlaybackIsNotMisclassifiedAsSelection() {
        assertFalse(PlaybackStepTiming().finish(PlaybackTimingResult.COMMITTED)!!.selected)
    }
    @Test fun selectedOutcomeIsSeparatedWithoutChangingOldMeasurementFields() {
        val timing = PlaybackStepTiming { 0L }
        timing.markSelected()
        val result = timing.finish(PlaybackTimingResult.CANCELLED)!!
        assertTrue(result.selected)
        assertEquals(0L, result.totalMillis)
        assertTrue(result.diagnosticFields().contains("result=CANCELLED"))
        assertTrue(result.diagnosticFields().contains("notTouchOrGpu=true"))
    }
    @Test fun markingCompletedTimerDoesNotChangeAnAlreadyPublishedMeasurement() {
        val timing = PlaybackStepTiming()
        val result = timing.finish(PlaybackTimingResult.COMMITTED)!!
        timing.markSelected()
        assertFalse(result.selected)
        assertNull(timing.finish(PlaybackTimingResult.COMMITTED))
    }
}
