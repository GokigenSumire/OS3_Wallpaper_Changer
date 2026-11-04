package dev.abdus.apps.shimmer.media

/** Derived from the currently opened original; never persisted as an unchecked file offset. */
enum class MediaKind { IMAGE, VIDEO, MOTION_PHOTO }
enum class MotionMode { LOOP, ONCE, STILL }

data class MediaByteRange(val offset: Long, val length: Long) {
    init { require(offset >= 0 && length > 0 && offset <= Long.MAX_VALUE - length) }
    fun fits(total: Long): Boolean = total >= 0 && offset <= total && length <= total - offset
    fun subrange(position: Long, requested: Long = -1): MediaByteRange {
        require(position >= 0 && position < length && requested >= -1)
        val remaining = length - position
        return MediaByteRange(offset + position, if (requested < 0) remaining else minOf(remaining, requested))
    }
}

data class MediaClip(
    val uri: String,
    val kind: MediaKind,
    val range: MediaByteRange? = null,
    val durationMs: Long? = null,
    val photoTimestampUs: Long? = null,
    val hdr: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    init {
        require(kind != MediaKind.IMAGE)
        require(uri.substringBefore(':').lowercase() in setOf("content", "file", "android.resource"))
        require(photoTimestampUs == null || photoTimestampUs >= 0)
    }
}

object MediaFrameGeometry {
    /** Center crop in logical upright UVs; never stretch a video to its photograph's aspect. */
    fun crop(sourceAspect: Float, targetAspect: Float): Pair<Float, Float> {
        require(sourceAspect.isFinite() && sourceAspect > 0 && targetAspect.isFinite() && targetAspect > 0)
        return if (sourceAspect > targetAspect) targetAspect / sourceAspect to 1f
        else 1f to sourceAspect / targetAspect
    }
}

object MediaSourceBounds {
    /** AFD statSize covers the physical file; an unknown declared length still starts at offset. */
    fun length(declared: Long, stat: Long, start: Long): Long {
        if (start < 0 || stat >= 0 && start > stat) return -1
        if (declared >= 0) return if (stat >= 0 && declared > stat - start) -1 else declared
        return if (stat >= 0) stat - start else -1
    }
}

object MediaFileTypes {
    private val video = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "3gpp", "ts", "m2ts")
    fun isVideo(name: String, mime: String? = null): Boolean =
        mime?.startsWith("video/", ignoreCase = true) == true || name.substringBefore('?').substringAfterLast('.', "").lowercase() in video
    fun isMedia(name: String, mime: String? = null): Boolean = isVideo(name, mime) ||
        mime?.startsWith("image/", ignoreCase = true) == true ||
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
}
