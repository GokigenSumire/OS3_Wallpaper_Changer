package dev.abdus.apps.shimmer.p10

import dev.abdus.apps.shimmer.ShimmerWallpaperService

/** Independent native image service. Only an actually LOCK_ONLY Engine may write this domain. */
class LockWallpaperService : ShimmerWallpaperService() {
    override val wallpaperTarget: WallpaperTarget = WallpaperTarget.LOCK
}
