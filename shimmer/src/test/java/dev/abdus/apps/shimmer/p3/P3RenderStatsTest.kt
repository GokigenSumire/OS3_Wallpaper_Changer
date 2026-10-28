package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class P3RenderStatsTest {
    @Test
    fun percentileUsesNearestRank() {
        val values = LongArray(100) { it + 1L }
        assertEquals(95L, FrameStats.percentile(values, 95))
        assertEquals(99L, FrameStats.percentile(values, 99))
        assertEquals(7L, FrameStats.percentile(longArrayOf(7), 99))
        assertNull(FrameStats.percentile(LongArray(0), 95))
    }

    @Test
    fun verdictNeedsTheFrozenSampleCountAndThresholds() {
        val stats = FrameStats()
        repeat(FrameStats.MIN_FRAME_SAMPLES - 1) {
            stats.recordDraw(2_000_000L, it.toLong())
            stats.recordInputLatency(20L)
        }
        assertNull(stats.snapshot().drawPass)
        assertNull(stats.snapshot().latencyPass)

        stats.recordDraw(2_000_000L, 1_000L)
        stats.recordInputLatency(20L)
        val passing = stats.snapshot()
        assertEquals(true, passing.drawPass)
        assertEquals(true, passing.latencyPass)
        assertEquals(300L, passing.totalDraws)
        assertEquals(1_000L, passing.lastDrawUptimeMillis)

        // 2% of frames over budget breaks the ≤ 1% rule even though p95 stays low.
        repeat(7) { stats.recordDraw(20_000_000L, 2_000L) }
        val snapshot = stats.snapshot()
        assertTrue(snapshot.drawOverBudgetFraction!! > FrameStats.DRAW_OVER_BUDGET_MAX_FRACTION)
        assertEquals(false, snapshot.drawPass)

        repeat(30) { stats.recordInputLatency(120L) }
        assertEquals(false, stats.snapshot().latencyPass)
    }

    @Test
    fun recentEventsKeepTheLastFiveInOrderAndResetClearsEverything() {
        val stats = FrameStats()
        (1L..7L).forEach { stats.recordSave(it * 100) }
        stats.recordEntry(900L)
        assertEquals(listOf(300L, 400L, 500L, 600L, 700L), stats.snapshot().recentSaveMillis)
        assertEquals(listOf(900L), stats.snapshot().recentEntryMillis)

        stats.recordDraw(1L, 5L)
        stats.reset()
        val cleared = stats.snapshot()
        assertEquals(0, cleared.drawSamples)
        assertEquals(0L, cleared.totalDraws)
        assertTrue(cleared.recentSaveMillis.isEmpty())
        assertTrue(cleared.recentEntryMillis.isEmpty())
    }

    @Test
    fun ringBufferKeepsOnlyTheNewestSamples() {
        val stats = FrameStats(capacity = 4)
        listOf(100L, 1L, 2L, 3L, 4L).forEach { stats.recordInputLatency(it) }
        val snapshot = stats.snapshot()
        assertEquals(4, snapshot.inputSamples)
        assertEquals(4L, snapshot.inputP99Millis)
    }

    @Test
    fun liveSlotKeepsOnlyTheNewestPlacementAndTheOldestInputTime() {
        val slot = LatestTransformSlot()
        slot.offer("a", ImageTransform(offsetX = 0.1f), 1_000L)
        slot.offer("a", ImageTransform(offsetX = 0.2f), 1_016L)
        val entry = slot.take()!!
        assertEquals(0.2f, entry.transform.offsetX, 0f)
        assertEquals(1_000L, entry.inputUptimeMillis)
        assertNull(slot.take())

        // A placement for another image does not inherit the old input time.
        slot.offer("a", ImageTransform(), 2_000L)
        slot.offer("b", ImageTransform(), 2_050L)
        assertEquals(2_050L, slot.take()!!.inputUptimeMillis)
    }

    @Test
    fun committedPlacementDiscardsOnlyTheSameImage() {
        val slot = LatestTransformSlot()
        slot.offer("a", ImageTransform(), 1L)
        slot.discard("b")
        assertEquals("a", slot.take()!!.imageId)
        slot.offer("a", ImageTransform(), 1L)
        slot.discard("a")
        assertNull(slot.take())
    }

    @Test
    fun clipMatrixInvertsTheForegroundPlacement() {
        val transform = ImageTransform(
            fitMode = FitMode.CONTAIN, userScale = 0.7f, offsetX = 0.12f, offsetY = -0.3f,
            rotationDegrees = 33f, mirrorHorizontal = true,
        )
        val mvp = TransformMath.foregroundMvp(transform, 4000, 3000, 1220, 2656)
        val inverse = TransformMath.clipToQuad(mvp)!!
        for ((x, y) in listOf(-1f to -1f, 1f to -1f, 0.4f to 0.9f, 0f to 0f)) {
            val clipX = mvp[0] * x + mvp[4] * y + mvp[12]
            val clipY = mvp[1] * x + mvp[5] * y + mvp[13]
            val backX = inverse[0] * clipX + inverse[3] * clipY + inverse[6]
            val backY = inverse[1] * clipX + inverse[4] * clipY + inverse[7]
            assertTrue("x=$x back=$backX", abs(backX - x) < 1e-4f)
            assertTrue("y=$y back=$backY", abs(backY - y) < 1e-4f)
        }
        assertEquals(1f, inverse[8], 0f)
    }

    @Test
    fun clipMatrixRejectsDegeneratePlacements() {
        val flat = FloatArray(16).also { it[10] = 1f; it[15] = 1f }
        assertNull(TransformMath.clipToQuad(flat))
        assertFalse(TransformMath.clipToQuad(TransformMath.foregroundMvp(ImageTransform(), 10, 10, 10, 10)) == null)
    }
}
