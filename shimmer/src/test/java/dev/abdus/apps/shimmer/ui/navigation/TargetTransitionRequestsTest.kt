package dev.abdus.apps.shimmer.ui.navigation

import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test

class TargetTransitionRequestsTest {
    @Test fun recompositionOfSameTargetKeepsRequestIdentity() {
        val owner = TargetTransitionRequests(WallpaperTarget.HOME)
        val first = owner.select(WallpaperTarget.HOME)
        assertSame(first, owner.select(WallpaperTarget.HOME))
    }
    @Test fun rapidReturnInvalidatesEarlierRequestEvenWithSameTarget() {
        val owner = TargetTransitionRequests(WallpaperTarget.HOME)
        val first = owner.select(WallpaperTarget.HOME)
        val lock = owner.select(WallpaperTarget.LOCK)
        val latest = owner.select(WallpaperTarget.HOME)
        assertFalse(owner.isCurrent(first))
        assertFalse(owner.isCurrent(lock))
        assertTrue(owner.isCurrent(latest))
    }
    @Test fun anotherOwnersTicketCannotCommit() {
        val owner = TargetTransitionRequests(WallpaperTarget.HOME)
        assertFalse(owner.isCurrent(TargetTransitionRequests(WallpaperTarget.HOME).select(WallpaperTarget.HOME)))
    }
}
