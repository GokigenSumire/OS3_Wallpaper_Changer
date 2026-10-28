package dev.abdus.apps.shimmer.p10.capability

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Constructing an Engine must not read its not-yet-attached platform wrapper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityServiceConstructionTest {
    @Test fun homeEngineCanBeConstructedBeforePlatformAttach() {
        val controller = Robolectric.buildService(HomeCapabilityWallpaperService::class.java).create()
        try {
            val first = controller.get().onCreateEngine()
            val second = controller.get().onCreateEngine()
            assertNotSame(first, second)
        } finally { controller.destroy() }
    }

    @Test fun lockEngineCanBeConstructedBeforePlatformAttach() {
        val controller = Robolectric.buildService(LockCapabilityWallpaperService::class.java).create()
        try { assertNotNull(controller.get().onCreateEngine()) }
        finally { controller.destroy() }
    }
}
