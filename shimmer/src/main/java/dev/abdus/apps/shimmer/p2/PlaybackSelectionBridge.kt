package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p10.WallpaperTarget

/** Main-thread, in-process only. A selected row is an intent, never a saved displayed snapshot. */
object PlaybackSelectionBridge {
    enum class Result { ACCEPTED, QUEUED, NO_ENGINE, BUSY_EDIT, NOOP }
    interface Target { fun select(itemId: String): Result }
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
    fun markVisible(id: String) { if (id in targets) {
        lastVisible = id
        domains[id]?.let { lastVisibleByDomain[it] = id }
    } }
    fun submit(itemId: String, wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME): Result {
        if (itemId.isBlank()) return Result.NOOP
        val selected = lastVisibleByDomain[wallpaperTarget]?.takeIf { domains[it] == wallpaperTarget && it in targets }
            ?: targets.keys.lastOrNull { domains[it] == wallpaperTarget }
            ?: return Result.NO_ENGINE
        val target = targets[selected] ?: return Result.NO_ENGINE
        return target.select(itemId)
    }
}
