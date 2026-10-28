package dev.abdus.apps.shimmer.p3

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure placement math shared by the renderer, the editor and the tests.
 *
 * Pixel space is centred on the viewport with y pointing up; the image is the oriented (EXIF
 * applied) full-resolution size, so results do not depend on the decode sample size.
 */
object TransformMath {
    /** Rotation within this many degrees of a multiple of 90 snaps onto it. */
    const val SNAP_DEGREES = 4f

    /** Minimum share of the viewport's shorter side that must stay covered by the image. */
    const val MIN_VISIBLE_FRACTION = 0.1f

    /** Scale from image pixels to viewport pixels for [mode] at [rotationDegrees], before user zoom. */
    fun baseScale(
        mode: FitMode,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        rotationDegrees: Float = 0f,
    ): Float {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val w = imageWidth.toDouble()
        val h = imageHeight.toDouble()
        val vw = viewWidth.toDouble()
        val vh = viewHeight.toDouble()
        // Axis-aligned bounds of the rotated image are (w·c + h·s) × (w·s + h·c).
        val fitWidth = vw / (w * c + h * s)
        val fitHeight = vh / (w * s + h * c)
        val scale = when (mode) {
            FitMode.FIT_WIDTH -> fitWidth
            FitMode.FIT_HEIGHT -> fitHeight
            FitMode.CONTAIN -> min(fitWidth, fitHeight)
            // Smallest scale whose rotated rectangle still contains all four viewport corners.
            FitMode.COVER -> max((vw * c + vh * s) / w, (vw * s + vh * c) / h)
        }
        return scale.toFloat()
    }

