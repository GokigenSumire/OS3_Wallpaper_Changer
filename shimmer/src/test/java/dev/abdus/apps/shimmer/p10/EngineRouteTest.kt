package dev.abdus.apps.shimmer.p10

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineRouteTest {
    @Test fun formalHomeUsesActualExclusiveFlags() {
        val route = EngineRoute(1, false)
        assertEquals(EngineRoute.Exposure.HOME_ONLY, route.exposure)
        assertEquals(WallpaperTarget.HOME, route.singleObservedTarget)
        assertEquals(WallpaperTarget.HOME, route.formalTarget)
    }

    @Test fun formalLockUsesActualExclusiveFlags() {
        val route = EngineRoute(2, false)
        assertEquals(EngineRoute.Exposure.LOCK_ONLY, route.exposure)
        assertEquals(WallpaperTarget.LOCK, route.singleObservedTarget)
        assertEquals(WallpaperTarget.LOCK, route.formalTarget)
    }

    @Test fun absentFlagsAreUnknownRatherThanHome() {
        val route = EngineRoute(null, false)
        assertEquals(EngineRoute.Exposure.UNKNOWN, route.exposure)
        assertNull(route.singleObservedTarget)
        assertNull(route.formalTarget)
    }

    @Test fun sharedFlagsNeverChooseOneDomain() {
        val route = EngineRoute(3, false)
        assertEquals(EngineRoute.Exposure.SHARED, route.exposure)
        assertNull(route.singleObservedTarget)
        assertNull(route.formalTarget)
    }

    @Test fun homePreviewCarriesEvidenceButNeverFormalOwnership() {
        val route = EngineRoute(1, true)
        assertEquals(EngineRoute.Exposure.HOME_ONLY, route.exposure)
        assertEquals(WallpaperTarget.HOME, route.singleObservedTarget)
        assertNull(route.formalTarget)
    }

    @Test fun lockPreviewCarriesEvidenceButNeverFormalOwnership() {
        val route = EngineRoute(2, true)
        assertEquals(EngineRoute.Exposure.LOCK_ONLY, route.exposure)
        assertEquals(WallpaperTarget.LOCK, route.singleObservedTarget)
        assertNull(route.formalTarget)
    }

    @Test fun sharedPreviewIsStillShared() {
        val route = EngineRoute(3, true)
        assertEquals(EngineRoute.Exposure.SHARED, route.exposure)
        assertNull(route.singleObservedTarget)
        assertNull(route.formalTarget)
    }

    @Test fun zeroFlagsAreUnsupportedNotAnImplicitHomeDefault() {
        assertUnsupported(0)
    }

    @Test fun negativeFlagsAreNotMaskedIntoShared() {
        assertUnsupported(-1)
        assertUnsupported(Int.MIN_VALUE)
    }

    @Test fun unknownBitsDoNotAuthorizeEitherKnownTarget() {
        listOf(4, 5, 6, 7, Int.MAX_VALUE).forEach(::assertUnsupported)
    }

    @Test fun observedRawValueAndPreviewArePartOfRouteIdentity() {
        assertEquals(EngineRoute(1, false), EngineRoute(1, false))
        assertNotEquals(EngineRoute(1, false), EngineRoute(1, true))
        assertNotEquals(EngineRoute(1, false), EngineRoute(2, false))
        assertEquals(5, EngineRoute(5, false).observedFlags)
    }

    private fun assertUnsupported(flags: Int) {
        listOf(false, true).forEach { preview ->
            val route = EngineRoute(flags, preview)
            assertEquals(EngineRoute.Exposure.UNSUPPORTED, route.exposure)
            assertNull(route.singleObservedTarget)
            assertNull(route.formalTarget)
        }
    }
}
