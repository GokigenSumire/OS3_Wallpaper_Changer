package dev.abdus.apps.shimmer.p2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackCatalogTimingTest {
    private class Clock(var now: Long = 0) { fun tick(ms: Long) { now += ms * 1_000_000 } }

    @Test fun nestedPartsDoNotChangeParentTotalOrRemainder() = runBlocking {
        val clock = Clock()
        val parent = PlaybackStepTiming { clock.now }
        clock.tick(2)
        parent.measure(PlaybackTimingStage.CATALOG) {
            parent.catalogTiming.measure(PlaybackCatalogTimingStage.DECISIONS) { clock.tick(3) }
            parent.catalogTiming.measure(PlaybackCatalogTimingStage.DATABASE) { clock.tick(5) }
            parent.catalogTiming.measure(PlaybackCatalogTimingStage.MAPPING) { clock.tick(7) }
            clock.tick(11) // Dispatch / boundary overhead is only in the parent.
        }
        val result = parent.finish(PlaybackTimingResult.COMMITTED)!!
        assertEquals(28L, result.totalMillis)
        assertEquals(26L, result.catalogMillis)
        assertEquals(2L, result.remainderMillis)
        assertEquals(PlaybackCatalogMeasurement(3, 5, 7), result.catalogParts)
        assertFalse(result.diagnosticFields().contains("catalogDatabaseMs"))
        assertTrue(result.catalogParts!!.diagnosticFields().contains("nestedIn=catalogMs"))
    }

    @Test fun noAttemptDoesNotInventACatalogEvent() {
        assertNull(PlaybackCatalogTiming().finish())
        assertNull(PlaybackStepTiming().finish(PlaybackTimingResult.NOT_READY)!!.catalogParts)
    }

    @Test fun returnValueAndNullableResultAreNotReexecuted() = runBlocking {
        val timing = PlaybackCatalogTiming()
        val marker = Any()
        var calls = 0
        assertSame(marker, timing.measure(PlaybackCatalogTimingStage.DATABASE) { marker })
        assertNull(timing.measure(PlaybackCatalogTimingStage.MAPPING) { calls++; null })
        assertEquals(1, calls)
        assertNotNull(timing.finish())
    }

    @Test fun exceptionalReadRecordsElapsedAndPreservesOriginalException() = runBlocking {
        val clock = Clock()
        val timing = PlaybackCatalogTiming { clock.now }
        val original = IllegalStateException("private URI must not leak")
        assertSame(original, runCatching {
            timing.measure<Unit>(PlaybackCatalogTimingStage.DATABASE) { clock.tick(13); throw original }
        }.exceptionOrNull())
        val result = timing.finish()!!
        assertEquals(13L, result.databaseMillis)
        assertFalse(result.diagnosticFields().contains("private"))
    }

    @Test fun cancelledReadRecordsTimeWithoutPublishingSuccess() = runBlocking {
        val clock = Clock()
        val timing = PlaybackCatalogTiming { clock.now }
        val original = CancellationException("cancel")
        assertSame(original, runCatching {
            timing.measure<Unit>(PlaybackCatalogTimingStage.DATABASE) { clock.tick(17); throw original }
        }.exceptionOrNull())
        assertEquals(17L, timing.finish()!!.databaseMillis)
    }

    @Test fun repeatedRuleAttemptsAreAccumulatedNotReportedAsSingleSqlTime() = runBlocking {
        val clock = Clock()
        val timing = PlaybackCatalogTiming { clock.now }
        timing.measure(PlaybackCatalogTimingStage.DATABASE) { clock.tick(5) }
        timing.measure(PlaybackCatalogTimingStage.DATABASE) { clock.tick(7) }
        val result = timing.finish()!!
        assertEquals(12L, result.databaseMillis)
        assertTrue(result.diagnosticFields().contains("databaseIncludesRuleAndProjection=true"))
    }

    @Test fun backwardsClockProducesZeroDuration() = runBlocking {
        val clock = Clock(10_000_000)
        val timing = PlaybackCatalogTiming { clock.now }
        timing.measure(PlaybackCatalogTimingStage.DECISIONS) { clock.now = 0 }
        assertEquals(0L, timing.finish()!!.decisionMillis)
    }

    @Test fun elapsedOverflowAndAdditionAreSaturated() = runBlocking {
        var time = Long.MIN_VALUE
        val timing = PlaybackCatalogTiming { time }
        repeat(2) {
            time = Long.MIN_VALUE
            timing.measure(PlaybackCatalogTimingStage.DATABASE) { time = Long.MAX_VALUE }
        }
        assertEquals(Long.MAX_VALUE / 1_000_000, timing.finish()!!.databaseMillis)
    }

    @Test fun fractionalMillisAreTruncatedNotInvented() = runBlocking {
        val clock = Clock()
        val timing = PlaybackCatalogTiming { clock.now }
        timing.measure(PlaybackCatalogTimingStage.MAPPING) { clock.now += 999_999 }
        assertEquals(0L, timing.finish()!!.mappingMillis)
    }

    @Test fun completedTimerIgnoresLateCallsAndSecondFinish() = runBlocking {
        val clock = Clock()
        val timing = PlaybackCatalogTiming { clock.now }
        timing.measure(PlaybackCatalogTimingStage.DECISIONS) { clock.tick(1) }
        val result = timing.finish()!!
        timing.measure(PlaybackCatalogTimingStage.DATABASE) { clock.tick(5) }
        assertNull(timing.finish())
        assertEquals(0L, result.databaseMillis)
    }

    @Test fun finishIsExactlyOnceAcrossThreads() = runBlocking {
        val timing = PlaybackCatalogTiming()
        timing.measure(PlaybackCatalogTimingStage.MAPPING) { Unit }
        val start = CountDownLatch(1)
        val end = CountDownLatch(8)
        val results = CopyOnWriteArrayList<PlaybackCatalogMeasurement>()
        repeat(8) {
            Thread {
                try { start.await(); timing.finish()?.let(results::add) } finally { end.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(end.await(5, TimeUnit.SECONDS))
        assertEquals(1, results.size)
    }

    @Test fun diagnosticRecordFitsPrivateRingWithoutPaths() {
        val fields = PlaybackCatalogMeasurement(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE).diagnosticFields()
        val prefix = "preview=false direction=PREVIOUS scheduled=false result=COMMITTED_SAVE_CANCELLED items=2147483647 order=NATURAL_DESC "
        assertTrue((prefix + fields).length <= 450)
        assertFalse(fields.contains("content://"))
        assertFalse(fields.contains("/storage/"))
    }
}
