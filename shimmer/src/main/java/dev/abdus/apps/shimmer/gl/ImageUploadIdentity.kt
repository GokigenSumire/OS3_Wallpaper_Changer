package dev.abdus.apps.shimmer.gl

/** Owner-thread only. Reuse a completed upload by exact owned references, never hash/equals. */
internal class ImageUploadIdentity {
    private var owner: Any? = null
    private var original: Any? = null
    private var blurred: List<Any> = emptyList()
    private var background: Any? = null

    fun canReuse(candidate: Any, foreground: Any, blurLayers: List<Any>, fill: Any?, texturesReady: Boolean): Boolean =
        texturesReady && owner === candidate && original === foreground && background === fill &&
            blurred.size == blurLayers.size && blurred.indices.all { blurred[it] === blurLayers[it] }

    /** Called only after every foreground/background upload returned successfully. */
    fun record(candidate: Any, foreground: Any, blurLayers: List<Any>, fill: Any?) {
        owner = candidate
        original = foreground
        blurred = blurLayers.toList()
        background = fill
    }

    /** Clear before changing textures, on a failed attempt, or when the renderer is released. */
    fun clear() {
        owner = null
        original = null
        blurred = emptyList()
        background = null
    }
}
