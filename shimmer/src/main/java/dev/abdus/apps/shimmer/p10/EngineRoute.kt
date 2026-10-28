package dev.abdus.apps.shimmer.p10

/**
 * Classification of actual flags observed from an attached platform Engine, not a requested
 * component, selected page, keyguard state or guessed target. Read flags no earlier than onCreate.
 *
 * This is routing evidence only. It does not authorize a storage write or prove that a target has
 * its own Surface. Registration, engine/session generations and request admission remain separate.
 */
data class EngineRoute(val observedFlags: Int?, val isPreview: Boolean) {
    enum class Exposure { UNKNOWN, HOME_ONLY, LOCK_ONLY, SHARED, UNSUPPORTED }

    val exposure: Exposure
        get() = when (observedFlags) {
            null -> Exposure.UNKNOWN
            1 -> Exposure.HOME_ONLY
            2 -> Exposure.LOCK_ONLY
            3 -> Exposure.SHARED
            else -> Exposure.UNSUPPORTED
        }

    /** Shared/unknown/unsupported flags are deliberately not coerced to a single target. */
    val singleObservedTarget: WallpaperTarget?
        get() = when (exposure) {
            Exposure.HOME_ONLY -> WallpaperTarget.HOME
            Exposure.LOCK_ONLY -> WallpaperTarget.LOCK
            else -> null
        }

    /** A preview can carry target evidence but must never become a committed formal domain. */
    val formalTarget: WallpaperTarget?
        get() = if (isPreview) null else singleObservedTarget
}
