package dev.abdus.apps.shimmer.p10

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionRoutePolicyTest {
    @Test fun homeAcceptsOnlyHomeOrExplicitSharedOnModernApi() {
        assertTrue(ProductionRoutePolicy.allowed(WallpaperTarget.HOME, 1, false))
        assertTrue(ProductionRoutePolicy.allowed(WallpaperTarget.HOME, 3, false))
        listOf(null, 0, 2, 4, -1).forEach {
            assertFalse("HOME flags=$it", ProductionRoutePolicy.allowed(WallpaperTarget.HOME, it, false))
        }
    }

    @Test fun onlyLegacyHomeCanUseUnavailableFlags() {
        assertTrue(ProductionRoutePolicy.allowed(WallpaperTarget.HOME, null, false, legacyHome = true))
        assertFalse(ProductionRoutePolicy.allowed(WallpaperTarget.HOME, 2, false, legacyHome = true))
        assertFalse(ProductionRoutePolicy.allowed(WallpaperTarget.LOCK, null, false, legacyHome = true))
    }

    @Test fun lockNeverGuessesFromUnknownOrShared() {
        assertTrue(ProductionRoutePolicy.allowed(WallpaperTarget.LOCK, 2, false))
        listOf(null, 0, 1, 3, 4, -1).forEach {
            assertFalse("LOCK flags=$it", ProductionRoutePolicy.allowed(WallpaperTarget.LOCK, it, false))
        }
    }

    @Test fun previewMayRenderButDoesNotGrantProductionRights() {
        WallpaperTarget.entries.forEach { target ->
            listOf(null, 0, 1, 2, 3, 4).forEach {
                assertTrue(ProductionRoutePolicy.allowed(target, it, true))
            }
        }
    }
}
