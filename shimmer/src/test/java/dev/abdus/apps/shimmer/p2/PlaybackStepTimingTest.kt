package dev.abdus.apps.shimmer.p2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStepTimingTest {
    private class Clock(var now: Long = 0L) { fun tick(millis: Long) { now += millis * 1_000_000 } }

    @Test fun nestedStatePartsDoNotCountTwiceInTotalOrRemainder() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        clock.tick(5)
        timing.measure(PlaybackTimingStage.STATE_PREPARATION) {
            timing.measureState(PlaybackStateTimingStage.SNAPSHOT_READ) { clock.tick(7) }
            timing.measureCatalogAndRestore { applied -> clock.tick(11); applied(); clock.tick(13) }
            clock.tick(3) // Snapshot output/diagnostic bookkeeping is in the parent only.
        }
        val result = timing.finish(PlaybackTimingResult.COMMITTED)!!
        assertEquals(7L, result.stateSnapshotMillis)
        assertEquals(11L, result.stateCatalogMillis)
        assertEquals(13L, result.stateRestoreMillis)
        assertEquals(34L, result.stateMillis)
        assertEquals(39L, result.totalMillis)
        assertEquals(5L, result.remainderMillis)
        assertTrue(result.diagnosticFields().contains("stateSnapshotMs=7 stateCatalogMs=11 stateRestoreMs=13"))
    }

    @Test fun nestedPreviewHasNoAuthoritativeRestoreAndPreservesReturnValue() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        val marker = Any()
        assertSame(marker, timing.measure(PlaybackTimingStage.STATE_PREPARATION) {
            timing.measureState(PlaybackStateTimingStage.CATALOG_APPLICATION) { clock.tick(4); marker }
        })
        val result = timing.finish(PlaybackTimingResult.COMMITTED)!!
        assertEquals(4L, result.stateMillis)
        assertEquals(4L, result.stateCatalogMillis)
        assertEquals(0L, result.stateRestoreMillis)
        assertEquals(0L, result.remainderMillis)
    }

    @Test fun failedCatalogBeforeCheckpointDoesNotInventRestoreTime() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        val failure = IllegalArgumentException("private catalog")
        assertSame(failure, runCatching {
            timing.measure(PlaybackTimingStage.STATE_PREPARATION) {
                timing.measureCatalogAndRestore<Unit> { clock.tick(6); throw failure }
            }
        }.exceptionOrNull())
        val result = timing.finish(PlaybackTimingResult.ERROR)!!
        assertEquals(6L, result.stateMillis)
        assertEquals(6L, result.stateCatalogMillis)
        assertEquals(0L, result.stateRestoreMillis)
        assertFalse(result.diagnosticFields().contains("private catalog"))
    }

    @Test fun cancelledRestoreRecordsBothPartsAndPropagatesOriginalCancellation() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        val failure = CancellationException("cancel")
        assertSame(failure, runCatching {
            timing.measure(PlaybackTimingStage.STATE_PREPARATION) {
                timing.measureCatalogAndRestore<Unit> { applied -> clock.tick(2); applied(); clock.tick(3); throw failure }
            }
        }.exceptionOrNull())
        val result = timing.finish(PlaybackTimingResult.CANCELLED)!!
        assertEquals(2L, result.stateCatalogMillis)
        assertEquals(3L, result.stateRestoreMillis)
        assertEquals(5L, result.totalMillis)
        assertEquals(0L, result.remainderMillis)
    }

    @Test fun failedSnapshotReadPreservesFailureAndElapsed() {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        val failure = IllegalStateException("read failed")
        assertSame(failure, runCatching {
            timing.measureState<Unit>(PlaybackStateTimingStage.SNAPSHOT_READ) { clock.tick(9); throw failure }
        }.exceptionOrNull())
        assertEquals(9L, timing.finish(PlaybackTimingResult.ERROR)!!.stateSnapshotMillis)
    }

    @Test fun checkpointUsesFirstBoundaryAndBusinessReturnValue() {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        val marker = Any()
        assertSame(marker, timing.measureCatalogAndRestore { applied ->
            clock.tick(2); applied(); clock.tick(3); applied(); clock.tick(4); marker
        })
        val result = timing.finish(PlaybackTimingResult.COMMITTED)!!
        assertEquals(2L, result.stateCatalogMillis)
        assertEquals(7L, result.stateRestoreMillis)
    }

    @Test fun nestedNanosecondsAccumulateBeforeConversion() {
        var now = 0L
        val timing = PlaybackStepTiming { now }
        repeat(4) {
            timing.measureCatalogAndRestore { applied -> now += 250_000; applied(); now += 500_000 }
            timing.measureState(PlaybackStateTimingStage.SNAPSHOT_READ) { now += 250_000 }
        }
        val result = timing.finish(PlaybackTimingResult.COMMITTED)!!
        assertEquals(1L, result.stateCatalogMillis)
        assertEquals(2L, result.stateRestoreMillis)
        assertEquals(1L, result.stateSnapshotMillis)
    }

    @Test fun nestedBackwardClockAndOverflowAreFinite() {
        var now = Long.MIN_VALUE
        val timing = PlaybackStepTiming { now }
        timing.measureCatalogAndRestore { applied -> now = Long.MAX_VALUE; applied(); now = -1L }
        now = 0
        timing.measureCatalogAndRestore { applied -> now = Long.MAX_VALUE; applied() }
        val result = timing.finish(PlaybackTimingResult.ERROR)!!
        assertEquals(Long.MAX_VALUE / 1_000_000, result.stateCatalogMillis)
        assertEquals(0L, result.stateRestoreMillis)
    }

    @Test fun nestedCompletionCannotPolluteAnAlreadyFinishedMeasurement() {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        var recorded: PlaybackStepMeasurement? = null
        timing.measureCatalogAndRestore { applied ->
            clock.tick(2); applied(); recorded = timing.finish(PlaybackTimingResult.CANCELLED); clock.tick(99)
        }
        timing.measureState(PlaybackStateTimingStage.SNAPSHOT_READ) { clock.tick(99) }
        assertEquals(2L, recorded!!.totalMillis)
        assertEquals(0L, recorded!!.stateCatalogMillis)
        assertEquals(0L, recorded!!.stateRestoreMillis)
        assertEquals(0L, recorded!!.stateSnapshotMillis)
        assertNull(timing.finish(PlaybackTimingResult.COMMITTED))
    }

    @Test fun accumulatesStagesAndLeavesUnmeasuredWaitingInRemainder() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        clock.tick(7) // Main and playback mutex wait is not mislabelled as decode.
        timing.measure(PlaybackTimingStage.CATALOG) { clock.tick(11) }
        timing.measure(PlaybackTimingStage.STATE_PREPARATION) { clock.tick(13) }
        timing.measure(PlaybackTimingStage.IMAGE_LOAD) { clock.tick(17) }
        timing.measure(PlaybackTimingStage.IMAGE_LOAD) { clock.tick(19) }
        timing.measure(PlaybackTimingStage.COMMIT) { clock.tick(23) }
        timing.catalog(11890)
        timing.order(PlaybackOrder.MANUAL)
        val result = timing.finish(PlaybackTimingResult.COMMITTED)!!
        assertEquals(11L, result.catalogMillis)
        assertEquals(13L, result.stateMillis)
        assertEquals(36L, result.imageLoadMillis)
        assertEquals(23L, result.commitMillis)
        assertEquals(7L, result.remainderMillis)
        assertEquals(90L, result.totalMillis)
        assertEquals(2, result.imageAttempts)
        assertEquals(11890, result.catalogItems)
        assertEquals(PlaybackOrder.MANUAL, result.order)
    }

    @Test fun preservesTheBusinessReturnValue() = runBlocking {
        val timing = PlaybackStepTiming { 0L }
        val value = Any()
        assertSame(value, timing.measure(PlaybackTimingStage.COMMIT) { value })
    }

    @Test fun failedImageLoadIsCountedAndTheSameFailurePropagates() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        val failure = IllegalStateException("private detail must never enter diagnostic fields")
        val caught = runCatching {
            timing.measure(PlaybackTimingStage.IMAGE_LOAD) { clock.tick(4); throw failure }
        }.exceptionOrNull()
        assertSame(failure, caught)
        val result = timing.finish(PlaybackTimingResult.ERROR)!!
        assertEquals(4L, result.imageLoadMillis)
        assertEquals(1, result.imageAttempts)
        assertFalse(result.diagnosticFields().contains(failure.message!!))
    }

    @Test fun cancellationStillRecordsElapsedAndDoesNotSwallowCancellation() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        val cancelled = CancellationException("cancel")
        assertSame(cancelled, runCatching {
            timing.measure(PlaybackTimingStage.CATALOG) { clock.tick(9); throw cancelled }
        }.exceptionOrNull())
        val result = timing.finish(PlaybackTimingResult.CANCELLED)!!
        assertEquals(9L, result.catalogMillis)
        assertEquals(PlaybackTimingResult.CANCELLED, result.result)
    }

    @Test fun finishIsIdempotentAndTheFirstRealResultWins() {
        val timing = PlaybackStepTiming { 0L }
        assertEquals(PlaybackTimingResult.STALE, timing.finish(PlaybackTimingResult.STALE)!!.result)
        assertNull(timing.finish(PlaybackTimingResult.CANCELLED))
        assertNull(timing.finish(PlaybackTimingResult.COMMITTED))
    }

    @Test fun concurrentCompletionHasExactlyOneWinner() {
        val timing = PlaybackStepTiming { 0L }
        val start = CountDownLatch(1)
        val done = CountDownLatch(12)
        val winners = CopyOnWriteArrayList<PlaybackStepMeasurement>()
        repeat(12) { index ->
            Thread {
                start.await()
                timing.finish(if (index % 2 == 0) PlaybackTimingResult.ERROR else PlaybackTimingResult.CANCELLED)
                    ?.let(winners::add)
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, winners.size)
    }

    @Test fun cancellationBeforeLaunchBodyHasExplicitZeroStageResult() {
        val timing = PlaybackStepTiming { 1L }
        val result = timing.finish(PlaybackTimingResult.CANCELLED)!!
        assertEquals(0L, result.totalMillis)
        assertEquals(0L, result.catalogMillis)
        assertEquals(0, result.imageAttempts)
        assertNull(result.order)
        assertEquals(0, result.catalogItems)
    }

    @Test fun backwardClockDoesNotProduceNegativeValues() = runBlocking {
        val clock = Clock(100_000_000L)
        val timing = PlaybackStepTiming { clock.now }
        timing.measure(PlaybackTimingStage.IMAGE_LOAD) { clock.now = -1 }
        val result = timing.finish(PlaybackTimingResult.NO_CANDIDATE)!!
        assertEquals(0L, result.totalMillis)
        assertEquals(0L, result.imageLoadMillis)
        assertEquals(0L, result.remainderMillis)
    }

    @Test fun zeroClockAndEveryTerminalBranchHaveFiniteNumericFields() {
        PlaybackTimingResult.entries.forEach { outcome ->
            val result = PlaybackStepTiming { 0L }.finish(outcome)!!
            assertEquals(outcome, result.result)
            assertEquals(0L, result.totalMillis)
            assertFalse(result.diagnosticFields().contains("NaN"))
            assertFalse(result.diagnosticFields().contains("Infinity"))
            assertTrue(result.diagnosticFields().contains("notTouchOrGpu=true"))
            assertTrue(result.diagnosticFields().contains("timingBoundary=BUSINESS_TO_MAIN_APPLY"))
        }
    }

    @Test fun nanosecondDurationsAccumulateBeforeConversionToMillis() = runBlocking {
        var now = 0L
        val timing = PlaybackStepTiming { now }
        repeat(4) { timing.measure(PlaybackTimingStage.IMAGE_LOAD) { now += 250_000 } }
        val result = timing.finish(PlaybackTimingResult.COMMITTED)!!
        assertEquals(1L, result.totalMillis)
        assertEquals(1L, result.imageLoadMillis)
        assertEquals(4, result.imageAttempts)
    }

    @Test fun subtractionAndAccumulationSaturateRatherThanOverflow() = runBlocking {
        var now = Long.MIN_VALUE
        val timing = PlaybackStepTiming { now }
        timing.measure(PlaybackTimingStage.IMAGE_LOAD) { now = Long.MAX_VALUE }
        now = 0L
        timing.measure(PlaybackTimingStage.IMAGE_LOAD) { now = Long.MAX_VALUE }
        val result = timing.finish(PlaybackTimingResult.ERROR)!!
        assertEquals(Long.MAX_VALUE / 1_000_000, result.totalMillis)
        assertEquals(Long.MAX_VALUE / 1_000_000, result.imageLoadMillis)
        assertEquals(0L, result.remainderMillis)
    }

    @Test fun laterStageCompletionCannotChangeAnAlreadyFinishedMeasurement() = runBlocking {
        val clock = Clock()
        val timing = PlaybackStepTiming { clock.now }
        var recorded: PlaybackStepMeasurement? = null
        timing.measure(PlaybackTimingStage.IMAGE_LOAD) {
            clock.tick(2)
            recorded = timing.finish(PlaybackTimingResult.CANCELLED)
            clock.tick(50)
        }
        timing.catalog(99)
        timing.order(PlaybackOrder.RANDOM)
        assertEquals(2L, recorded!!.totalMillis)
        assertEquals(0L, recorded!!.imageLoadMillis)
        assertEquals(1, recorded!!.imageAttempts)
        assertEquals(0, recorded!!.catalogItems)
        assertNull(recorded!!.order)
        assertNull(timing.finish(PlaybackTimingResult.COMMITTED))
    }
}
