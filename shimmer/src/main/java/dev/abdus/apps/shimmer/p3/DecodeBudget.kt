package dev.abdus.apps.shimmer.p3

import kotlin.math.max

/**
 * Chooses the `inSampleSize` for a foreground decode (REQ-SRC-05).
 *
 * Detail: the largest power of two whose decoded pixels are magnified at most [DETAIL_TOLERANCE]×
 * on screen, which matches the P2 rule of keeping at least 80 % of the needed resolution.
 * Caps: the decoded image stays within [MAX_PIXELS] and the GL maximum texture edge.
 */
object DecodeBudget {
    const val MAX_PIXELS = 16_800_000L
    const val DETAIL_TOLERANCE = 1.25f
    /** Used until the renderer has reported GL_MAX_TEXTURE_SIZE; every GLES 2 device supports it. */
    const val FALLBACK_MAX_TEXTURE_SIZE = 4096
    private const val MAX_SAMPLE_SIZE = 1 shl 12

    /**
     * @param orientedWidth upright source width in pixels (after EXIF orientation)
     * @param displayScale on-screen pixels per source pixel for the placement being shown
     */
    fun sampleSize(orientedWidth: Int, orientedHeight: Int, displayScale: Float, maxTextureSize: Int): Int {
        if (orientedWidth <= 0 || orientedHeight <= 0) return 1
        val scale = if (displayScale.isFinite() && displayScale > 0f) displayScale else 1f
        var sample = 1
        while (sample < MAX_SAMPLE_SIZE && scale * sample * 2 <= DETAIL_TOLERANCE) sample *= 2
        val textureEdge = if (maxTextureSize > 0) maxTextureSize else FALLBACK_MAX_TEXTURE_SIZE
        while (sample < MAX_SAMPLE_SIZE && !fits(orientedWidth, orientedHeight, sample, textureEdge)) sample *= 2
        return sample
    }

    /** Decoded size for [sample]; rounded up because some decoders keep the partial last block. */
    fun sampledEdge(edge: Int, sample: Int): Int = max(1, (edge + sample - 1) / sample)

    private fun fits(width: Int, height: Int, sample: Int, textureEdge: Int): Boolean {
        val w = sampledEdge(width, sample)
        val h = sampledEdge(height, sample)
        return w <= textureEdge && h <= textureEdge && w.toLong() * h <= MAX_PIXELS
    }
}
