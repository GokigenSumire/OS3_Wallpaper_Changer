package dev.abdus.apps.shimmer.p3

import dev.abdus.apps.shimmer.p10.WallpaperTarget

/**
 * In-process link between the P3 editor and the formal wallpaper Engine it edits. Main thread
 * only. The editor drives that Engine's renderer directly, so a drag never touches storage and
 * only a save is persisted.
 */
object LiveEditBridge {
    interface Target {
        /** Starts editing the image on screen; null when there is nothing editable. */
        fun beginEdit(): EditSession?
    }

    /** The image under edit, captured when the session starts. */
    data class EditImage(
        val imageId: String,
        /** Upright full-resolution size; placement math uses it, not the decoded size. */
        val sourceWidth: Int,
        val sourceHeight: Int,
        /** The wallpaper surface, which is what offsets and zoom focal points are relative to. */
        val viewWidth: Int,
        val viewHeight: Int,
        /** The placement shown when editing started; cancel returns to it. */
        val original: ImageTransform,
        val defaults: P3Defaults,
        /** Null when not applicable (e.g., default image); otherwise true if enabled, false if disabled. */
        val enabled: Boolean? = true,
    )

    sealed interface StepResult {
        data class Success(val image: EditImage) : StepResult
        data class Failed(val reason: String) : StepResult
        object NoCandidate : StepResult
    }

    interface EditSession {
        var image: EditImage

        /** False once saved or cancelled, or when the Engine went away. */
        val active: Boolean

        /** Shows [transform] on the wallpaper without saving it. */
        fun preview(transform: ImageTransform, inputUptimeMillis: Long)

        /** Persists [transform] without closing the session; null removes the image's config. */
        fun apply(transform: ImageTransform?)

        /** Commits [transform] and closes the session; null removes the image's config. */
        fun save(transform: ImageTransform?, saveUptimeMillis: Long)

        /** Restores [EditImage.original]; nothing is written. */
        fun cancel()

        /** Engine-side part of the editor's measurement line. */
        fun statusLine(): String

        /**
         * Steps to the next or previous image through the coordinator. Saves or discards the
         * draft first, then commits the step and updates [image]. Callback is invoked on Main
         * when done, or with null if the session became inactive.
         */
        fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (StepResult?) -> Unit)
    }

    private val targets = LinkedHashMap<String, Target>()
    private val domains = LinkedHashMap<String, WallpaperTarget>()
    private var lastVisible: String? = null
    private val lastVisibleByDomain = LinkedHashMap<WallpaperTarget, String>()

    fun register(id: String, target: Target, wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME) {
        targets[id] = target
        domains[id] = wallpaperTarget
    }

    fun unregister(id: String) {
        targets.remove(id)
        domains.remove(id)
        lastVisibleByDomain.entries.removeAll { it.value == id }
        if (lastVisible == id) lastVisible = null
    }

    fun markVisible(id: String) {
        if (id in targets) {
            lastVisible = id
            domains[id]?.let { lastVisibleByDomain[it] = id }
        }
    }

    /** A specific stale ID fails closed; only an unspecified ID may select within the same domain. */
    fun find(id: String?, wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME): Target? {
        if (id != null) return targets[id]?.takeIf { domains[id] == wallpaperTarget }
        val selected = lastVisibleByDomain[wallpaperTarget]?.takeIf { it in targets && domains[it] == wallpaperTarget }
            ?: targets.keys.lastOrNull { domains[it] == wallpaperTarget }
        return selected?.let(targets::get)
    }
}
