package dev.abdus.apps.shimmer.media

import android.content.Intent
import dev.abdus.apps.shimmer.p1.P1SourceIntents
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MediaSourceIntentsTest {
    @Test fun mixedPickerRetainsReadOnlyPersistableGrantAndExactTypes() {
        val intent = P1SourceIntents.openSingleMedia()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf("image/*", "video/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }
    @Test fun multipleMediaAndLegacyImagePickerRemainDistinct() {
        assertTrue(P1SourceIntents.openMultipleMedia().getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals("image/*", P1SourceIntents.openSingleImage().type)
    }
}
