package dev.abdus.apps.shimmer.p10

/**
 * Independent committed-state domains. Preview is a session, never a third persistence domain.
 * Existing unscoped application data belongs to [HOME]; [LOCK] starts empty.
 */
enum class WallpaperTarget {
    HOME,
    LOCK,
}
