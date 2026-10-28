package dev.abdus.apps.shimmer.p10

/** Preview is memory-only. HOME retains legacy/shared support, not explicit LOCK-only routing. */
object ProductionRoutePolicy {
    fun allowed(target: WallpaperTarget, actualFlags: Int?, preview: Boolean, legacyHome: Boolean = false): Boolean =
        preview || when (target) {
            WallpaperTarget.HOME -> (legacyHome && actualFlags == null) || actualFlags == 1 || actualFlags == 3
            WallpaperTarget.LOCK -> actualFlags == 2
        }
}
