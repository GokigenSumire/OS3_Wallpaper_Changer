package dev.abdus.apps.shimmer.p0

import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DiagnosticStageEntriesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun allStagesAreOrderedAndDeferredStagesHaveNoFakeDestination() {
        assertEquals((0..11).toList(), DiagnosticStageEntries.entries.map { it.stage })
        DiagnosticStageEntries.entries.forEach {
            assertEquals(it.available, DiagnosticStageEntries.intent(context, it.stage) != null)
        }
        assertFalse(DiagnosticStageEntries.entries[6].available)
        assertFalse(DiagnosticStageEntries.entries[7].available)
        assertNull(DiagnosticStageEntries.intent(context, 12))
    }
    @Test fun productEntriesActuallyReachRequestedTabAndPlaybackSettings() {
        assertFalse(DiagnosticStageEntries.readMainEntry(DiagnosticStageEntries.intent(context, 11)!!).allowAutomaticGuide)
        assertTrue(MainEntry().allowAutomaticGuide)
        assertEquals(MainEntry("effects"), DiagnosticStageEntries.readMainEntry(DiagnosticStageEntries.intent(context, 9)!!))
        assertEquals(MainEntry("playback", true), DiagnosticStageEntries.readMainEntry(DiagnosticStageEntries.intent(context, 11)!!))
        assertEquals(MainEntry(), DiagnosticStageEntries.readMainEntry(DiagnosticStageEntries.intent(context, 8)!!))
    }
    @Test fun arbitraryAndMalformedLaunchExtrasFallBackToGalleryWithoutActions() {
        val unknown = Intent().putExtra("personal.wallpaper.os3.diagnostic.ROUTE", "content://private/image")
            .putExtra("personal.wallpaper.os3.diagnostic.PLAYBACK_SETTINGS", true)
        assertEquals(MainEntry(), DiagnosticStageEntries.readMainEntry(unknown))
        assertEquals(MainEntry(), DiagnosticStageEntries.readMainEntry(Intent()))
    }
}
