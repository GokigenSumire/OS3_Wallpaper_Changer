package dev.abdus.apps.shimmer.p0

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRunCoreTest {
    private var now = 1000L
    private fun core(capacity: Int = 1600, engines: Int = 128) = DiagnosticRunCore("PROCESS_A", {
        DiagnosticClockValue(now++, now + 100, now + 50)
    }, maxEvents = capacity, maxEngines = engines)
    private fun state(scope: String = "FORMAL_A", preview: Boolean = false) = EngineDiagnosticState(
        scope, "SHIMMER", preview, true, true, true, true, 1080, 2400, true, false,
    )
    private fun touch(hold: Boolean = false) = DiagnosticTouchData(0, 0, 11, 10, 1,
        listOf(DiagnosticPointer(3, 100f, 200f)), 0, hold)
    private fun snapshot(core: DiagnosticRunCore, source: String = "UI_REFRESH") = core.snapshot("APP target=37", source)
    private fun counters(core: DiagnosticRunCore, scope: String = "FORMAL_A") =
        snapshot(core).engines.single { it.state.scope == scope }.counts

    @Test fun startPreservesRunAndEventsAndStopRetainsEvidence() {
        val core = core(); core.registerEngine(state())
        val token = core.startPreserving(); core.event("FORMAL_A", "ORIGINAL")
        core.stop()
        val stopped = snapshot(core)
        assertFalse(stopped.enabled)
        assertTrue(stopped.content.contains("ORIGINAL"))
        assertFalse(core.event("FORMAL_A", "AFTER_STOP"))
        assertEquals(token, core.startPreserving())
        assertTrue(snapshot(core).content.contains("ORIGINAL"))
    }

    @Test fun resetHasNewRunButPreservesTheActualEngineIdAndZeroCounts() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val before = core.token(); core.routeTouch(core.beginTouch("FORMAL_A", touch()), true, "P2_GESTURE")
        val next = core.resetAndStart()
        assertNotEquals(before, next)
        assertEquals(before.epoch + 1, next.epoch)
        val snap = snapshot(core)
        assertTrue(snap.enabled)
        assertEquals("FORMAL_A", snap.engines.single().state.scope)
        assertEquals(0L, snap.engines.single().counts.received)
        assertTrue(snap.events.any { it.contains("event=RUN_START") })
        assertTrue(snap.events.any { it.contains("sampleSource=RESET_BASELINE") })
        assertFalse(snap.events.any { it.contains("event=ENGINE_CREATE") })
        assertFalse(snap.events.any { it.contains("TOUCH_RECEIVED") })
    }

    @Test fun repeatedResetRemainsDistinctAndDoesNotCollectPreviousCounters() {
        val core = core(); core.registerEngine(state()); core.resetAndStart()
        val previous = core.token()
        core.beginTouch("FORMAL_A", touch())
        val next = core.resetAndStart()
        assertNotEquals(previous, next)
        assertFalse(core.isCurrent(previous)); assertTrue(core.isCurrent(next))
        assertEquals(0L, counters(core).received)
        assertEquals(1, snapshot(core).events.count { it.contains("RUN_START") })
    }

    @Test fun noResetCallIsObservationallyIdenticalToCancelingConfirmation() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val old = snapshot(core)
        // Cancel is a UI choice and deliberately has no core command.
        val after = snapshot(core)
        assertEquals(old.token, after.token)
        assertEquals(old.events, after.events)
        assertEquals(old.engines.map { it.counts }, after.engines.map { it.counts })
        assertEquals(old.enabled, after.enabled)
        assertEquals(old.revision, after.revision)
    }

    @Test fun existingEngineRegisteredWhileDisabledAppearsInStartBaseline() {
        val core = core(); core.registerEngine(state())
        assertTrue(snapshot(core).engines.single().state.alive == true)
        assertTrue(snapshot(core).events.isEmpty())
        core.startPreserving()
        assertTrue(snapshot(core).events.any { it.contains("START_BASELINE") })
        assertEquals(0L, counters(core).received)
    }

    @Test fun editHoldEntranceStillCountsReceivedAndIgnored() {
        val core = core(); core.registerEngine(state().copy(editHold = true)); core.startPreserving()
        val ticket = core.beginTouch("FORMAL_A", touch(true))
        assertTrue(core.routeTouch(ticket, false, "EDIT_HOLD"))
        assertEquals(DiagnosticEngineCounts(received = 1, ignored = 1, ignoredEditHold = 1), counters(core))
        assertTrue(snapshot(core).content.contains("editHold=true"))
        assertTrue(snapshot(core).events.any { it.contains("destination=IGNORED reason=EDIT_HOLD") })
    }

    @Test fun normalEventHasOneEntranceAndOneRoutingEvenIfCalledTwice() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val ticket = core.beginTouch("FORMAL_A", touch())
        assertTrue(core.routeTouch(ticket, true, "P2_GESTURE"))
        assertFalse(core.routeTouch(ticket, true, "P2_GESTURE"))
        assertFalse(core.routeTouch(ticket, false, "EDIT_HOLD"))
        assertEquals(DiagnosticEngineCounts(received = 1, forwarded = 1), counters(core))
        assertEquals(1, snapshot(core).events.count { it.contains("TOUCH_RECEIVED") })
        assertEquals(1, snapshot(core).events.count { it.contains("TOUCH_ROUTE") })
    }

    @Test fun stoppedRunDoesNotCountOrResolveLaterObservations() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val ticket = core.beginTouch("FORMAL_A", touch())
        core.stop(); val before = snapshot(core)
        assertNull(core.beginTouch("FORMAL_A", touch()))
        assertFalse(core.routeTouch(ticket, true, "P2_GESTURE"))
        core.command("FORMAL_A", "android.wallpaper.tap", 0, 0, 0, false)
        assertEquals(before.events, snapshot(core).events)
        assertEquals(DiagnosticEngineCounts(received = 1), counters(core))
    }

    @Test fun oldRoutingTicketCannotContaminateNewRun() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val old = core.beginTouch("FORMAL_A", touch())
        core.resetAndStart()
        assertFalse(core.routeTouch(old, true, "P2_GESTURE"))
        assertEquals(DiagnosticEngineCounts(), counters(core))
    }

    @Test fun formalAndPreviewCountersAreSeparate() {
        val core = core(); core.registerEngine(state()); core.registerEngine(state("PREVIEW_B", true)); core.startPreserving()
        core.routeTouch(core.beginTouch("FORMAL_A", touch()), true, "P2_GESTURE")
        core.routeTouch(core.beginTouch("PREVIEW_B", touch(true)), false, "EDIT_HOLD")
        assertEquals(1L, counters(core).forwarded)
        assertEquals(0L, counters(core).ignored)
        assertEquals(1L, counters(core, "PREVIEW_B").ignored)
        assertEquals(0L, counters(core, "PREVIEW_B").forwarded)
    }

    @Test fun commandClassesAreFixedAndOnlyObserveNoGestureIsSynthesized() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        core.command("FORMAL_A", "android.wallpaper.tap", 1, 2, 3, false)
        core.command("FORMAL_A", "android.wallpaper.secondaryTap", 4, 5, 6, true)
        repeat(200) { core.command("FORMAL_A", "vendor.command$it", 0, 0, 0, false) }
        val count = counters(core)
        assertEquals(1L, count.tapCommands); assertEquals(1L, count.secondaryTapCommands); assertEquals(200L, count.otherCommands)
        assertEquals(0L, count.received)
        assertFalse(snapshot(core).events.any { it.contains("event=GESTURE") })
        assertFalse(snapshot(core).content.contains("action=DOUBLE_NEXT"))
    }

    @Test fun arbitraryCommandDataIsNotPrintedAsBundleOrUriAndRemainsBounded() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        core.command("FORMAL_A", "content://private/pictures/secret.jpg", 3, 4, 5, false)
        core.command("FORMAL_A", "a".repeat(20000), 0, 0, 0, false)
        val snap = snapshot(core)
        assertEquals(2L, counters(core).otherCommands)
        assertFalse(snap.content.contains("secret.jpg"))
        assertTrue(snap.events.all { it.length <= 640 })
        assertTrue(snap.content.contains("NON_IDENTIFIER"))
    }

    @Test fun ringEvictionDoesNotResetCumulativeCounters() {
        val core = core(capacity = 8); core.registerEngine(state()); core.startPreserving()
        repeat(100) { core.routeTouch(core.beginTouch("FORMAL_A", touch()), true, "P2_GESTURE") }
        val snap = snapshot(core)
        assertEquals(8, snap.events.size)
        assertTrue(snap.overwrittenRecords >= 194)
        assertEquals(100L, counters(core).received)
        assertEquals(100L, counters(core).forwarded)
    }

    @Test fun destroyedEngineIsNotResampledAsAliveAfterStop() {
        val core = core(); core.registerEngine(state()); core.startPreserving(); core.stop()
        val before = snapshot(core).events
        core.unregisterEngine("FORMAL_A")
        assertEquals(false, snapshot(core).engines.single().state.alive)
        assertEquals("OWNER_DESTROY", snapshot(core).engines.single().sampleSource)
        assertEquals(before, snapshot(core).events)
        core.startPreserving()
        assertFalse(snapshot(core).events.takeLast(2).any { it.contains("alive=true") })
    }

    @Test fun repeatedDestroyAndPostDestroyOwnerUpdateDoNotReviveEngine() {
        val core = core(); core.registerEngine(state()); core.startPreserving(); core.unregisterEngine("FORMAL_A")
        assertFalse(core.updateEngineState(state()))
        val before = snapshot(core).events
        core.unregisterEngine("FORMAL_A")
        assertEquals(before, snapshot(core).events)
        assertEquals(false, snapshot(core).engines.single().state.alive)
    }

    @Test fun delayedOldEngineSamplesAndUiTokensAreRejected() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val old = core.token(); core.resetAndStart()
        assertFalse(core.updateEngineState(state().copy(editHold = true), old))
        assertFalse(core.event("FORMAL_A", "OLD_UI_RESULT", expected = old))
        assertFalse(core.isCurrent(old))
        assertEquals(false, snapshot(core).engines.single().state.editHold)
    }

    @Test fun unconditionalOwnerUpdatesStayCurrentWhenDiagnosisIsDisabled() {
        val core = core(); core.registerEngine(state()); core.startPreserving(); core.stop()
        assertTrue(core.updateEngineState(state().copy(visible = false)))
        core.resetAndStart()
        assertEquals(false, snapshot(core).engines.single().state.visible)
        assertTrue(snapshot(core).events.any { it.contains("RESET_BASELINE") && it.contains("visible=false") })
    }

    @Test fun actualOwnerCacheCanUpdateWithoutRelabelingAnOldDelayedSampleAsNewRun() {
        val core = core(); core.registerEngine(state().copy(rendererReady = false)); core.startPreserving()
        val old = core.token(); core.resetAndStart()
        val events = snapshot(core).events
        val real = state().copy(rendererReady = true)
        assertTrue(core.updateEngineCache(real))
        assertFalse(core.updateEngineState(real, old))
        assertEquals(events, snapshot(core).events)
        assertEquals(true, snapshot(core).engines.single().state.rendererReady)
        core.resetAndStart()
        assertTrue(snapshot(core).events.any { it.contains("RESET_BASELINE") && it.contains("rendererReady=true") })
    }

    @Test fun stoppedExportPreservesOwnerCacheTimeInsteadOfRelabelingItAsExportTime() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val ownerAt = snapshot(core).engines.single().ownerSampledAt
        core.stop(); now += 20000
        val exported = snapshot(core, "EXPORT_METADATA")
        assertEquals(ownerAt, exported.engines.single().ownerSampledAt)
        assertNotEquals(ownerAt, exported.engines.single().sampledAt)
        assertTrue(exported.summary.contains("metadataSampleIsNotOwnerRefresh=true"))
        assertTrue(exported.summary.contains("ownerCacheWallMs=${ownerAt!!.wallMs}"))
    }

    @Test fun resetBaselineUsesLatestOwnerCacheButKeepsItsActualOwnerTimeDistinct() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        core.updateEngineCache(state().copy(visible = false))
        val ownerAt = snapshot(core).engines.single().ownerSampledAt
        now += 20000; core.resetAndStart()
        val snap = snapshot(core)
        assertEquals(ownerAt, snap.engines.single().ownerSampledAt)
        assertEquals(false, snap.engines.single().state.visible)
        assertNotEquals(ownerAt, snap.engines.single().sampledAt)
        assertTrue(snap.events.any { it.contains("RESET_BASELINE") && it.contains("ownerCacheWallMs=${ownerAt!!.wallMs}") })
    }

    @Test fun unknownStatesStayUnknownAndAreNotFalseOrZero() {
        val core = core(); core.registerEngine(EngineDiagnosticState("UNKNOWN_A", "SHIMMER")); core.startPreserving()
        val snap = snapshot(core)
        assertTrue(snap.summary.contains("touchRequested=UNKNOWN"))
        assertTrue(snap.summary.contains("surfaceValid=UNKNOWN"))
        assertTrue(snap.summary.contains("width=UNKNOWN"))
        assertNull(snap.engines.single().state.rendererReady)
    }

    @Test fun snapshotsAreDetachedReadOnlyAndFileNameMatchesItsOwnRun() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val old = snapshot(core, "EXPORT_METADATA")
        core.resetAndStart(); core.command("FORMAL_A", "android.wallpaper.tap", 1, 1, 0, false)
        assertTrue(old.fileName.contains(old.runId)); assertTrue(old.fileName.contains(old.cutoffWallMs.toString()))
        assertTrue(old.content.contains("diagnosticRunId=${old.runId}"))
        assertEquals(0L, old.engines.single().counts.commands)
        assertNotEquals(old.runId, core.token().runId)
        try { (old.events as MutableList<String>).add("POLLUTED"); fail("must be read-only") }
        catch (_: UnsupportedOperationException) { }
        try { (old.engines as MutableList<DiagnosticEngineSnapshot>).clear(); fail("must be read-only") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun exportAfterStopIsMetadataOnlyAndCannotEnableOrEraseRecords() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        core.routeTouch(core.beginTouch("FORMAL_A", touch()), true, "P2_GESTURE"); core.stop()
        val before = snapshot(core)
        val exported = snapshot(core, "EXPORT_METADATA")
        assertFalse(exported.enabled)
        assertEquals(before.events, exported.events)
        assertEquals(before.revision, exported.revision)
        assertTrue(exported.engines.single().sampleSource.startsWith("EXPORT_METADATA:OWNER_CACHE"))
        assertEquals(before.engines.single().counts, exported.engines.single().counts)
    }

    @Test fun freshProcessRestoresMarkedHistoryButNeverThePreviousCountersAsLive() {
        val old = core(); old.registerEngine(state()); old.startPreserving()
        old.routeTouch(old.beginTouch("FORMAL_A", touch()), true, "P2_GESTURE")
        val stored = old.storedSnapshot()
        val fresh = DiagnosticRunCore("PROCESS_B", { DiagnosticClockValue(9000, 8000, 7000) })
        fresh.restoreHistory(stored.tail, stored.token.runId, true)
        val snap = snapshot(fresh)
        assertEquals("PROCESS_B", snap.processSession)
        assertNotEquals(stored.token.runId, snap.runId)
        assertTrue(snap.restoredRecords > 0)
        assertTrue(snap.summary.contains("restoredCounters=UNKNOWN"))
        assertTrue(snap.content.contains("RESTORED_HISTORY_BEGIN"))
        assertTrue(snap.content.contains("notCurrentObservation=true"))
        assertTrue(snap.content.contains("CURRENT_RUN_EVENTS diagnosticRunId=${snap.runId}"))
        assertTrue(snap.engines.isEmpty())
        fresh.registerEngine(state("PROCESS_B_ENGINE")); fresh.startPreserving()
        assertEquals(0L, snapshot(fresh).engines.single().counts.received)
    }

    @Test fun restoredStoppedHistoryStaysStoppedUntilExplicitStartAndResetRemovesIt() {
        val core = core(); core.restoreHistory("old one\nold two", "OLD_RUN", false)
        assertFalse(snapshot(core).enabled)
        assertEquals(2, snapshot(core).restoredRecords)
        core.startPreserving(); assertEquals(2, snapshot(core).restoredRecords)
        core.resetAndStart(); assertEquals(0, snapshot(core).restoredRecords)
        assertFalse(snapshot(core).content.contains("old one"))
    }

    @Test fun oldWriteAcknowledgmentCannotMarkNewRunSavedOrClearItsFailure() {
        val core = core(); core.startPreserving(); val old = core.storedSnapshot()
        core.resetAndStart(); core.event("APP", "NEW_EVENT")
        core.markPersistence(old.token, old.revision, true)
        assertEquals("PENDING", snapshot(core).persistenceStatus)
        val current = core.storedSnapshot()
        core.markPersistence(current.token, current.revision, false, "REAL_FAIL")
        core.markPersistence(old.token, old.revision, true)
        assertEquals("FAILED", snapshot(core).persistenceStatus)
        core.markPersistence(current.token, current.revision, true)
        assertEquals("SAVED", snapshot(core).persistenceStatus)
    }

    @Test fun oldRevisionAckDoesNotClaimNewEventsHaveBeenSaved() {
        val core = core(); core.startPreserving(); val first = core.storedSnapshot()
        core.event("APP", "NEW_EVENT"); core.markPersistence(first.token, first.revision, true)
        assertEquals("PENDING", snapshot(core).persistenceStatus)
        val second = core.storedSnapshot(); core.markPersistence(second.token, second.revision, true)
        assertEquals("SAVED", snapshot(core).persistenceStatus)
    }

    @Test fun observationTablesAndRestoredLinesAreAllBounded() {
        val core = core(capacity = 4, engines = 2)
        core.restoreHistory((1..30).joinToString("\n") { "legacy-$it" }, null, false)
        assertEquals(4, snapshot(core).events.size)
        core.registerEngine(state("A")); core.registerEngine(state("B")); core.registerEngine(state("C"))
        core.startPreserving()
        assertEquals(2, snapshot(core).engines.size)
        assertNull(core.beginTouch("C", touch()))
        assertTrue(snapshot(core).summary.contains("engineCapacityDrops="))
    }

    @Test fun touchPointerInputsAreReadImmediatelyNotHeldForAsyncMutation() {
        val core = core(); core.registerEngine(state()); core.startPreserving()
        val pointers = mutableListOf(DiagnosticPointer(4, 90f, 80f))
        core.beginTouch("FORMAL_A", touch().copy(pointers = pointers))
        pointers[0] = DiagnosticPointer(999, 0f, 0f)
        assertTrue(snapshot(core).content.contains("pointers=4:90,80"))
        assertFalse(snapshot(core).content.contains("999:0,0"))
    }

    @Test fun resetAndExportHaveNoBusinessObjectsOrSideEffectsToMutate() {
        val playback = mutableListOf("current", "history", "manual", "editHold", "GL")
        val before = playback.toList()
        val core = core(); core.registerEngine(state().copy(editHold = true)); core.startPreserving()
        core.resetAndStart(); snapshot(core, "EXPORT_METADATA"); core.stop(); snapshot(core)
        assertEquals(before, playback)
        assertEquals(true, snapshot(core).engines.single().state.editHold)
    }
}
