package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TransformMathTest {
    private val viewW = 1220
    private val viewH = 2656

    @Test
    fun baseScalesMatchClassicFitsWithoutRotation() {
        val w = 4000
        val h = 3000
        assertEquals(viewW / 4000f, TransformMath.baseScale(FitMode.FIT_WIDTH, w, h, viewW, viewH), 1e-6f)
        assertEquals(viewH / 3000f, TransformMath.baseScale(FitMode.FIT_HEIGHT, w, h, viewW, viewH), 1e-6f)
        assertEquals(viewW / 4000f, TransformMath.baseScale(FitMode.CONTAIN, w, h, viewW, viewH), 1e-6f)
        assertEquals(viewH / 3000f, TransformMath.baseScale(FitMode.COVER, w, h, viewW, viewH), 1e-6f)
    }

    @Test
    fun quarterTurnSwapsTheFittedImageAxes() {
        val scale = TransformMath.baseScale(FitMode.FIT_WIDTH, 4000, 3000, viewW, viewH, rotationDegrees = 90f)
        assertEquals(viewW / 3000f, scale, 1e-5f)
    }

    @Test
    fun coverScaleCoversAtEveryAngleAndIsMinimal() {
        for (angle in listOf(0f, 7f, 17f, 45f, 90f, -135f, 180f, 33.3f)) {
            val transform = ImageTransform(rotationDegrees = angle)
            assertTrue("angle $angle", TransformMath.coversViewport(transform, 4000, 3000, viewW, viewH))
            val smaller = transform.copy(userScale = 0.99f)
            assertFalse("angle $angle", TransformMath.coversViewport(smaller, 4000, 3000, viewW, viewH))
        }
    }

    @Test
    fun containLeavesFillVisibleForMismatchedAspect() {
        val transform = ImageTransform(fitMode = FitMode.CONTAIN)
        assertFalse(TransformMath.coversViewport(transform, 4000, 3000, viewW, viewH))
        assertTrue(TransformMath.coversViewport(transform, viewW, viewH, viewW, viewH))
    }

    @Test
    fun identityOnMatchingAspectFillsClipSpaceExactly() {
        val mvp = TransformMath.foregroundMvp(ImageTransform(), viewW, viewH, viewW, viewH)
        assertPoint(-1f, 1f, apply(mvp, -1f, 1f))
        assertPoint(1f, -1f, apply(mvp, 1f, -1f))
    }

    @Test
    fun clockwiseQuarterTurnMovesTopLeftToTopRight() {
        val mvp = TransformMath.foregroundMvp(ImageTransform(rotationDegrees = 90f), 3000, 3000, viewW, viewH)
        val topLeft = apply(mvp, -1f, 1f)
        assertTrue(topLeft.first > 0f && topLeft.second > 0f)
        val rightMiddle = apply(mvp, 1f, 0f)
        assertEquals(0f, rightMiddle.first, 1e-5f)
        assertTrue(rightMiddle.second < 0f)
    }

    @Test
    fun mirrorsFlipAlongScreenAxesBeforeRotation() {
        val horizontal = TransformMath.foregroundMvp(ImageTransform(mirrorHorizontal = true), viewW, viewH, viewW, viewH)
        assertPoint(-1f, 0f, apply(horizontal, 1f, 0f))
        val vertical = TransformMath.foregroundMvp(ImageTransform(mirrorVertical = true), viewW, viewH, viewW, viewH)
        assertPoint(0f, -1f, apply(vertical, 0f, 1f))
    }

    @Test
    fun offsetsAreViewportFractionsWithDownPositive() {
        val mvp = TransformMath.foregroundMvp(ImageTransform(offsetX = 0.25f, offsetY = 0.25f), viewW, viewH, viewW, viewH)
        assertPoint(0.5f, -0.5f, apply(mvp, 0f, 0f))
    }

    @Test
    fun matrixAndCoverageAgreeForRandomisedTransforms() {
        val random = java.util.Random(42)
        repeat(400) {
            val transform = ImageTransform(
                fitMode = FitMode.entries[random.nextInt(FitMode.entries.size)],
                userScale = 0.5f + random.nextFloat() * 2f,
                offsetX = (random.nextFloat() - 0.5f) * 0.6f,
                offsetY = (random.nextFloat() - 0.5f) * 0.6f,
                rotationDegrees = random.nextFloat() * 360f - 180f,
                mirrorHorizontal = random.nextBoolean(),
                mirrorVertical = random.nextBoolean(),
            )
            val imageW = 500 + random.nextInt(6000)
            val imageH = 500 + random.nextInt(6000)
            val mvp = TransformMath.foregroundMvp(transform, imageW, imageH, viewW, viewH)
            val cornersInside = listOf(-1f to -1f, -1f to 1f, 1f to -1f, 1f to 1f).map { (x, y) ->
                val model = inverseApply(mvp, x, y)
                abs(model.first) <= 1f && abs(model.second) <= 1f
            }
            val expected = cornersInside.all { it }
            // Skip cases inside the half-viewport-pixel tolerance band around the boundary.
            val nearBoundary = listOf(-1f to -1f, -1f to 1f, 1f to -1f, 1f to 1f).any { (x, y) ->
                val model = inverseApply(mvp, x, y)
                abs(abs(model.first) - 1f) < 0.05f || abs(abs(model.second) - 1f) < 0.05f
            }
            if (!nearBoundary) {
                assertEquals(transform.toString(), expected, TransformMath.coversViewport(transform, imageW, imageH, viewW, viewH))
            }
        }
    }

    @Test
    fun pinchKeepsTheImagePointUnderTheFingersFixed() {
        val start = ImageTransform(userScale = 1.3f, offsetX = 0.1f, offsetY = -0.05f, rotationDegrees = 30f)
        val focalX = 300f
        val focalY = 2000f
        val clipX = focalX / viewW * 2f - 1f
        val clipY = 1f - focalY / viewH * 2f
        val before = TransformMath.foregroundMvp(start, 4000, 3000, viewW, viewH)
        val modelPoint = inverseApply(before, clipX, clipY)

        val zoomed = TransformMath.zoomAround(start, 1.7f, focalX, focalY, viewW, viewH)
        val after = TransformMath.foregroundMvp(zoomed, 4000, 3000, viewW, viewH)

        assertEquals(1.3f * 1.7f, zoomed.userScale, 1e-5f)
        assertPoint(clipX, clipY, apply(after, modelPoint.first, modelPoint.second), 1e-4f)
    }

    @Test
    fun twistKeepsTheImagePointUnderTheFingersFixed() {
        val start = ImageTransform(userScale = 1.3f, offsetX = 0.1f, offsetY = -0.05f, rotationDegrees = 30f, mirrorHorizontal = true)
        val focalX = 900f
        val focalY = 700f
        val clipX = focalX / viewW * 2f - 1f
        val clipY = 1f - focalY / viewH * 2f
        val modelPoint = inverseApply(TransformMath.foregroundMvp(start, 4000, 3000, viewW, viewH), clipX, clipY)

        val rotated = TransformMath.rotateAround(start, 25f, focalX, focalY, 4000, 3000, viewW, viewH)
        val after = TransformMath.foregroundMvp(rotated, 4000, 3000, viewW, viewH)

        assertEquals(55f, rotated.rotationDegrees, 1e-4f)
        assertPoint(clipX, clipY, apply(after, modelPoint.first, modelPoint.second), 1e-4f)
    }

    @Test
    fun twistAroundAnEdgeSwingsTheCentreClockwise() {
        // Focal at the left edge midpoint; a clockwise quarter turn swings the centre below it.
        // CONTAIN of a square keeps the same base scale at 0 and 90 degrees.
        val start = ImageTransform(fitMode = FitMode.CONTAIN)
        val rotated = TransformMath.rotateAround(start, 90f, 0f, viewH / 2f, 1000, 1000, viewW, viewH)
        assertEquals(-0.5f, rotated.offsetX, 1e-5f)
        assertEquals(viewW / 2f / viewH, rotated.offsetY, 1e-5f)
        assertEquals(90f, rotated.rotationDegrees, 1e-5f)
    }

    @Test
    fun pinchClampsZoomAndOnlyMovesByTheAppliedFactor() {
        val start = ImageTransform(userScale = 9f)
        val zoomed = TransformMath.zoomAround(start, 4f, 0f, 0f, viewW, viewH)
        assertEquals(ImageTransform.MAX_SCALE, zoomed.userScale, 1e-6f)
        val applied = ImageTransform.MAX_SCALE / 9f
        assertEquals(-0.5f + 0.5f * applied, zoomed.offsetX, 1e-5f)
    }

    @Test
    fun panUsesTouchDirection() {
        val moved = TransformMath.pan(ImageTransform(), 122f, -265.6f, viewW, viewH)
        assertEquals(0.1f, moved.offsetX, 1e-6f)
        assertEquals(-0.1f, moved.offsetY, 1e-6f)
    }

    @Test
    fun rotationNormalisesAndSnapsToQuarterTurns() {
        assertEquals(-90f, normalizeDegrees(270f), 0f)
        assertEquals(180f, normalizeDegrees(-180f), 0f)
        assertEquals(180f, normalizeDegrees(540f), 0f)
        assertEquals(0f, TransformMath.snapRotation(3f), 0f)
        assertEquals(90f, TransformMath.snapRotation(87f), 0f)
        assertEquals(85f, TransformMath.snapRotation(85f), 0f)
        assertEquals(180f, TransformMath.snapRotation(-178f), 0f)
        assertEquals(-90f, TransformMath.snapRotation(-93.5f), 0f)
        assertEquals(170f, TransformMath.rotateBy(ImageTransform(rotationDegrees = -170f), -20f).rotationDegrees, 1e-4f)
    }

    @Test
    fun quarterTurnButtonsLandOnMultiplesOfNinety() {
        assertEquals(90f, TransformMath.stepQuarterTurn(ImageTransform(rotationDegrees = 10f), clockwise = true).rotationDegrees, 0f)
        assertEquals(-90f, TransformMath.stepQuarterTurn(ImageTransform(rotationDegrees = 180f), clockwise = true).rotationDegrees, 0f)
        assertEquals(-90f, TransformMath.stepQuarterTurn(ImageTransform(), clockwise = false).rotationDegrees, 0f)
        assertEquals(90f, TransformMath.stepQuarterTurn(ImageTransform(rotationDegrees = 50f), clockwise = true).rotationDegrees, 0f)
        assertEquals(0f, TransformMath.stepQuarterTurn(ImageTransform(rotationDegrees = 10f), clockwise = false).rotationDegrees, 0f)
        assertEquals(180f, TransformMath.stepQuarterTurn(ImageTransform(rotationDegrees = -90f), clockwise = false).rotationDegrees, 0f)
    }

    @Test
    fun backgroundKeepsOrientationButIgnoresPanAndZoom() {
        val background = TransformMath.backgroundTransform(
            ImageTransform(FitMode.CONTAIN, 3f, 0.4f, 0.2f, 100f, mirrorHorizontal = true),
        )
        assertEquals(FitMode.COVER, background.fitMode)
        assertEquals(1f, background.userScale, 0f)
        assertEquals(0f, background.offsetX, 0f)
        assertEquals(90f, background.rotationDegrees, 0f)
        assertTrue(background.mirrorHorizontal)
        assertTrue(TransformMath.coversViewport(background, 4000, 3000, viewW, viewH))
    }

    @Test
    fun keepVisibleStopsTheImageLeavingTheScreen() {
        val far = ImageTransform(offsetX = 5f, offsetY = -5f)
        val kept = TransformMath.keepVisible(far, 4000, 3000, viewW, viewH)
        val mvp = TransformMath.foregroundMvp(kept, 4000, 3000, viewW, viewH)
        // 10% of the 1220 px short side (122 px) stays overlapped on each clamped axis.
        assertEquals(1f - 2f * 122f / 1220f, apply(mvp, -1f, 0f).first, 1e-3f)
        assertEquals(1f - 2f * 122f / 2656f, apply(mvp, 0f, -1f).second, 1e-3f)
        assertTrue(kept.offsetX < far.offsetX)
    }

    @Test
    fun zoomedPanoramaCanBePannedToItsEnds() {
        // Covering, a 12000×2000 panorama at 5× is about 36 viewports wide; its left end must
        // still reach the right edge of the screen, which needs an offset far beyond ±10.
        val far = ImageTransform(userScale = 5f, offsetX = 40f)
        val kept = TransformMath.keepVisible(far, 12000, 2000, viewW, viewH)
        val mvp = TransformMath.foregroundMvp(kept, 12000, 2000, viewW, viewH)
        assertTrue(kept.offsetX > 30f)
        assertEquals(1f - 2f * 122f / 1220f, apply(mvp, -1f, 0f).first, 2e-3f)
    }

    @Test
    fun sanitizedReplacesNonFiniteValuesAndClamps() {
        val cleaned = ImageTransform(userScale = Float.NaN, offsetX = Float.POSITIVE_INFINITY, rotationDegrees = 450f).sanitized()
        assertEquals(1f, cleaned.userScale, 0f)
        assertEquals(0f, cleaned.offsetX, 0f)
        assertEquals(90f, cleaned.rotationDegrees, 0f)
        assertEquals(ImageTransform.MIN_SCALE, ImageTransform(userScale = 0.01f).sanitized().userScale, 0f)
        // The storage bound only stops corrupt values; a real panorama pan passes unchanged.
        assertEquals(40f, ImageTransform(offsetX = 40f).sanitized().offsetX, 0f)
        assertEquals(ImageTransform.MAX_OFFSET, ImageTransform(offsetY = 1e9f).sanitized().offsetY, 0f)
    }

    @Test
    fun codecIsCompactRoundTripsAndToleratesBadInput() {
        assertEquals("{}", TransformCodec.encode(ImageTransform()))
        val transform = ImageTransform(FitMode.FIT_HEIGHT, 2.5f, -0.2f, 0.1f, -45f, mirrorHorizontal = true, mirrorVertical = true, blurFill = false)
        assertEquals(transform, TransformCodec.decode(TransformCodec.encode(transform)))
        assertEquals(ImageTransform(), TransformCodec.decode("{}"))
        assertEquals(ImageTransform(userScale = 2f), TransformCodec.decode("""{"s":2,"future":1}"""))
        assertNull(TransformCodec.decode("""{"f":"SPIRAL"}"""))
        assertNull(TransformCodec.decode("not json"))
        assertNull(TransformCodec.decode(null))
    }

    private fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])

    private fun inverseApply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val dx = (x - m[12]).toDouble()
        val dy = (y - m[13]).toDouble()
        val det = m[0].toDouble() * m[5] - m[4].toDouble() * m[1]
        val mx = (m[5] * dx - m[4] * dy) / det
        val my = (-m[1] * dx + m[0] * dy) / det
        return mx.toFloat() to my.toFloat()
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>, delta: Float = 1e-5f) {
        assertEquals(expectedX, actual.first, delta)
        assertEquals(expectedY, actual.second, delta)
    }
}
