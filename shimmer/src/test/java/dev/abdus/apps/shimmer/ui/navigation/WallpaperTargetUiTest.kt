package dev.abdus.apps.shimmer.ui.navigation

import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test

class WallpaperTargetUiTest {
    @Test fun everyPageHasSeparateTargetSavedStateKeys() {
        val keys = WallpaperTarget.entries.flatMap { target ->
            listOf("gallery", "playback", "effects", "settings").map { WallpaperTargetUi.stateKey(it, target) }
        }
        assertEquals(8, keys.toSet().size)
        assertEquals("HOME:playback", WallpaperTargetUi.stateKey("playback", WallpaperTarget.HOME))
        assertEquals("LOCK:playback", WallpaperTargetUi.stateKey("playback", WallpaperTarget.LOCK))
    }

    @Test fun sameViewModelClassIsKeyedByTargetAndDifferentClassesDoNotShareKeys() {
        assertNotEquals(WallpaperTargetUi.viewModelKey("Gallery", WallpaperTarget.HOME),
            WallpaperTargetUi.viewModelKey("Gallery", WallpaperTarget.LOCK))
        assertNotEquals(WallpaperTargetUi.viewModelKey("Gallery", WallpaperTarget.LOCK),
            WallpaperTargetUi.viewModelKey("Playback", WallpaperTarget.LOCK))
    }

    @Test fun restoredTargetIsExactAndInvalidSavedNameSafelyFallsBackHome() {
        assertEquals(WallpaperTarget.LOCK, WallpaperTargetUi.restoreTarget("LOCK"))
        assertEquals(WallpaperTarget.HOME, WallpaperTargetUi.restoreTarget("HOME"))
        assertEquals(WallpaperTarget.HOME, WallpaperTargetUi.restoreTarget("lock"))
        assertEquals(WallpaperTarget.HOME, WallpaperTargetUi.restoreTarget(""))
        assertEquals(WallpaperTarget.HOME, WallpaperTargetUi.restoreTarget("SHARED"))
    }

    @Test fun targetLabelsAreExplicitAndLockCannotReachLegacyHomeEditor() {
        assertEquals("桌面 HOME", WallpaperTargetUi.label(WallpaperTarget.HOME))
        assertEquals("锁屏 LOCK", WallpaperTargetUi.label(WallpaperTarget.LOCK))
        assertTrue(WallpaperTargetUi.canOpenLegacyEditor(WallpaperTarget.HOME))
        assertFalse(WallpaperTargetUi.canOpenLegacyEditor(WallpaperTarget.LOCK))
    }

    @Test fun staleBackdropReadCannotPublishAfterNewReadInSameTarget() {
        val reads = TargetBackdropReads()
        val old = reads.begin()
        val current = reads.begin()
        assertFalse(reads.isLatest(old))
        assertTrue(reads.isLatest(current))
    }

    @Test fun backdropReadCountersArePerTargetAndDoNotInvalidateTheOtherDomain() {
        val home = TargetBackdropReads()
        val lock = TargetBackdropReads()
        val homeRead = home.begin()
        val lockRead = lock.begin()
        lock.begin()
        assertTrue(home.isLatest(homeRead))
        assertFalse(lock.isLatest(lockRead))
    }
}
