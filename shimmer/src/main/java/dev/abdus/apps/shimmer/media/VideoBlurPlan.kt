package dev.abdus.apps.shimmer.media

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** Foreground detail and low-frequency fill have separate GPU textures; neither uses CPU readback. */
object VideoBlurPlan {
    const val MAX_LONG_EDGE = 1024
    const val MAX_KERNEL_RADIUS = 200
    const val MAX_SAMPLES = 101
    data class Size(val width: Int, val height: Int)
    data class Kernel(val weights: FloatArray, val offsets: FloatArray)

    fun size(width: Int, height: Int): Size {
        require(width > 0 && height > 0)
        val scale = minOf(0.5, MAX_LONG_EDGE.toDouble() / max(width, height))
        return Size(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    fun radius(maxRadius: Float, progress: Float, source: Size, target: Size): Int {
        require(maxRadius.isFinite() && progress.isFinite())
        val p = progress.coerceIn(0f, 1f)
        return (maxRadius.coerceIn(0f, 400f) * p * p *
            minOf(target.width.toFloat() / source.width, target.height.toFloat() / source.height))
            .roundToInt().coerceIn(0, MAX_KERNEL_RADIUS)
    }

    /** Normalized symmetric Gaussian paired for bilinear texture sampling; support <= 3 sigma. */
    fun kernel(radius: Int): Kernel {
        require(radius in 0..MAX_KERNEL_RADIUS)
        if (radius == 0) return Kernel(floatArrayOf(1f), floatArrayOf(0f))
        val sigma = max(radius / 3.0, 0.5)
        val raw = DoubleArray(radius + 1) { exp(-0.5 * it * it / (sigma * sigma)) }
        val total = raw[0] + 2 * raw.drop(1).sum()
        val weights = ArrayList<Float>(); val offsets = ArrayList<Float>()
        weights.add((raw[0] / total).toFloat()); offsets.add(0f)
        for (i in 1..radius step 2) {
            val a = raw[i]; val b = raw.getOrElse(i + 1) { 0.0 }
            weights.add(((a + b) / total).toFloat())
            offsets.add(((a * i + b * (i + 1)) / (a + b)).toFloat())
        }
        check(weights.size <= MAX_SAMPLES)
        return Kernel(weights.toFloatArray(), offsets.toFloatArray())
    }

    fun mipLevels(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        var edge = max(width, height); var levels = 1
        while (edge > 1) { edge /= 2; levels++ }
        return levels
    }
}
