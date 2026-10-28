package dev.abdus.apps.shimmer.p4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ScheduleStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun event(kind: String, block: ScheduleEvent.() -> ScheduleEvent = { this }) =
        ScheduleEvent(wall = 0L, elapsed = 0L, kind = kind).block()

    private fun ScheduleStats.line(prefix: String) = report(zone).single { it.startsWith(prefix) }

    @Test
    fun nearestRankPercentiles() {
        val summary = summarize((1L..20L).toList())!!
        assertEquals(20, summary.n)
        assertEquals(10L, summary.p50)
        assertEquals(19L, summary.p95)
        assertEquals(20L, summary.max)
        assertEquals(null, summarize(emptyList()))
    }

    @Test
    fun commitSamplesSplitByLibrarySizeAndHeldCommitsAreNotSamples() {
        var stats = ScheduleStats()
        stats = stats.record(event(EventKind.COMMIT) { copy(commitMs = 800, catalogSize = 500) })
        stats = stats.record(event(EventKind.COMMIT) { copy(commitMs = 2_500, catalogSize = 26_599) })
        stats = stats.record(event(EventKind.COMMIT) { copy(commitMs = null, catalogSize = 10) })
        assertEquals(listOf(800L), stats.commitNormal)
        assertEquals(listOf(2_500L), stats.commitLarge)
        assertEquals(3, stats.counts[EventKind.COMMIT])
    }

    @Test
    fun lockCyclesCountCommitsOnlyForObservedCycles() {
        var stats = ScheduleStats()
        stats = stats.record(event(EventKind.CYCLE_OPEN) { copy(cycle = 1) })
        stats = stats.record(event(EventKind.CYCLE_OPEN) { copy(cycle = 2) })
        stats = stats.record(event(EventKind.COMMIT) { copy(cycle = 2) })
        stats = stats.record(event(EventKind.COMMIT) { copy(cycle = 2) })
        // A cycle opened before the statistics were cleared is not counted.
        stats = stats.record(event(EventKind.COMMIT) { copy(cycle = 0) })
        assertEquals(mapOf(1L to 0, 2L to 2), stats.cycleCommits)
        assertTrue(stats.line("T3 锁屏周期").endsWith("存在重复，未达标"))
    }

    @Test
    fun verdictNeedsEnoughSamplesButFailsOnAnyBreach() {
        var stats = ScheduleStats()
        repeat(3) { stats = stats.record(event(EventKind.DISPATCH) { copy(late = mapOf(ScheduleReason.INTERVAL to 300L)) }) }
        assertTrue(stats.line("T1").endsWith("样本不足（需 ≥ 20）"))
        stats = stats.record(event(EventKind.DISPATCH) { copy(late = mapOf(ScheduleReason.DAILY to 2_500L)) })
        assertTrue(stats.line("T1").endsWith("未达标"))

        var merge = ScheduleStats()
        repeat(3) { merge = merge.record(event(EventKind.DISPATCH) { copy(mergeMs = 40L) }) }
        assertTrue(merge.line("T4").endsWith("达标"))
        assertTrue(merge.line("T4").contains("最大 ≤ 1000"))
    }

    @Test
    fun ruleLatenessFeedsBothT1AndT5() {
        val stats = ScheduleStats().record(event(EventKind.DISPATCH) { copy(late = mapOf(ScheduleReason.RULE to 700L)) })
        assertEquals(listOf(700L), stats.dueLate)
        assertEquals(listOf(700L), stats.ruleLate)
    }
}
