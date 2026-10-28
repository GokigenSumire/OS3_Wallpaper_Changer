package dev.abdus.apps.shimmer.p10.capability

import dev.abdus.apps.shimmer.p10.EngineRoute
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test

class CapabilityRecorderTest {
    private fun recorder(maxEngines: Int = 64, maxEvents: Int = 256) =
        CapabilityRecorder("test-session", maxEngines, maxEvents)

    private fun CapabilityRecorder.create(
        id: String = "A", marker: WallpaperTarget = WallpaperTarget.HOME,
        flags: Int? = 1, preview: Boolean = false,
    ) = created(id, marker, flags, preview, 0)

    @Test fun newRecorderIsEmptyAndExplicitlyProcessScoped() {
        val state = recorder().snapshot()
        assertEquals("test-session", state.session)
        assertTrue(state.engines.isEmpty())
        assertTrue(state.events.isEmpty())
        assertEquals(0, state.revision)
    }

    @Test fun componentMarkerDoesNotOverrideObservedTarget() {
        val recorder = recorder().apply { create(marker = WallpaperTarget.HOME, flags = 2) }
        val row = recorder.snapshot().engines.single()
        assertEquals(WallpaperTarget.HOME, row.markerTarget)
        assertEquals(WallpaperTarget.LOCK, row.route.formalTarget)
    }

    @Test fun touchRequestSuccessDoesNotClaimEventsWereActuallyForwarded() {
        val recorder = recorder().apply { create(); touchRequested("A", true, 1) }
        val row = recorder.snapshot().engines.single()
        assertTrue(row.touchRequested)
        assertEquals(0, row.rawTouchEvents)
        assertEquals(0, row.doubleTaps)
    }

    @Test fun failedTouchRequestRemainsExplicitlyFalse() {
        val recorder = recorder().apply { create(); failure("A", 1); touchRequested("A", false, 2) }
        assertFalse(recorder.snapshot().engines.single().touchRequested)
        assertEquals(1, recorder.snapshot().engines.single().failureCount)
    }

    @Test fun sharedFlagsNeverBecomeAFormalSingleTarget() {
        val recorder = recorder().apply { create(flags = 3) }
        val row = recorder.snapshot().engines.single()
        assertEquals(EngineRoute.Exposure.SHARED, row.route.exposure)
        assertNull(row.route.formalTarget)
    }

    @Test fun previewHasIndependentIdAndCannotClaimFormalDomain() {
        val recorder = recorder().apply { create("formal"); create("preview", preview = true) }
        val rows = recorder.snapshot().engines
        assertEquals(2, rows.size)
        assertEquals(WallpaperTarget.HOME, rows[0].route.formalTarget)
        assertNull(rows[1].route.formalTarget)
    }

    @Test fun unavailableFlagsRemainUnknown() {
        val recorder = recorder().apply { create(flags = null) }
        assertEquals(EngineRoute.Exposure.UNKNOWN, recorder.snapshot().engines.single().route.exposure)
        assertTrue(recorder.snapshot().events.single().detail.contains("flags=UNKNOWN"))
    }

    @Test fun flagsChangeKeepsCallbackAndSampleDistinct() {
        val recorder = recorder().apply { create(); flagsChanged("A", 2, 3, 1) }
        val state = recorder.snapshot()
        assertEquals(2, state.engines.single().route.observedFlags)
        assertEquals(1, state.engines.single().flagChanges)
        assertEquals("callbackFlags=3 sampledFlags=2", state.events.last().detail)
    }

    @Test fun flagsChangeDoesNotTurnPreviewIntoFormalEngine() {
        val recorder = recorder().apply { create(preview = true); flagsChanged("A", 2, 2, 1) }
        assertNull(recorder.snapshot().engines.single().route.formalTarget)
    }

    @Test fun formalAndPreviewGestureCountsNeverMerge() {
        val recorder = recorder().apply {
            create("formal"); create("preview", preview = true)
            gesture("preview", CapabilityEventKind.DOUBLE_TAP, 1)
        }
        assertEquals(0, recorder.snapshot().engines[0].doubleTaps)
        assertEquals(1, recorder.snapshot().engines[1].doubleTaps)
    }

