package dev.abdus.apps.shimmer.p3

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Base fit applied before the user's own zoom. Names are persisted; do not rename. */
@Serializable
enum class FitMode {
    FIT_WIDTH,
    FIT_HEIGHT,
    CONTAIN,
    COVER,
}

/**
 * One image's placement on the visible viewport.
 *
 * Applied in a fixed order: mirror in image-local space, rotate around the image centre, scale
 * uniformly, then translate. Offsets are fractions of the viewport, and the base scale is always
 * recomputed from the current image and viewport; that is what lets a saved or inherited
 * placement adapt to another surface size or image aspect ratio.
 *
 * Short serial names keep the per-history-entry copies small inside the playback snapshot.
 */
@Serializable
data class ImageTransform(
    @SerialName("f") val fitMode: FitMode = FitMode.COVER,
    @SerialName("s") val userScale: Float = 1f,
    /** Horizontal centre shift as a fraction of viewport width; positive moves right. */
    @SerialName("x") val offsetX: Float = 0f,
    /** Vertical centre shift as a fraction of viewport height; positive moves down. */
    @SerialName("y") val offsetY: Float = 0f,
    /** Degrees, positive is clockwise on screen, normalised to (-180, 180]. */
    @SerialName("r") val rotationDegrees: Float = 0f,
    @SerialName("h") val mirrorHorizontal: Boolean = false,
    @SerialName("v") val mirrorVertical: Boolean = false,
    /** Fill uncovered viewport area with the blurred whole image instead of black. */
    @SerialName("b") val blurFill: Boolean = true,
) {
    /** Finite, bounded copy. Every stored or rendered transform passes through this. */
    fun sanitized(): ImageTransform = copy(
        userScale = userScale.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE),
        offsetX = offsetX.finiteOr(0f).coerceIn(-MAX_OFFSET, MAX_OFFSET),
        offsetY = offsetY.finiteOr(0f).coerceIn(-MAX_OFFSET, MAX_OFFSET),
        rotationDegrees = normalizeDegrees(rotationDegrees.finiteOr(0f)),
    )

    companion object {
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 10f

        /**
         * Guard against corrupt values only. How far an image may move depends on its size, so
         * [TransformMath.keepVisible] sets the real limit; a zoomed panorama needs dozens of
         * viewports to reach its ends.
         */
        const val MAX_OFFSET = 1000f
    }
}

/** Degrees folded into (-180, 180]. */
fun normalizeDegrees(degrees: Float): Float {
    var value = degrees % 360f
    if (value <= -180f) value += 360f
    if (value > 180f) value -= 360f
    return value
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

/**
 * Text form used for per-image saved transforms.
 *
 * Decoding never throws: an unreadable value (for example a fit mode written by a newer version)
 * reads as absent, so the image falls back to the global default instead of failing to load.
 */
object TransformCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(transform: ImageTransform): String =
        json.encodeToString(ImageTransform.serializer(), transform.sanitized())

    fun decode(value: String?): ImageTransform? {
        if (value.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(ImageTransform.serializer(), value).sanitized() }.getOrNull()
    }
}
