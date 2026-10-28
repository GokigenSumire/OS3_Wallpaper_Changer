package dev.abdus.apps.shimmer.p5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** REQ-FX-01: each effect's frames follow the curves in the P5 plan "效果定义". */
class TransitionTimelineTest {
    private val steps = (0..100).map { it / 100f }

    @Test
    fun easingCurvesStartAtZeroEndAtOneAndNeverGoBack() {
        listOf(TransitionTimeline::decelerate, TransitionTimeline::smooth).forEach { curve ->
            assertEquals(0f, curve(0f), 0f)
            assertEquals(1f, curve(1f), 0f)
            steps.zipWithNext().forEach { (a, b) -> assertTrue(curve(b) >= curve(a)) }
        }
        assertEquals(0.75f, TransitionTimeline.decelerate(0.5f), 1e-6f)
        assertEquals(0.5f, TransitionTimeline.smooth(0.5f), 1e-6f)
    }

    @Test
    fun noneDrawsOnlyTheNewImage() {
        steps.forEach { p ->
            assertEquals(TransitionFrame(drawOld = false, drawNew = true), TransitionTimeline.frame(TransitionEffect.NONE, p))
        }
    }

    @Test
    fun crossfadeAndZoomFadeTheNewImageInThroughTheTarget() {
        val crossfade = steps.map { TransitionTimeline.frame(TransitionEffect.CROSSFADE, it) }
        assertEquals(0f, crossfade.first().newAlpha, 0f)
        assertEquals(1f, crossfade.last().newAlpha, 0f)
        assertTrue(crossfade.all { it.drawOld && it.drawNew && it.newScale == 1f && it.blackOverlay == 0f })
        assertTrue(crossfade.dropLast(1).all { it.needsTarget })
        assertFalse(crossfade.last().needsTarget)

        val zoom = steps.map { TransitionTimeline.frame(TransitionEffect.ZOOM, it) }
        assertEquals(TransitionTimeline.ZOOM_START_SCALE, zoom.first().newScale, 1e-6f)
        assertEquals(1f, zoom.last().newScale, 1e-6f)
        zoom.zipWithNext().forEach { (a, b) -> assertTrue(b.newScale <= a.newScale && b.newAlpha >= a.newAlpha) }
        assertTrue(zoom.dropLast(1).all { it.needsTarget })
    }

    @Test
    fun fadeThroughGoesToBlackAtTheMidpointAndSwapsImagesThere() {
        val frames = steps.map { it to TransitionTimeline.frame(TransitionEffect.FADE_THROUGH, it) }
        frames.forEach { (p, frame) ->
            assertEquals("p=$p", p < 0.5f, frame.drawOld)
            assertEquals("p=$p", p >= 0.5f, frame.drawNew)
            assertFalse(frame.needsTarget)
        }
        assertEquals(0f, frames.first().second.blackOverlay, 0f)
        assertEquals(1f, TransitionTimeline.frame(TransitionEffect.FADE_THROUGH, 0.5f).blackOverlay, 1e-6f)
        assertEquals(0f, frames.last().second.blackOverlay, 0f)
        // Continuous on both sides of the swap: no visible jump.
        val before = TransitionTimeline.frame(TransitionEffect.FADE_THROUGH, 0.4999f).blackOverlay
        assertTrue(abs(1f - before) < 1e-3f)
    }

    @Test
    fun zoomEndsExactlyAtFullSizeAndOpacity() {
        val end = TransitionTimeline.frame(TransitionEffect.ZOOM, 1f)
        assertEquals(1f, end.newScale, 0f)
        assertEquals(1f, end.newAlpha, 0f)
        assertFalse(end.needsTarget)
    }

    @Test
    fun progressOutsideZeroToOneIsClamped() {
        TransitionEffect.entries.forEach { effect ->
            assertEquals(TransitionTimeline.frame(effect, 0f), TransitionTimeline.frame(effect, -1f))
            assertEquals(TransitionTimeline.frame(effect, 1f), TransitionTimeline.frame(effect, 2f))
        }
    }

    @Test
    fun slideCompositesMeetEdgeToEdgeInWholePixels() {
        val width = 1_080
        val height = 2_400
        SlideDirection.entries.forEach { direction ->
            steps.forEach { p ->
                val moved = TransitionTimeline.frame(TransitionEffect.SLIDE, p).slide
                val offsets = TransitionTimeline.slideOffsets(direction, moved, width, height)
                // Exactly one screen apart along the slide axis, aligned on the other.
                assertEquals(direction.dx * width, offsets.oldX - offsets.newX)
                assertEquals(direction.dy * height, offsets.oldY - offsets.newY)
            }
            val start = TransitionTimeline.slideOffsets(direction, 0f, width, height)
            assertEquals(SlideOffsets(0, 0, -direction.dx * width, -direction.dy * height), start)
            val end = TransitionTimeline.slideOffsets(direction, 1f, width, height)
            assertEquals(SlideOffsets(direction.dx * width, direction.dy * height, 0, 0), end)
        }
    }

    @Test
    fun slideNeverNeedsTheOffscreenTarget() {
        steps.forEach { assertFalse(TransitionTimeline.frame(TransitionEffect.SLIDE, it).needsTarget) }
    }

    @Test
    fun nextSlideMovesContentLeftSoTheNewImageEntersFromTheRight() {
        val half = TransitionTimeline.slideOffsets(SlideDirection.LEFT, 0.5f, 1_000, 2_000)
        assertEquals(-500, half.oldX)
        assertEquals(500, half.newX)
        // GL window y grows upward: UP moves content up and the new image rises from below.
        val up = TransitionTimeline.slideOffsets(SlideDirection.UP, 0.25f, 1_000, 2_000)
        assertEquals(500, up.oldY)
        assertEquals(-1_500, up.newY)
    }
}
