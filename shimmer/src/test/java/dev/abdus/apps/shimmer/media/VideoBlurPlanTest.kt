package dev.abdus.apps.shimmer.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class VideoBlurPlanTest {
    @Test fun foregroundRetainsDetailWithinTheSourceAndGpuBudget() {
        val size = VideoBlurPlan.size(1220, 2656)
        assertEquals(1024, size.height)
        assertTrue(size.width >= 400)
        assertEquals(VideoBlurPlan.Size(320, 180), VideoBlurPlan.size(640, 360))
        assertEquals(VideoBlurPlan.Size(1, 1), VideoBlurPlan.size(1, 1))
        assertEquals(VideoBlurPlan.Size(1, 1024), VideoBlurPlan.size(1, Int.MAX_VALUE))
    }

    @Test fun requestedStrengthChangesTheRadiusWithoutMixingSharpContentBack() {
        val source = VideoBlurPlan.Size(640, 360)
        val target = VideoBlurPlan.size(640, 360)
        assertEquals(0, VideoBlurPlan.radius(400f, 0f, source, target))
        assertEquals(50, VideoBlurPlan.radius(400f, 0.5f, source, target))
        assertEquals(200, VideoBlurPlan.radius(400f, 1f, source, target))
        assertEquals(25, VideoBlurPlan.radius(50f, 1f, source, target))
        assertEquals(0, VideoBlurPlan.radius(0f, 1f, source, target))
    }

    @Test fun kernelsPreserveConstantColourAndNeverExceedUniformBudget() {
        for (radius in 0..VideoBlurPlan.MAX_KERNEL_RADIUS) {
            val kernel = VideoBlurPlan.kernel(radius)
            assertTrue(kernel.weights.size <= VideoBlurPlan.MAX_SAMPLES)
            assertEquals(kernel.weights.size, kernel.offsets.size)
            assertEquals(1.0, kernel.weights[0] + 2 * kernel.weights.drop(1).sum().toDouble(), 0.000001)
            assertTrue(kernel.weights.all { it.isFinite() && it > 0 })
            assertTrue(kernel.offsets.all { it.isFinite() && it in 0f..radius.toFloat() })
            assertTrue(kernel.offsets.toList().zipWithNext().all { (a, b) -> a < b })
        }
    }

    @Test fun gaussianSuppressesRepeatedStripeContrastAtStrongBlur() {
        val kernel = VideoBlurPlan.kernel(40)
        // Response of a symmetric filter to an eight-pixel sinusoidal stripe pattern.
        val response = kernel.weights[0] + 2 * (1 until kernel.weights.size).sumOf {
            kernel.weights[it] * cos(2 * Math.PI * kernel.offsets[it] / 8)
        }
        assertTrue(abs(response) < 0.02)
    }

    @Test fun mipPyramidIncludesTheFinalSingleTexelForNpotSources() {
        assertEquals(1, VideoBlurPlan.mipLevels(1, 1))
        assertEquals(10, VideoBlurPlan.mipLevels(640, 360))
        assertEquals(12, VideoBlurPlan.mipLevels(1220, 2656))
    }
}