    @Test fun threeGestureCountersAreIndependent() {
        val recorder = recorder().apply {
            create()
            gesture("A", CapabilityEventKind.DOUBLE_TAP, 1)
            gesture("A", CapabilityEventKind.TRIPLE_TAP, 2)
            gesture("A", CapabilityEventKind.TWO_FINGER_DOUBLE_TAP, 3)
        }
        val row = recorder.snapshot().engines.single()
        assertEquals(1, row.doubleTaps)
        assertEquals(1, row.tripleTaps)
        assertEquals(1, row.twoFingerDoubleTaps)
        assertTrue(recorder.snapshot().events.drop(1).all { it.detail == "diagnosticOnly=true" })
    }

    @Test fun rawTouchesCountEachActionWithoutAccumulatingMoveEventRows() {
        val recorder = recorder().apply {
            create()
            CapabilityTouchAction.entries.forEach { touch("A", it, 2, 1) }
        }
        val row = recorder.snapshot().engines.single()
        assertEquals(7, row.rawTouchEvents)
        assertEquals(1, row.downEvents)
        assertEquals(1, row.upEvents)
        assertEquals(1, row.pointerDownEvents)
        assertEquals(1, row.pointerUpEvents)
        assertEquals(1, row.cancelEvents)
        assertEquals(1, row.moveEvents)
        assertEquals(2, row.maxPointerCount)
        assertEquals(1, recorder.snapshot().events.size)
    }

    @Test fun rawTouchPointerCountsAreBoundedWithoutCoordinates() {
        val recorder = recorder().apply {
            create(); touch("A", CapabilityTouchAction.DOWN, -1, 1)
            touch("A", CapabilityTouchAction.MOVE, 1_000_000, 2)
        }
        assertEquals(32, recorder.snapshot().engines.single().maxPointerCount)
    }

    @Test fun surfacesTrackNumericLifecycleAndClipInvalidDimensions() {
        val recorder = recorder().apply {
            create(); surfaceCreated("A", 1); surfaceChanged("A", -10, 2400, 2)
            drawn("A", 3); surfaceDestroyed("A", 4)
        }
        val row = recorder.snapshot().engines.single()
        assertFalse(row.surfaceReady)
        assertEquals(0, row.width)
        assertEquals(2400, row.height)
        assertEquals(1, row.surfaceCreates)
        assertEquals(1, row.surfaceDestroys)
        assertEquals(1, row.framesDrawn)
    }

    @Test fun destructionClearsActivePresentationButKeepsEvidence() {
        val recorder = recorder().apply {
            create(); visibility("A", true, 1); surfaceCreated("A", 2); destroyed("A", 3)
        }
        val row = recorder.snapshot().engines.single()
        assertFalse(row.alive)
        assertFalse(row.visible)
        assertFalse(row.surfaceReady)
        assertEquals(CapabilityEventKind.DESTROYED, recorder.snapshot().events.last().kind)
    }

    @Test fun callbacksAfterDestroyedAreRejectedWithoutChangingCounts() {
        val recorder = recorder().apply {
            create(); destroyed("A", 1); drawn("A", 2)
            gesture("A", CapabilityEventKind.DOUBLE_TAP, 3)
        }
        val state = recorder.snapshot()
        assertEquals(2, state.rejectedEngineSamples)
        assertEquals(0, state.engines.single().doubleTaps)
        assertEquals(0, state.engines.single().framesDrawn)
    }

    @Test fun duplicateIdCannotOverwriteAnExistingEngine() {
        val recorder = recorder().apply { create(); create(flags = 2) }
        assertEquals(1, recorder.snapshot().engines.single().route.observedFlags)
        assertEquals(1, recorder.snapshot().rejectedEngineSamples)
    }

    @Test fun unknownEngineSamplesAreRejected() {
        val recorder = recorder().apply { touch("missing", CapabilityTouchAction.DOWN, 1, 0) }
        assertTrue(recorder.snapshot().engines.isEmpty())
        assertEquals(1, recorder.snapshot().rejectedEngineSamples)
    }

