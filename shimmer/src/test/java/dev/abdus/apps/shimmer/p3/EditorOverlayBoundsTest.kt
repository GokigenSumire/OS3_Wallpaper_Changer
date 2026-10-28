package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorOverlayBoundsTest {
    @Test fun positiveWindowOriginTranslatesOnlyTheAlreadyClippedRectangle() {
        assertTrue(EditorOverlayBounds.contains(20, 30, 80, 90, 100, 200, 120f, 230f))
        assertTrue(EditorOverlayBounds.contains(20, 30, 80, 90, 100, 200, 179.5f, 289.5f))
        assertFalse(EditorOverlayBounds.contains(20, 30, 80, 90, 100, 200, 110f, 230f))
        assertFalse(EditorOverlayBounds.contains(20, 30, 80, 90, 100, 200, 180f, 230f))
    }
    @Test fun negativeWindowOriginsAreNotClampedToZero() {
        assertTrue(EditorOverlayBounds.contains(20, 30, 80, 90, -100, -200, -80f, -170f))
        assertFalse(EditorOverlayBounds.contains(20, 30, 80, 90, -100, -200, -20f, -170f))
    }
    @Test fun fractionalNegativeCoordinateMustNotTruncateIntoTheLeftEdge() {
        assertFalse(EditorOverlayBounds.contains(0, 0, 10, 10, 0, 0, -0.2f, 0f))
        assertFalse(EditorOverlayBounds.contains(0, 0, 10, 10, 0, 0, 0f, -0.2f))
        assertTrue(EditorOverlayBounds.contains(0, 0, 10, 10, 0, 0, 0f, 0f))
    }
    @Test fun rightAndBottomEdgesRemainHalfOpenAfterTranslation() {
        assertTrue(EditorOverlayBounds.contains(0, 0, 10, 10, 50, 100, 59.99f, 109.99f))
        assertFalse(EditorOverlayBounds.contains(0, 0, 10, 10, 50, 100, 60f, 109f))
        assertFalse(EditorOverlayBounds.contains(0, 0, 10, 10, 50, 100, 59f, 110f))
    }
    @Test fun emptyReversedOrMissingClippedRectangleNeverHits() {
        assertFalse(EditorOverlayBounds.contains(1, 1, 1, 3, 100, 100, 101f, 101f))
        assertFalse(EditorOverlayBounds.contains(1, 1, 3, 1, 100, 100, 101f, 101f))
        assertFalse(EditorOverlayBounds.contains(3, 1, 1, 3, 100, 100, 101f, 101f))
    }
    @Test fun nonFiniteInputNeverHitsAnOverlay() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertFalse(EditorOverlayBounds.contains(0, 0, 10, 10, 0, 0, it, 1f))
            assertFalse(EditorOverlayBounds.contains(0, 0, 10, 10, 0, 0, 1f, it))
        }
    }
    @Test fun originAdditionDoesNotOverflowIntOrReenterTheOppositeSideOfScreen() {
        assertFalse(EditorOverlayBounds.contains(Int.MAX_VALUE - 100, 0, Int.MAX_VALUE, 10,
            Int.MAX_VALUE, 0, -20f, 1f))
        assertTrue(EditorOverlayBounds.contains(Int.MIN_VALUE, 0, Int.MIN_VALUE + 100, 10,
            Int.MAX_VALUE, 0, 0f, 1f))
    }
    @Test fun fullScreenZeroOriginKeepsTheOriginalVisibleHitArea() {
        assertTrue(EditorOverlayBounds.contains(0, 2000, 1220, 2656, 0, 0, 1f, 2001f))
        assertFalse(EditorOverlayBounds.contains(0, 2000, 1220, 2656, 0, 0, 1f, 1999f))
    }
}
