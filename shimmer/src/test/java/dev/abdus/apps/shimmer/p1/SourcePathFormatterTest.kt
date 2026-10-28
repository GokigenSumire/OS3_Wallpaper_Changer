package dev.abdus.apps.shimmer.p1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourcePathFormatterTest {
    @Test
    fun primaryTreeUriUsesReadableExternalStorageMapping() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A1%2Ftest%2F2"
        assertEquals("/storage/emulated/0/1/test/2/", SourcePathFormatter.format(uri))
    }

    @Test
    fun primaryDocumentUriUsesReadableExternalStorageMappingWithoutTreeSlash() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3A1%2Ftest%2Fimage.jpg"

        assertEquals("/storage/emulated/0/1/test/image.jpg", SourcePathFormatter.format(uri))
    }

    @Test
    fun unsupportedProviderKeepsUriAsFallback() {
        val uri = "content://com.android.providers.media.documents/document/image%3A123"

        assertEquals(uri, SourcePathFormatter.format(uri))
        assertTrue(SourcePathFormatter.shorten(uri, 32).startsWith("content://"))
    }

    @Test
    fun literalPercentAndSpacesInDocumentNamesArePreserved() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3A1%2F%252F%20photo%20.jpg"
        assertEquals("/storage/emulated/0/1/%2F photo .jpg", SourcePathFormatter.format(uri))
    }

    @Test
    fun unknownVolumesKeepTheirUri() {
        listOf("home", "cloud").forEach { volume ->
            val uri = "content://com.android.externalstorage.documents/tree/$volume%3APictures"
            assertEquals(uri, SourcePathFormatter.format(uri))
        }
    }

    @Test
    fun hyperOsFileManagerExternalFilesUsesPrimaryStorageMapping() {
        val uri = "content://com.android.fileexplorer.myprovider/external_files/1/test/1/10.jpg"
        assertEquals("/storage/emulated/0/1/test/1/10.jpg", SourcePathFormatter.format(uri))
        assertEquals("/storage/emulated/0/1/test/1/10.jpg", SourcePathFormatter.shorten(uri))
    }

    @Test
    fun hyperOsFileManagerUnknownRootKeepsUri() {
        val uri = "content://com.android.fileexplorer.myprovider/root_files/data/10.jpg"
        assertEquals(uri, SourcePathFormatter.format(uri))
    }

    @Test
    fun folderNameIsTheLastSegmentOfTheReadableRoot() {
        assertEquals("1", SourcePathFormatter.folderName("content://com.android.externalstorage.documents/tree/primary%3A1"))
        assertEquals("2", SourcePathFormatter.folderName("content://com.android.externalstorage.documents/tree/primary%3A1%2Ftest%2F2"))
        assertEquals("100%_合集", SourcePathFormatter.folderName("file:///storage/emulated/0/100%25_合集/"))
        assertEquals("内部存储", SourcePathFormatter.folderName("content://com.android.externalstorage.documents/tree/primary%3A"))
    }

    @Test
    fun folderNameIsNullWhenTheRootCannotBeMapped() {
        assertEquals(null, SourcePathFormatter.folderName("content://com.android.providers.media.documents/tree/image%3A123"))
        assertEquals(null, SourcePathFormatter.folderName(""))
    }

    @Test
    fun documentWithinTreeShowsDocumentInsteadOfRootDirectory() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A1/document/primary%3A1%2Ftest%2F2.jpg"
        assertEquals("/storage/emulated/0/1/test/2.jpg", SourcePathFormatter.format(uri))
    }
}
