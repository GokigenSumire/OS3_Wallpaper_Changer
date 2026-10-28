package dev.abdus.apps.shimmer.ui.navigation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BackGestureTransformTest {
    @Test
    fun scaleShrinksLinearlyAndIsClamped() {
        assertEquals(1f, BackGestureTransform.scale(0f), 1e-6f)
        assertEquals(0.95f, BackGestureTransform.scale(0.5f), 1e-6f)
        assertEquals(BackGestureTransform.MinScale, BackGestureTransform.scale(1f), 1e-6f)
        assertEquals(1f, BackGestureTransform.scale(-0.3f), 1e-6f)
        assertEquals(BackGestureTransform.MinScale, BackGestureTransform.scale(1.7f), 1e-6f)
    }

    @Test
    fun pageFollowsTheFingerFromEitherEdge() {
        val width = 1000f
        val max = width * BackGestureTransform.MaxShiftFraction

        assertEquals(0f, BackGestureTransform.translationX(0f, width, fromRightEdge = false), 1e-3f)
        assertEquals(max, BackGestureTransform.translationX(1f, width, fromRightEdge = false), 1e-3f)
        assertEquals(-max, BackGestureTransform.translationX(1f, width, fromRightEdge = true), 1e-3f)
        assertEquals(max, BackGestureTransform.translationX(4f, width, fromRightEdge = false), 1e-3f)
    }

    @Test
    fun cornersRoundWithProgress() {
        assertEquals(0.dp, BackGestureTransform.cornerRadius(0f))
        assertEquals(BackGestureTransform.MaxCornerRadius, BackGestureTransform.cornerRadius(1f))
        assertEquals(14.dp, BackGestureTransform.cornerRadius(0.5f))
    }
}
