package dev.abdus.apps.shimmer.ui.effects

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF

    @Test
    fun uniformImageIsUnchangedIncludingItsEdges() {
        val color = argb(255, 40, 120, 200)
        val pixels = IntArray(7 * 5) { color }

        val blurred = BoxBlur.blur(pixels, 7, 5, radius = 3, passes = 3)

        assertArrayEquals(pixels, blurred)
    }

    @Test
    fun zeroRadiusOrPassesReturnsACopy() {
        val pixels = IntArray(9) { argb(255, it * 20, 0, 0) }

        val sameRadius = BoxBlur.blur(pixels, 3, 3, radius = 0, passes = 3)
        val samePasses = BoxBlur.blur(pixels, 3, 3, radius = 2, passes = 0)

        assertArrayEquals(pixels, sameRadius)
        assertArrayEquals(pixels, samePasses)
        assertTrue(sameRadius !== pixels)
    }

    @Test
    fun brightPixelSpreadsToItsNeighboursAndKeepsItsTotal() {
        val size = 21
        val pixels = IntArray(size * size) { argb(255, 0, 0, 0) }
        pixels[10 * size + 10] = argb(255, 255, 255, 255)

        val blurred = BoxBlur.blur(pixels, size, size, radius = 2, passes = 1)

        val center = red(blurred[10 * size + 10])
        val neighbour = red(blurred[10 * size + 11])
        assertTrue(center in 1..254)
        assertTrue(neighbour > 0)
        assertEquals(0, red(blurred[0]))
        // 远离边缘时亮度只被摊开不丢失；逐像素四舍五入允许少量误差
        val total = blurred.sumOf { red(it) }
        assertTrue("total=$total", total in 230..280)
    }

    @Test
    fun inputIsNotModifiedAndAlphaStaysOpaque() {
        val pixels = IntArray(4 * 4) { argb(255, (it * 16) and 0xFF, 0, 255 - it) }
        val original = pixels.copyOf()

        val blurred = BoxBlur.blur(pixels, 4, 4, radius = 1, passes = 3)

        assertArrayEquals(original, pixels)
        assertTrue(blurred.all { (it ushr 24) == 255 })
    }

    @Test
    fun singleColumnImagesAreSupported() {
        val pixels = IntArray(6) { argb(255, if (it < 3) 0 else 240, 0, 0) }

        val blurred = BoxBlur.blur(pixels, 1, 6, radius = 1, passes = 1)

        assertEquals(6, blurred.size)
        assertTrue(red(blurred[2]) in 1..239)
        assertTrue(red(blurred[3]) in 1..239)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedSizeIsRejected() {
        BoxBlur.blur(IntArray(5), 2, 3, radius = 1, passes = 1)
    }
}
