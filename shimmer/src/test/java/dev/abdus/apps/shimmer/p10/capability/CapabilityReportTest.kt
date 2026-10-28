package dev.abdus.apps.shimmer.p10.capability

import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test

class CapabilityReportTest {
    @Test fun emptyReportDoesNotClaimProductionLockOrHook() {
        val result = CapabilityReport.format(CapabilitySnapshot("session"), "test/64/version")
        assertTrue(result.contains("productionLockPlayback=NOT_EXERCISED_BY_THIS_PROBE"))
        assertTrue(result.contains("rootLockInput=NOT_EXERCISED_BY_THIS_PROBE"))
        assertTrue(result.contains("deviceAcceptance=NOT_RUN"))
        assertTrue(result.contains("compatibilityBaseline=USER_CONFIRMATION_REQUIRED"))
    }
    @Test fun markerDoesNotOverrideObservedTargetAndPreviewDoesNotBecomeFormal() {
        val recorder = CapabilityRecorder("session")
        recorder.created("HOME-1", WallpaperTarget.HOME, 2, true, 1)
        val result = CapabilityReport.format(recorder.snapshot(), "test")
        assertTrue(result.contains("marker=HOME flags=2 exposure=LOCK_ONLY preview=true"))
        assertTrue(result.contains("markerIsNotActualTarget=true"))
    }
    @Test fun sharedFlagsRemainSharedInExport() {
        val recorder = CapabilityRecorder("session")
        recorder.created("LOCK-1", WallpaperTarget.LOCK, 3, false, 1)
        assertTrue(CapabilityReport.format(recorder.snapshot(), "test").contains("flags=3 exposure=SHARED"))
    }
    @Test fun reportPreservesCountersAndNeverHasTouchCoordinates() {
        val recorder = CapabilityRecorder("session")
        recorder.created("HOME-1", WallpaperTarget.HOME, 1, false, 1)
        recorder.touch("HOME-1", CapabilityTouchAction.DOWN, 2, 2)
        recorder.gesture("HOME-1", CapabilityEventKind.TWO_FINGER_DOUBLE_TAP, 3)
        val result = CapabilityReport.format(recorder.snapshot(), "test")
        assertTrue(result.contains("raw=1 down=1"))
        assertTrue(result.contains("maxPointers=2"))
        assertTrue(result.contains("twoFingerDouble=1"))
        assertFalse(result.contains(" x="))
        assertFalse(result.contains(" y="))
        assertFalse(result.contains("content://"))
    }
    @Test fun reportUsesImmutableSnapshotEvenAfterNewEvents() {
        val recorder = CapabilityRecorder("session")
        recorder.created("HOME-1", WallpaperTarget.HOME, 1, false, 1)
        val frozen = recorder.snapshot()
        recorder.touch("HOME-1", CapabilityTouchAction.DOWN, 1, 2)
        assertTrue(CapabilityReport.format(frozen, "test").contains("raw=0"))
        assertTrue(CapabilityReport.format(recorder.snapshot(), "test").contains("raw=1"))
    }
}
