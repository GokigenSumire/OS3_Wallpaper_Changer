package dev.abdus.apps.shimmer.p5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** X1–X5 and X7 verdicts follow the frozen P5 thresholds and sample sizes. */
class TransitionStatsTest {
    private val ms = 1_000_000L

    /** One completed transition: [frames] frames [intervalMs] apart, each drawn in [drawMs]. */
    private fun TransitionStats.transition(
        effect: TransitionEffect,
        frames: Int = 10,
        drawMs: Long = 2,
        intervalMs: Long = 16,
        overshootMs: Long = 10,
        interrupted: Boolean = false,
        paused: Boolean = false,
        residual: Int = 0,
        layers: Int = 2,
        targets: Int = 1,
        heap: Long = 100L * 1024 * 1024,
        imageId: String = "a",
    ) {
        val recorder = open(effect, 1_500)
        repeat(frames) { recorder.frame(drawMs * ms, it * intervalMs * ms) }
        recorder.paused = paused
        recorder.finish(interrupted, 1_500 + overshootMs, residual, layers, targets, heap, imageId)
    }

    /** 40 healthy transitions, 10 per animated effect, 400 frames. */
    private fun healthy(): TransitionStats = TransitionStats().apply {
        repeat(40) { transition(TransitionEffect.ANIMATED[it % 4], imageId = "img${it % 5}") }
    }

    @Test
    fun anEmptyRecordIsInsufficientEverywhere() {
        val snapshot = TransitionStats().snapshot()
        listOf(snapshot.x1, snapshot.x2, snapshot.x3, snapshot.x4, snapshot.x5, snapshot.x7).forEach {
            assertEquals(Verdict.INSUFFICIENT, it)
        }
        assertNull(snapshot.heapGrowth)
    }

    @Test
    fun healthyTransitionsPassAndTheFirstFrameHasNoInterval() {
        val snapshot = healthy().snapshot()
        assertEquals(40, snapshot.transitions)
        assertEquals(400, snapshot.drawSamples)
        assertEquals(360, snapshot.intervalSamples)
        assertEquals(16.0, snapshot.intervalMaxMillis!!, 1e-9)
        assertEquals(10L, snapshot.overshootP95Millis)
        TransitionEffect.ANIMATED.forEach { assertEquals(10, snapshot.completedByEffect[it]) }
        listOf(snapshot.x1, snapshot.x2, snapshot.x3, snapshot.x4, snapshot.x5, snapshot.x7).forEach {
            assertEquals(Verdict.PASS, it)
        }
    }

    @Test
    fun slowDrawsAndLongIntervalsFail() {
        val slowDraw = TransitionStats().apply { repeat(40) { transition(TransitionEffect.ANIMATED[it % 4], drawMs = 9) } }
        assertEquals(Verdict.FAIL, slowDraw.snapshot().x1)
        val slowInterval = TransitionStats().apply { repeat(40) { transition(TransitionEffect.ANIMATED[it % 4], intervalMs = 21) } }
        assertEquals(Verdict.FAIL, slowInterval.snapshot().x2)
        val oneStall = healthy().apply { transition(TransitionEffect.SLIDE, frames = 2, intervalMs = 101) }
        assertEquals(Verdict.FAIL, oneStall.snapshot().x2)
    }

    @Test
    fun overshootUsesTheNearestRankP95() {
        assertEquals(Verdict.PASS, healthy().snapshot().x3)
        val stats = TransitionStats().apply {
            repeat(37) { transition(TransitionEffect.ANIMATED[it % 4]) }
            repeat(3) { transition(TransitionEffect.ZOOM, overshootMs = 80) }
        }
        assertEquals(80L, stats.snapshot().overshootP95Millis)
        assertEquals(Verdict.FAIL, stats.snapshot().x3)
    }

    @Test
    fun interruptedAndPausedTransitionsAreCountedButNotMeasured() {
        val stats = TransitionStats().apply {
            transition(TransitionEffect.SLIDE, interrupted = true, overshootMs = 5_000)
            transition(TransitionEffect.ZOOM, paused = true, intervalMs = 60_000, overshootMs = 60_000)
        }
        val snapshot = stats.snapshot()
        assertEquals(2, snapshot.transitions)
        assertEquals(1, snapshot.interrupted)
        assertEquals(1, snapshot.paused)
        // The interrupted one keeps its frames; the paused one keeps nothing.
        assertEquals(10, snapshot.drawSamples)
        assertEquals(16.0, snapshot.intervalMaxMillis!!, 1e-9)
        assertNull(snapshot.overshootP95Millis)
        TransitionEffect.ANIMATED.forEach { assertEquals(0, snapshot.completedByEffect[it]) }
        assertTrue(snapshot.recent.last().endsWith("paused residual=0"))
    }

    @Test
    fun x1AndX3NeedEveryEffectAtLeastThreeTimes() {
        val stats = TransitionStats().apply { repeat(40) { transition(TransitionEffect.ANIMATED[it % 3]) } }
        assertEquals(Verdict.INSUFFICIENT, stats.snapshot().x1)
        assertEquals(Verdict.INSUFFICIENT, stats.snapshot().x3)
        assertEquals(Verdict.PASS, stats.snapshot().x2)
    }

    @Test
    fun oneLeakOrOverBudgetPeakFailsX4AtOnce() {
        assertEquals(Verdict.FAIL, TransitionStats().apply { transition(TransitionEffect.CROSSFADE, residual = 1) }.snapshot().x4)
        assertEquals(Verdict.FAIL, TransitionStats().apply { transition(TransitionEffect.CROSSFADE, layers = 3) }.snapshot().x4)
        assertEquals(Verdict.FAIL, TransitionStats().apply { transition(TransitionEffect.ZOOM, targets = 2) }.snapshot().x4)
        assertEquals(Verdict.INSUFFICIENT, TransitionStats().apply { transition(TransitionEffect.ZOOM) }.snapshot().x4)
    }

    @Test
    fun aStaleFrameFailsX5AndFallbacksAreCounted() {
        val stats = healthy().apply {
            recordStaleFrame()
            recordTargetFallback()
        }
        assertEquals(Verdict.FAIL, stats.snapshot().x5)
        assertEquals(1, stats.snapshot().targetFallbacks)
    }

    @Test
    fun heapGrowthComparesTheSameImageAtLeastThirtyTransitionsApart() {
        val mib = 1024L * 1024
        val stats = TransitionStats().apply {
            repeat(40) { transition(TransitionEffect.ANIMATED[it % 4], heap = (100 + it) * mib, imageId = "img${it % 5}") }
        }
        // Latest is #40 (img4); the earliest img4 is #5, 35 transitions before.
        assertEquals(TransitionStats.HeapGrowth(35 * mib, 35), stats.snapshot().heapGrowth)
        assertEquals(Verdict.PASS, stats.snapshot().x7)
        stats.transition(TransitionEffect.CROSSFADE, heap = 300 * mib, imageId = "img0")
        assertEquals(Verdict.FAIL, stats.snapshot().x7)
    }

    @Test
    fun resetClearsEverything() {
        val stats = healthy().apply { recordStaleFrame() }
        stats.reset()
        val snapshot = stats.snapshot()
        assertEquals(0, snapshot.transitions)
        assertEquals(0, snapshot.staleFrames)
        assertTrue(snapshot.recent.isEmpty())
        assertNull(snapshot.heapGrowth)
    }
}
