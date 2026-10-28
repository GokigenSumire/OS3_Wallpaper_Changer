package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EXIF orientation, decode budget and background fill (WP3-03). */
class P3DecodeTest {
    /**
     * The EXIF definition of each tag, as the upright position of stored pixel (x, y) in a
     * [w]×[h] image. This is the oracle; [OrientationSpec.matrixValues] must reproduce it.
     */
    private fun exifUpright(tag: Int, x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> = when (tag) {
        2 -> (w - 1 - x) to y
        3 -> (w - 1 - x) to (h - 1 - y)
        4 -> x to (h - 1 - y)
        5 -> y to x
        6 -> (h - 1 - y) to x
        7 -> (h - 1 - y) to (w - 1 - x)
        8 -> y to (w - 1 - x)
        else -> x to y
    }

    /** Maps a pixel centre through Matrix-order values and returns the destination pixel. */
    private fun mapPixel(values: FloatArray, x: Int, y: Int): Pair<Int, Int> {
        val cx = x + 0.5f
        val cy = y + 0.5f
        val dx = values[0] * cx + values[1] * cy + values[2]
        val dy = values[3] * cx + values[4] * cy + values[5]
        return kotlin.math.floor(dx).toInt() to kotlin.math.floor(dy).toInt()
    }

    @Test
    fun everyExifOrientationMatchesItsDefinition() {
        val w = 3
        val h = 2
        for (tag in 1..8) {
            val spec = OrientationSpec.fromExif(tag)
            val values = spec.matrixValues(w, h)
            for (x in 0 until w) for (y in 0 until h) {
                assertEquals("tag $tag pixel ($x,$y)", exifUpright(tag, x, y, w, h), mapPixel(values, x, y))
            }
            val uprightW = spec.orientedWidth(w, h)
            val uprightH = spec.orientedHeight(w, h)
            assertEquals("tag $tag width", if (tag >= 5) h else w, uprightW)
            assertEquals("tag $tag height", if (tag >= 5) w else h, uprightH)
        }
    }

    @Test
    fun unknownOrMissingOrientationIsUpright() {
        for (tag in listOf(0, 1, 9, -1, 255)) {
            assertTrue(OrientationSpec.fromExif(tag).isIdentity)
        }
        assertFalse(OrientationSpec.fromExif(6).isIdentity)
        assertTrue(OrientationSpec.fromExif(6).swapsDimensions)
        assertFalse(OrientationSpec.fromExif(3).swapsDimensions)
    }

    @Test
    fun sampleSizeKeepsEnoughDetailForTheDisplayedScale() {
        // 4000×3000 covering a 1220×2656 portrait needs ~0.885 screen px per source px.
        val cover = TransformMath.totalScale(ImageTransform(), 4000, 3000, 1220, 2656)
        assertEquals(1, DecodeBudget.sampleSize(4000, 3000, cover, 16384))
        // Shown at 0.3×: 4× sampling magnifies 1.2× (≤ 1.25), 8× would magnify 2.4×.
        assertEquals(4, DecodeBudget.sampleSize(4000, 3000, 0.3f, 16384))
        // Exactly on the tolerance still counts as enough detail.
        assertEquals(2, DecodeBudget.sampleSize(4000, 3000, 0.625f, 16384))
    }

    @Test
    fun zoomingInNeverAsksForACoarserDecode() {
        var previous = Int.MAX_VALUE
        var scale = 0.01f
        while (scale < 20f) {
            val sample = DecodeBudget.sampleSize(8000, 6000, scale, 16384)
            assertTrue("scale $scale", sample <= previous)
            previous = sample
            scale *= 1.1f
        }
    }

    @Test
    fun pixelAndTextureCapsBoundEveryDecode() {
        val cases = listOf(
            intArrayOf(20000, 2000, 16384),
            intArrayOf(20000, 2000, 4096),
            intArrayOf(12000, 9000, 16384),
            intArrayOf(16385, 100, 16384),
            intArrayOf(50000, 50000, 8192),
        )
        for ((w, h, maxTexture) in cases.map { Triple(it[0], it[1], it[2]) }) {
            for (scale in listOf(0.1f, 1f, 10f)) {
                val sample = DecodeBudget.sampleSize(w, h, scale, maxTexture)
                val dw = DecodeBudget.sampledEdge(w, sample)
                val dh = DecodeBudget.sampledEdge(h, sample)
                assertTrue("$w×$h@$scale texture", dw <= maxTexture && dh <= maxTexture)
                assertTrue("$w×$h@$scale pixels", dw.toLong() * dh <= DecodeBudget.MAX_PIXELS)
                assertEquals("power of two", 0, sample and (sample - 1))
            }
        }
        // A zoomed-in 20000×2000 panorama still has to be halved to fit 16.8 MP.
        assertEquals(2, DecodeBudget.sampleSize(20000, 2000, 10f, 16384))
        assertEquals(8, DecodeBudget.sampleSize(20000, 2000, 10f, 4096))
        assertEquals(2, DecodeBudget.sampleSize(16385, 100, 10f, 16384))
    }

    @Test
    fun invalidInputsDecodeAtFullSizeWithinTheCaps() {
        assertEquals(1, DecodeBudget.sampleSize(0, 100, 1f, 16384))
        assertEquals(1, DecodeBudget.sampleSize(100, 100, Float.NaN, 16384))
        assertEquals(1, DecodeBudget.sampleSize(100, 100, 1f, 0))
        assertEquals(2, DecodeBudget.sampleSize(5000, 100, 1f, 0))
    }

    @Test
    fun backgroundIsReducedToTheTargetLongEdgeAndNeverEnlarged() {
        assertEquals(96 to 72, BackgroundBlur.targetSize(4000, 3000))
        assertEquals(44 to 96, BackgroundBlur.targetSize(1220, 2656))
        assertEquals(96 to 10, BackgroundBlur.targetSize(20000, 2000))
        assertEquals(96 to 1, BackgroundBlur.targetSize(100000, 10))
        assertEquals(50 to 40, BackgroundBlur.targetSize(50, 40))
        assertEquals(1 to 1, BackgroundBlur.targetSize(0, 40))
    }

    @Test
    fun blurKeepsFlatColourAndOnlyDimsIt() {
        val colour = 0xFF808080.toInt()
        val pixels = IntArray(12 * 9) { colour }
        BackgroundBlur.blurAndDim(pixels, 12, 9)
        // 128 × 0.8 = 102.4 → 102; alpha stays opaque; edges are clamped, not darkened.
        val expected = (0xFF shl 24) or (102 shl 16) or (102 shl 8) or 102
        assertTrue(pixels.all { it == expected })
    }

    @Test
    fun blurSpreadsDetailWhileKeepingItsEnergy() {
        val size = 21
        val pixels = IntArray(size * size) { 0xFF000000.toInt() }
        pixels[10 * size + 10] = 0xFFFFFFFF.toInt()
        BackgroundBlur.blurAndDim(pixels, size, size)

        val red = pixels.map { (it shr 16) and 0xFF }
        val centre = red[10 * size + 10]
        val neighbour = red[10 * size + 13]
        assertTrue("peak is flattened", centre in 1..40)
        assertTrue("detail spreads", neighbour in 1 until centre)
        // 255 × 0.8 = 204 spread over the window; per-pass integer rounding moves it only a little.
        assertTrue("energy ${red.sum()}", red.sum() in 150..260)
        assertTrue(pixels.all { (it ushr 24) == 0xFF })
    }
}