    @Test fun liveEngineCapacityIsExactly64AndOverflowIsExplicit() {
        val recorder = recorder().apply { repeat(65) { create("E$it") } }
        assertEquals(64, recorder.snapshot().engines.size)
        assertEquals(1, recorder.snapshot().rejectedEngineSamples)
        assertFalse(recorder.snapshot().engines.any { it.id == "E64" })
    }

    @Test fun newEngineEvictsOnlyOldestDeadRecordAtCapacity() {
        val recorder = recorder(maxEngines = 2).apply {
            create("A"); create("B"); destroyed("B", 1); create("C")
        }
        assertEquals(listOf("A", "C"), recorder.snapshot().engines.map { it.id })
        assertEquals(0, recorder.snapshot().rejectedEngineSamples)
    }

    @Test fun eventTailIsExactly256AndDroppedHistoryIsExplicit() {
        val recorder = recorder().apply { create(); repeat(300) { drawn("A", it.toLong()) } }
        val state = recorder.snapshot()
        assertEquals(256, state.events.size)
        assertEquals(45, state.droppedEvents)
        assertEquals(46, state.events.first().sequence)
        assertEquals(301, state.events.last().sequence)
    }

    @Test fun eventSequencesNeverResetWhenHistoryIsCleared() {
        val recorder = recorder().apply { create(); drawn("A", 1); clearEvents(); drawn("A", 2) }
        assertEquals(3, recorder.snapshot().events.single().sequence)
        assertEquals(2, recorder.snapshot().engines.single().framesDrawn)
    }

    @Test fun clearEventsPreservesEngineCountersAndFlags() {
        val recorder = recorder().apply { create(flags = 2); drawn("A", 1) }
        val before = recorder.snapshot()
        recorder.clearEvents()
        assertEquals(before.engines, recorder.snapshot().engines)
        assertTrue(recorder.snapshot().events.isEmpty())
    }

    @Test fun previouslyReturnedSnapshotsStayDetached() {
        val recorder = recorder().apply { create() }
        val before = recorder.snapshot()
        recorder.drawn("A", 1)
        assertEquals(0, before.engines.single().framesDrawn)
        assertEquals(1, before.events.size)
        assertEquals(1, recorder.snapshot().engines.single().framesDrawn)
    }

    @Test fun failureRecordsOnlyAFixedLabelAndCount() {
        val recorder = recorder().apply { create(); failure("A", 1) }
        assertEquals(1, recorder.snapshot().engines.single().failureCount)
        assertEquals("platformOperationFailed=true", recorder.snapshot().events.last().detail)
    }

    @Test fun regressingClockDoesNotRewritePreviousObservation() {
        val recorder = recorder().apply { create(); drawn("A", 10); drawn("A", 9) }
        assertEquals(1, recorder.snapshot().engines.single().framesDrawn)
        assertEquals(10, recorder.snapshot().engines.single().lastObservedAtMs)
        assertEquals(1, recorder.snapshot().rejectedEngineSamples)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonGestureKindCannotIncrementAGestureCounter() {
        recorder().gesture("A", CapabilityEventKind.CREATED, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankSessionIsRejected() { CapabilityRecorder(" ") }

    @Test(expected = IllegalArgumentException::class)
    fun engineCapacityAboveFrozen64IsRejected() { recorder(maxEngines = 65) }

    @Test(expected = IllegalArgumentException::class)
    fun eventCapacityAboveFrozen256IsRejected() { recorder(maxEvents = 257) }

    @Test fun smallerDiagnosticCapacitiesRemainBounded() {
        val recorder = recorder(maxEngines = 1, maxEvents = 1).apply { create(); drawn("A", 1) }
        assertEquals(1, recorder.snapshot().engines.size)
        assertEquals(1, recorder.snapshot().events.size)
        assertEquals(1, recorder.snapshot().droppedEvents)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTimestampIsRejected() { recorder().created("A", WallpaperTarget.HOME, 1, false, -1) }
}
