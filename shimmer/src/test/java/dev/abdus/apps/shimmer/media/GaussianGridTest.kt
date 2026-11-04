package dev.abdus.apps.shimmer.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

class GaussianGridTest {
    private fun sample(values: DoubleArray, x: Double): Double {
        val lo = floor(x).toInt(); val part = x - lo
        return values[lo.coerceIn(values.indices)] * (1 - part) + values[(lo + 1).coerceIn(values.indices)] * part
    }
    private fun paired(values: DoubleArray, center: Double, step: Double, radius: Int): Double {
        val kernel = VideoBlurPlan.kernel(radius)
        var out = sample(values, center) * kernel.weights[0]
        for (i in 1 until kernel.weights.size) {
            val offset = kernel.offsets[i] * step
            out += (sample(values, center - offset) + sample(values, center + offset)) * kernel.weights[i]
        }
        return out
    }
    private fun discrete(values: DoubleArray, center: Int, radius: Int): Double {
        val sigma = (radius / 3.0).coerceAtLeast(0.5)
        var total = 0.0; var out = 0.0
        for (i in -radius..radius) {
            val w = exp(-0.5 * i * i / (sigma * sigma))
            total += w; out += values[(center + i).coerceIn(values.indices)] * w
        }
        return out / total
    }
    @Test fun bilinearPairedWeightsMatchGaussianOnlyOnTheirOwnTexelGrid() {
        val source = DoubleArray(257) { if (it % 3 == 0) 1.0 else 0.0 }
        val size = 93
        val grid = DoubleArray(size) { sample(source, (it + 0.5) * source.size / size - 0.5) }
        var fusedError = 0.0
        for (i in grid.indices) {
            val reference = discrete(grid, i, 17)
            assertEquals(reference, paired(grid, i.toDouble(), 1.0, 17), 0.000001)
            val fused = paired(source, (i + 0.5) * source.size / size - 0.5, source.size.toDouble() / size, 17)
            fusedError = maxOf(fusedError, abs(fused - reference))
        }
        assertTrue("Fused resize/convolution must expose its non-equivalent grid", fusedError > 0.01)
    }
    @Test fun independentFixedGridPassesPreserveUniformColourAtNpotEdges() {
        val grid = DoubleArray(93) { 0.375 }
        for (i in grid.indices) assertEquals(0.375, paired(grid, i.toDouble(), 1.0, 37), 0.000001)
    }
}