    /** Effective image-to-viewport pixel scale including the user's zoom. */
    fun totalScale(transform: ImageTransform, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Float =
        baseScale(transform.fitMode, imageWidth, imageHeight, viewWidth, viewHeight, transform.rotationDegrees) *
            transform.userScale

    /**
     * Column-major model-view-projection matrix for the unit quad [-1, 1]², whose texture origin
     * is the image's top-left corner.
     */
    fun foregroundMvp(
        transform: ImageTransform,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray {
        val matrix = FloatArray(16)
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            matrix[0] = 1f; matrix[5] = 1f; matrix[10] = 1f; matrix[15] = 1f
            return matrix
        }
        val scale = totalScale(transform, imageWidth, imageHeight, viewWidth, viewHeight).toDouble()
        val a = scale * imageWidth / viewWidth
        val b = scale * imageHeight / viewHeight
        val mx = if (transform.mirrorHorizontal) -1.0 else 1.0
        val my = if (transform.mirrorVertical) -1.0 else 1.0
        val ratio = viewWidth.toDouble() / viewHeight
        val radians = Math.toRadians(transform.rotationDegrees.toDouble())
        val c = cos(radians)
        val s = sin(radians)
        // Clockwise on screen in y-up pixel space is [c, s; -s, c]; converting pixels to clip
        // space divides x by W/2 and y by H/2, which is where the aspect ratio terms come from.
        matrix[0] = (a * mx * c).toFloat()
        matrix[1] = (-a * mx * s * ratio).toFloat()
        matrix[4] = (b * my * s / ratio).toFloat()
        matrix[5] = (b * my * c).toFloat()
        matrix[10] = 1f
        matrix[12] = 2f * transform.offsetX
        matrix[13] = -2f * transform.offsetY
        matrix[15] = 1f
        return matrix
    }

    /**
     * Matrix for the blurred fill layer: the same mirror and nearest quarter turn as the image,
     * cover-fitted and centred, deliberately ignoring pan and zoom so the fill stays calm.
     */
    fun backgroundMvp(transform: ImageTransform, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): FloatArray =
        foregroundMvp(backgroundTransform(transform), imageWidth, imageHeight, viewWidth, viewHeight)

    fun backgroundTransform(transform: ImageTransform): ImageTransform = ImageTransform(
        fitMode = FitMode.COVER,
        rotationDegrees = nearestQuarterTurn(transform.rotationDegrees),
        mirrorHorizontal = transform.mirrorHorizontal,
        mirrorVertical = transform.mirrorVertical,
        blurFill = transform.blurFill,
    )

    /**
     * Inverse of [foregroundMvp]'s 2-D affine part as a column-major 3×3 matrix mapping clip
     * space back to the quad's [-1, 1]²; null when the matrix is degenerate.
     */
    fun clipToQuad(mvp: FloatArray): FloatArray? {
        val a = mvp[0].toDouble(); val c = mvp[4].toDouble(); val tx = mvp[12].toDouble()
        val b = mvp[1].toDouble(); val d = mvp[5].toDouble(); val ty = mvp[13].toDouble()
        val det = a * d - b * c
        if (!det.isFinite() || abs(det) < 1e-12) return null
        val ia = d / det; val ic = -c / det
        val ib = -b / det; val id = a / det
        return floatArrayOf(
            ia.toFloat(), ib.toFloat(), 0f,
            ic.toFloat(), id.toFloat(), 0f,
            (-(ia * tx + ic * ty)).toFloat(), (-(ib * tx + id * ty)).toFloat(), 1f,
        )
    }

    /** True when every viewport pixel is inside the transformed image, so no fill is visible. */
    fun coversViewport(transform: ImageTransform, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return false
        val scale = totalScale(transform, imageWidth, imageHeight, viewWidth, viewHeight).toDouble()
        if (scale <= 0.0) return false
        val radians = Math.toRadians(transform.rotationDegrees.toDouble())
        val c = cos(radians)
        val s = sin(radians)
        val centreX = transform.offsetX.toDouble() * viewWidth
        val centreY = -transform.offsetY.toDouble() * viewHeight
        val halfW = imageWidth / 2.0
        val halfH = imageHeight / 2.0
        // Half a viewport pixel of slack absorbs float error on exact fits.
        val tolerance = 0.5 / scale
        for (cornerX in doubleArrayOf(-viewWidth / 2.0, viewWidth / 2.0)) {
            for (cornerY in doubleArrayOf(-viewHeight / 2.0, viewHeight / 2.0)) {
                val px = (cornerX - centreX) / scale
                val py = (cornerY - centreY) / scale
                // Inverse of the clockwise rotation. Mirroring maps the rectangle onto itself.
                val u = c * px - s * py
                val v = s * px + c * py
                if (abs(u) > halfW + tolerance || abs(v) > halfH + tolerance) return false
            }
        }
        return true
    }

    /** Moves the image by a finger delta given in viewport pixels (y down, as in touch events). */
    fun pan(transform: ImageTransform, dxPixels: Float, dyPixels: Float, viewWidth: Int, viewHeight: Int): ImageTransform {
        if (viewWidth <= 0 || viewHeight <= 0) return transform
        return transform.copy(
            offsetX = transform.offsetX + dxPixels / viewWidth,
            offsetY = transform.offsetY + dyPixels / viewHeight,
        )
    }

    /**
     * Multiplies the zoom by [factor] while keeping the viewport point under the fingers fixed.
     * Focal coordinates are touch pixels (origin top-left, y down).
     */
    fun zoomAround(
        transform: ImageTransform,
        factor: Float,
        focalX: Float,
        focalY: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): ImageTransform {
        if (viewWidth <= 0 || viewHeight <= 0 || !factor.isFinite() || factor <= 0f) return transform
        val newScale = (transform.userScale * factor).coerceIn(ImageTransform.MIN_SCALE, ImageTransform.MAX_SCALE)
        val applied = newScale / transform.userScale
        // Offsets and focal point in the same fraction-of-viewport units, measured from the centre.
        val focalFractionX = focalX / viewWidth - 0.5f
        val focalFractionY = focalY / viewHeight - 0.5f
        return transform.copy(
            userScale = newScale,
            offsetX = focalFractionX + (transform.offsetX - focalFractionX) * applied,
            offsetY = focalFractionY + (transform.offsetY - focalFractionY) * applied,
        )
    }

    /** Adds [deltaDegrees] (clockwise positive) around the image centre. */
    fun rotateBy(transform: ImageTransform, deltaDegrees: Float): ImageTransform =
        transform.copy(rotationDegrees = normalizeDegrees(transform.rotationDegrees + deltaDegrees))

    /**
     * Rotates by [deltaDegrees] (clockwise positive) around a viewport point instead of the image
     * centre, so a two-finger twist keeps the image point between the fingers in place. The base
     * scale follows the angle (see [baseScale]), so that change is applied around the same point.
     * Focal coordinates are touch pixels (origin top-left, y down).
     */
    fun rotateAround(
        transform: ImageTransform,
        deltaDegrees: Float,
        focalX: Float,
        focalY: Float,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): ImageTransform {
        if (viewWidth <= 0 || viewHeight <= 0 || !deltaDegrees.isFinite()) return transform
        val rotated = transform.copy(rotationDegrees = normalizeDegrees(transform.rotationDegrees + deltaDegrees))
        val before = totalScale(transform, imageWidth, imageHeight, viewWidth, viewHeight).toDouble()
        val after = totalScale(rotated, imageWidth, imageHeight, viewWidth, viewHeight).toDouble()
        val grow = if (before > 0.0 && after.isFinite()) after / before else 1.0
        // Image centre relative to the focal point, in pixels (y down, so clockwise is +angle).
        val dx = transform.offsetX.toDouble() * viewWidth + viewWidth / 2.0 - focalX
        val dy = transform.offsetY.toDouble() * viewHeight + viewHeight / 2.0 - focalY
        val radians = Math.toRadians(deltaDegrees.toDouble())
        val c = cos(radians)
        val s = sin(radians)
        val centreX = focalX + grow * (dx * c - dy * s)
        val centreY = focalY + grow * (dx * s + dy * c)
        return rotated.copy(
            offsetX = (centreX / viewWidth - 0.5).toFloat(),
            offsetY = (centreY / viewHeight - 0.5).toFloat(),
        )
    }

    /** Snaps onto a multiple of 90 degrees when within [SNAP_DEGREES]. */
    fun snapRotation(degrees: Float, threshold: Float = SNAP_DEGREES): Float {
        val normalized = normalizeDegrees(degrees)
        val nearest = nearestQuarterTurn(normalized)
        // Compare the angular distance so -178 still snaps to 180.
        return if (abs(normalizeDegrees(normalized - nearest)) <= threshold) nearest else normalized
    }

    /** The multiple of 90 closest to [degrees], normalised to (-180, 180]. */
    fun nearestQuarterTurn(degrees: Float): Float =
        normalizeDegrees(((normalizeDegrees(degrees) / 90f).roundToInt() * 90).toFloat())

    /**
     * Button rotation: the next quarter turn in the chosen direction. From an exact quarter turn
     * that is a full 90 degrees; from a free angle it first lands on the neighbouring quarter.
     */
    fun stepQuarterTurn(transform: ImageTransform, clockwise: Boolean): ImageTransform {
        val quarters = normalizeDegrees(transform.rotationDegrees) / 90f
        val target = if (clockwise) ceil(quarters + QUARTER_EPSILON) else floor(quarters - QUARTER_EPSILON)
        return transform.copy(rotationDegrees = normalizeDegrees(target * 90f))
    }

    private const val QUARTER_EPSILON = 1e-3f

    /**
     * Limits offsets so at least [MIN_VISIBLE_FRACTION] of the viewport's shorter side stays over
     * the image. Uses the rotated bounding box, which is exact at quarter turns and slightly
     * permissive in between.
     */
    fun keepVisible(transform: ImageTransform, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): ImageTransform {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return transform
        val scale = totalScale(transform, imageWidth, imageHeight, viewWidth, viewHeight).toDouble()
        val radians = Math.toRadians(transform.rotationDegrees.toDouble())
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val boundsW = scale * (imageWidth * c + imageHeight * s)
        val boundsH = scale * (imageWidth * s + imageHeight * c)
        val margin = MIN_VISIBLE_FRACTION * min(viewWidth, viewHeight)
        val visibleW = min(margin.toDouble(), boundsW)
        val visibleH = min(margin.toDouble(), boundsH)
        val limitX = ((viewWidth + boundsW) / 2.0 - visibleW) / viewWidth
        val limitY = ((viewHeight + boundsH) / 2.0 - visibleH) / viewHeight
        return transform.copy(
            offsetX = transform.offsetX.coerceIn(-limitX.toFloat(), limitX.toFloat()),
            offsetY = transform.offsetY.coerceIn(-limitY.toFloat(), limitY.toFloat()),
        ).sanitized()
    }
}
