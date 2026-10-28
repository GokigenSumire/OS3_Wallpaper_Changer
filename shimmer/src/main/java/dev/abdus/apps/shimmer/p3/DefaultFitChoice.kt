package dev.abdus.apps.shimmer.p3

import dev.abdus.apps.shimmer.WallpaperPreferences

/**
 * The global default placement as the user picks it: smart fit, or one fixed fit mode.
 *
 * [P3Defaults] stores smart fit as COVER plus a flag, so cycling over [FitMode] alone showed
 * "铺满" while smart fit was active and the first tap silently left smart fit. Every page that
 * cycles the default goes through this list instead.
 */
enum class DefaultFitChoice(val label: String, val fitMode: FitMode?) {
    SMART("智能", null),
    COVER("铺满", FitMode.COVER),
    FIT_WIDTH("适宽", FitMode.FIT_WIDTH),
    FIT_HEIGHT("适高", FitMode.FIT_HEIGHT),
    CONTAIN("完整", FitMode.CONTAIN);

    fun next(): DefaultFitChoice = entries[(ordinal + 1) % entries.size]

    fun save(preferences: WallpaperPreferences) {
        val mode = fitMode
        if (mode == null) preferences.setP3SmartFit() else preferences.setP3DefaultFitMode(mode)
    }

    companion object {
        /** Smart fit wins over the stored fallback mode, the same way placement resolves it. */
        fun of(defaults: P3Defaults): DefaultFitChoice =
            if (defaults.smartFit) SMART else entries.first { it.fitMode == defaults.fitMode }
    }
}
