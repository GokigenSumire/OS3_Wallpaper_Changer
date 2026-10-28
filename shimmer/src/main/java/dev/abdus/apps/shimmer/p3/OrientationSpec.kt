package dev.abdus.apps.shimmer.p3

/**
 * How a stored bitmap must be turned to appear upright, per the EXIF `Orientation` tag.
 *
 * Applied in the order used by the platform and common loaders: rotate clockwise by
 * [rotationDegrees], then mirror horizontally when [mirrorHorizontal] is set.
 */
data class OrientationSpec(val rotationDegrees: Int, val mirrorHorizontal: Boolean) {
    /** Orientations 5–8 turn by a quarter, so the upright width/height swap. */
    val swapsDimensions: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    val isIdentity: Boolean get() = rotationDegrees == 0 && !mirrorHorizontal

    fun orientedWidth(storedWidth: Int, storedHeight: Int): Int = if (swapsDimensions) storedHeight else storedWidth

    fun orientedHeight(storedWidth: Int, storedHeight: Int): Int = if (swapsDimensions) storedWidth else storedHeight

    /**
     * Affine values in `android.graphics.Matrix` order (`MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y,
     * MSCALE_Y, MTRANS_Y, 0, 0, 1`) that map stored pixel space onto the upright bitmap, including
     * the translation back to the origin. Production and tests consume this same array.
     */
    fun matrixValues(storedWidth: Int, storedHeight: Int): FloatArray {
        val w = storedWidth.toFloat()
        val h = storedHeight.toFloat()
        // Rotation about the origin followed by the shift that brings the result back to (0, 0).
        var a: Float; var b: Float; var tx: Float
        var c: Float; var d: Float; var ty: Float
        when (rotationDegrees) {
            90 -> { a = 0f; b = -1f; tx = h; c = 1f; d = 0f; ty = 0f }
            180 -> { a = -1f; b = 0f; tx = w; c = 0f; d = -1f; ty = h }
            270 -> { a = 0f; b = 1f; tx = 0f; c = -1f; d = 0f; ty = w }
            else -> { a = 1f; b = 0f; tx = 0f; c = 0f; d = 1f; ty = 0f }
        }
        if (mirrorHorizontal) {
            val width = orientedWidth(storedWidth, storedHeight).toFloat()
            a = -a; b = -b; tx = width - tx
        }
        return floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)
    }

    companion object {
        val IDENTITY = OrientationSpec(0, false)

        /** Maps the raw EXIF value (1–8); anything else, including missing, is upright. */
        fun fromExif(orientation: Int): OrientationSpec = when (orientation) {
            2 -> OrientationSpec(0, true)
            3 -> OrientationSpec(180, false)
            4 -> OrientationSpec(180, true)
            5 -> OrientationSpec(90, true)
            6 -> OrientationSpec(90, false)
            7 -> OrientationSpec(270, true)
            8 -> OrientationSpec(270, false)
            else -> IDENTITY
        }
    }
}
