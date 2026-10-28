package dev.abdus.apps.shimmer.p0

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DiagnosticSerialWriterTest {
    private class ControlledScheduler : DiagnosticWriteScheduler {
        val tasks = mutableListOf<Runnable>()
        val canceled = mutableListOf<Runnable>()
        @Synchronized override fun schedule(task: Runnable, delayMillis: Long) { tasks.add(task) }
        @Synchronized override fun cancel(task: Runnable) { canceled.add(task); tasks.remove(task) }
        @Synchronized fun next(): Runnable = tasks.removeAt(0)
        @Synchronized fun size(): Int = tasks.size
        fun runNext() = next().run()
    }
    private fun core() = DiagnosticRunCore("PROCESS", { DiagnosticClockValue(1000, 2000, 3000) })
    private fun writer(scheduler: ControlledScheduler, core: DiagnosticRunCore,
        store: (DiagnosticStoredSnapshot) -> Boolean) = DiagnosticSerialWriter(scheduler,
        core::storedSnapshot, store, acknowledgment = { data, success, failure ->
            core.markPersistence(data.token, data.revision, success, failure)
        })
    private fun await(latch: CountDownLatch) = assertTrue("controlled checkpoint must be reached", latch.await(5, TimeUnit.SECONDS))

    @Test fun requestsCoalesceToOneLatestSnapshotInsteadOfUnboundedQueue() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        val saved = mutableListOf<DiagnosticStoredSnapshot>()
        val writer = writer(scheduler, core) { saved.add(it); true }
        repeat(1000) { core.event("APP", "EVENT_$it"); writer.request() }
        assertEquals(1, scheduler.size())
        scheduler.runNext()
        assertEquals(1, saved.size)
        assertTrue(saved.single().tail.contains("EVENT_999"))
        assertEquals("SAVED", core.snapshot("APP").persistenceStatus)
    }

    @Test fun queuedOldWriteCanceledByResetCannotClearTheNewPendingTask() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving(); core.event("APP", "OLD_EVENT")
        val saved = mutableListOf<DiagnosticStoredSnapshot>()
        val writer = writer(scheduler, core) { saved.add(it); true }
        writer.request(); val old = scheduler.tasks.single()
        core.resetAndStart(); core.event("APP", "NEW_EVENT"); writer.request(immediate = true)
        old.run() // Simulate a late canceled callback, not merely removeCallbacks success.
        assertEquals(1, scheduler.size()); assertTrue(saved.isEmpty())
        scheduler.runNext()
        assertEquals(core.token(), saved.single().token)
        assertTrue(saved.single().tail.contains("NEW_EVENT")); assertFalse(saved.single().tail.contains("OLD_EVENT"))
    }

    @Test fun oldQueuedTaskWithoutExplicitCancelStillCapturesLatestGeneration() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving(); core.event("APP", "OLD_EVENT")
        val saved = mutableListOf<DiagnosticStoredSnapshot>()
        val writer = writer(scheduler, core) { saved.add(it); true }
        writer.request(); core.resetAndStart(); core.event("APP", "NEW_EVENT"); writer.request()
        scheduler.runNext()
        assertEquals(1, saved.size); assertEquals(core.token(), saved.single().token)
        assertFalse(saved.single().tail.contains("OLD_EVENT"))
    }

    @Test fun startedOldCommitCompletesBeforeNewCommitAndCannotOverwriteCompletedReset() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving(); core.event("APP", "OLD_EVENT")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val physical = AtomicReference<DiagnosticStoredSnapshot>()
        val calls = AtomicInteger()
        val writer = writer(scheduler, core) { data ->
            if (calls.getAndIncrement() == 0) { entered.countDown(); await(release) }
            physical.set(data); true
        }
        writer.request(); val oldTask = scheduler.next()
        val worker = thread(name = "controlled-old-write") { oldTask.run() }
        await(entered)
        core.resetAndStart(); core.event("APP", "NEW_EVENT"); writer.request(immediate = true)
        assertEquals("PENDING", core.snapshot("APP").persistenceStatus)
        assertEquals(0, scheduler.size()) // Old I/O is serial; no simultaneous reset commit.
        release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
        assertEquals("PENDING", core.snapshot("APP").persistenceStatus) // Old ack is rejected.
        assertEquals(1, scheduler.size()); scheduler.runNext()
        assertEquals(core.token(), physical.get().token)
        assertTrue(physical.get().tail.contains("NEW_EVENT")); assertFalse(physical.get().tail.contains("OLD_EVENT"))
        assertEquals("SAVED", core.snapshot("APP").persistenceStatus)
    }

    @Test fun mainLikeObserverDoesNotBlockOnInFlightDiskIoOrLoseNewFirstRecords() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger(); val saved = AtomicReference<DiagnosticStoredSnapshot>()
        val writer = writer(scheduler, core) { data ->
            if (calls.getAndIncrement() == 0) { entered.countDown(); await(release) }
            saved.set(data); true
        }
        writer.request(); val oldTask = scheduler.next()
        val io = thread { oldTask.run() }; await(entered)
        val observed = CountDownLatch(1)
        val mainLike = thread {
            core.resetAndStart(); core.event("APP", "FIRST_NEW_EVENT"); writer.request(immediate = true)
            core.snapshot("APP"); observed.countDown()
        }
        await(observed) // Must complete while the disk latch is still closed.
        mainLike.join(5000); release.countDown(); io.join(5000)
        scheduler.runNext()
        assertTrue(saved.get().tail.contains("FIRST_NEW_EVENT"))
        assertEquals(core.token(), saved.get().token)
    }

    @Test fun successiveResetsDuringOldCommitPublishOnlyTheLatestCompleteTail() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val saved = mutableListOf<DiagnosticStoredSnapshot>()
        val calls = AtomicInteger()
        val writer = writer(scheduler, core) {
            if (calls.getAndIncrement() == 0) { entered.countDown(); await(release) }
            saved.add(it); true
        }
        writer.request(); val oldTask = scheduler.next(); val io = thread { oldTask.run() }; await(entered)
        repeat(20) { core.resetAndStart(); core.event("APP", "RUN_EVENT_$it"); writer.request(immediate = true) }
        release.countDown(); io.join(5000)
        assertEquals(1, scheduler.size()); scheduler.runNext()
        assertEquals(2, saved.size)
        assertEquals(core.token(), saved.last().token)
        assertTrue(saved.last().tail.contains("RUN_EVENT_19"))
        assertFalse(saved.last().tail.contains("RUN_EVENT_18"))
    }

    @Test fun duplicateTaskInvocationCannotStartOverlappingWrites() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val writer = writer(scheduler, core) { entered.countDown(); await(release); calls.incrementAndGet(); true }
        writer.request(); val task = scheduler.next(); val io = thread { task.run() }; await(entered)
        task.run(); assertEquals(0, calls.get())
        release.countDown(); io.join(5000); task.run()
        assertEquals(1, calls.get())
    }

    @Test fun commitFalseHasActualFailureFeedbackAndExplicitRetryCanRecover() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        var accepted = false
        val writer = writer(scheduler, core) { accepted }
        writer.request(); scheduler.runNext()
        assertEquals("FAILED", core.snapshot("APP").persistenceStatus)
        assertTrue(core.snapshot("APP").summary.contains("COMMIT_FAILED"))
        accepted = true; writer.request(immediate = true); scheduler.runNext()
        assertEquals("SAVED", core.snapshot("APP").persistenceStatus)
    }

    @Test fun exceptionIsVisibleAndDoesNotStickTheSerialWriterRunningForever() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        var fail = true
        val writer = writer(scheduler, core) { if (fail) throw IllegalStateException("private path never logged"); true }
        writer.request(); scheduler.runNext()
        val failed = core.snapshot("APP")
        assertEquals("FAILED", failed.persistenceStatus)
        assertTrue(failed.summary.contains("IllegalStateException")); assertFalse(failed.summary.contains("private path"))
        fail = false; writer.request(); scheduler.runNext()
        assertEquals("SAVED", core.snapshot("APP").persistenceStatus)
    }

    @Test fun newerEventDuringCommitGetsItsOwnFollowingSnapshotAndOldAckIsNotEnough() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val saved = mutableListOf<DiagnosticStoredSnapshot>()
        val writer = writer(scheduler, core) {
            if (calls.getAndIncrement() == 0) { entered.countDown(); await(release) }
            saved.add(it); true
        }
        writer.request(); val task = scheduler.next(); val io = thread { task.run() }; await(entered)
        core.event("APP", "NEWEST_SAME_RUN"); writer.request()
        release.countDown(); io.join(5000)
        assertEquals("PENDING", core.snapshot("APP").persistenceStatus)
        scheduler.runNext()
        assertEquals("SAVED", core.snapshot("APP").persistenceStatus)
        assertTrue(saved.last().tail.contains("NEWEST_SAME_RUN")); assertFalse(saved.first().tail.contains("NEWEST_SAME_RUN"))
    }

    @Test fun snapshotCallbackFaultIsReportedAndDoesNotStrandPendingWriter() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        var shouldFail = true; var reported = ""; var commits = 0
        val initialToken = core.token()
        var reportedToken: DiagnosticToken? = null
        val writer = DiagnosticSerialWriter(scheduler, snapshot = {
            if (shouldFail) throw IllegalStateException("not logged")
            core.storedSnapshot()
        }, store = { commits++; true }, acknowledgment = { _, _, _ -> },
            protocolFailure = { token, revision, failure ->
                reportedToken = token; reported = failure
                if (token != null) core.markPersistence(token, revision ?: -1L, false, failure)
            }, captureRunToken = core::token)
        writer.request(); scheduler.runNext()
        assertEquals("IllegalStateException", reported); assertEquals(0, commits)
        assertEquals(initialToken, reportedToken)
        assertEquals("FAILED", core.snapshot("APP").persistenceStatus)
        shouldFail = false; writer.request(immediate = true); scheduler.runNext()
        assertEquals(1, commits)
    }

    @Test fun acknowledgmentCallbackFaultIsReportedAndDoesNotStrandPendingWriter() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        var shouldFail = true; var reported = ""; var commits = 0
        val writer = DiagnosticSerialWriter(scheduler, core::storedSnapshot, store = { commits++; true },
            acknowledgment = { _, _, _ -> if (shouldFail) throw IllegalArgumentException("not logged") },
            protocolFailure = { _, _, failure -> reported = failure }, captureRunToken = core::token)
        writer.request(); scheduler.runNext()
        assertEquals("IllegalArgumentException", reported)
        shouldFail = false; writer.request(); scheduler.runNext()
        assertEquals(2, commits)
    }

    @Test fun oldAcknowledgmentFaultAfterResetCannotMarkNewRunFailedOrPreventItsCommit() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        val old = core.token()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reported = AtomicReference<DiagnosticToken>()
        val writer = DiagnosticSerialWriter(scheduler, core::storedSnapshot, store = { true },
            acknowledgment = { data, success, failure ->
                if (data.token == old) {
                    entered.countDown(); await(release)
                    throw IllegalStateException("old acknowledgment")
                }
                core.markPersistence(data.token, data.revision, success, failure)
            }, protocolFailure = { token, revision, failure ->
                reported.set(token)
                if (token != null) core.markPersistence(token, revision ?: -1L, false, failure)
            }, captureRunToken = core::token)
        writer.request(); val task = scheduler.next(); val io = thread { task.run() }; await(entered)
        core.resetAndStart(); core.event("APP", "NEW_EVENT"); writer.request(immediate = true)
        release.countDown(); io.join(5000); assertFalse(io.isAlive)
        assertEquals(old, reported.get())
        assertEquals("PENDING", core.snapshot("APP").persistenceStatus)
        assertEquals(1, scheduler.size()); scheduler.runNext()
        assertEquals("SAVED", core.snapshot("APP").persistenceStatus)
    }

    @Test fun oldSnapshotFaultAfterResetUsesBeforeCallbackTokenAndDoesNotFailNewRun() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        val old = core.token()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger(); val reported = AtomicReference<DiagnosticToken>()
        val writer = DiagnosticSerialWriter(scheduler, snapshot = {
            if (calls.getAndIncrement() == 0) {
                entered.countDown(); await(release)
                throw IllegalArgumentException("old snapshot")
            }
            core.storedSnapshot()
        }, store = { true }, acknowledgment = { data, success, failure ->
            core.markPersistence(data.token, data.revision, success, failure)
        }, protocolFailure = { token, revision, failure ->
            reported.set(token)
            if (token != null) core.markPersistence(token, revision ?: -1L, false, failure)
        }, captureRunToken = core::token)
        writer.request(); val task = scheduler.next(); val io = thread { task.run() }; await(entered)
        core.resetAndStart(); core.event("APP", "FIRST_NEW_EVENT"); writer.request(immediate = true)
        release.countDown(); io.join(5000); assertFalse(io.isAlive)
        assertEquals(old, reported.get())
        assertEquals("PENDING", core.snapshot("APP").persistenceStatus)
        scheduler.runNext()
        assertEquals("SAVED", core.snapshot("APP").persistenceStatus)
    }

    @Test fun unknownFailureAttributionDoesNotPretendItBelongsToCurrentRun() {
        val scheduler = ControlledScheduler(); val core = core(); core.startPreserving()
        var reported = "NOT_CALLED"; var reportedToken: DiagnosticToken? = core.token()
        val writer = DiagnosticSerialWriter(scheduler, snapshot = { throw IllegalStateException("no token") },
            store = { true }, acknowledgment = { _, _, _ -> }, protocolFailure = { token, _, type ->
                reportedToken = token; reported = type
            }) // The optional capture function cannot assign an invented current run.
        writer.request(); scheduler.runNext()
        assertNull(reportedToken); assertEquals("IllegalStateException", reported)
        assertEquals("PENDING", core.snapshot("APP").persistenceStatus)
    }
}
