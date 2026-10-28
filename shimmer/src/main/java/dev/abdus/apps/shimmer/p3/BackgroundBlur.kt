package dev.abdus.apps.shimmer.p3

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The blurred fill behind a foreground that does not cover the screen (REQ-EDIT-04).
 *
 * The whole image is reduced to a [TARGET_LONG_EDGE] px long edge, box-blurred [PASSES] times
 * (≈ Gaussian) and dimmed by [DIM_FRACTION]. It carries only low-frequency colour, so it is built
 * on the CPU from the foreground and does not depend on the optional blur keyframes.
 */
object BackgroundBlur {
    const val TARGET_LONG_EDGE = 96
    const val RADIUS = 2
    const val PASSES = 3
    const val DIM_FRACTION = 0.2f

    /** Background size for a foreground of [width]×[height]; never enlarges a smaller image. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longEdge = max(width, height)
        if (longEdge <= TARGET_LONG_EDGE) return width to height
        val factor = TARGET_LONG_EDGE.toFloat() / longEdge
        return max(1, (width * factor).roundToInt()) to max(1, (height * factor).roundToInt())
    }

    /** Blurs and dims ARGB [pixels] of a [width]×[height] image in place. Alpha is blurred too. */
    fun blurAndDim(pixels: IntArray, width: Int, height: Int, radius: Int = RADIUS, passes: Int = PASSES) {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Invalid background buffer" }
        val scratch = IntArray(width * height)
        repeat(passes) {
            boxPass(pixels, scratch, width, height, radius, horizontal = true)
            boxPass(scratch, pixels, width, height, radius, horizontal = false)
        }
        val keep = 1f - DIM_FRACTION
        for (i in 0 until width * height) {
            val p = pixels[i]
            val r = (((p shr 16) and 0xFF) * keep).roundToInt()
            val g = (((p shr 8) and 0xFF) * keep).roundToInt()
            val b = ((p and 0xFF) * keep).roundToInt()
            pixels[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /** One separable box pass with edge clamping, so edges do not darken toward black. */
    private fun boxPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val clamped = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * width + clamped] else src[clamped * width + line]
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -radius..radius) {
                val p = at(i)
                sa += (p ushr 24); sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (i in 0 until length) {
                val out = ((sa + window / 2) / window shl 24) or
                    ((sr + window / 2) / window shl 16) or
                    ((sg + window / 2) / window shl 8) or
                    ((sb + window / 2) / window)
                if (horizontal) dst[line * width + i] = out else dst[i * width + line] = out
                val leaving = at(i - radius)
                val entering = at(i + radius + 1)
                sa += (entering ushr 24) - (leaving ushr 24)
                sr += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                sg += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                sb += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
