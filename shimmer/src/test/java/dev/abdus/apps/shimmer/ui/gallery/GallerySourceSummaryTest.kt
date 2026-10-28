package dev.abdus.apps.shimmer.ui.gallery

import dev.abdus.apps.shimmer.p1.DIRECTORY_TREE_ROOT_NAME
import dev.abdus.apps.shimmer.p1.P1SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GallerySourceSummaryTest {
    @Test
    fun subdirectoryCountCountsEveryFolderLevelOnce() {
        val paths = listOf("a.jpg", "1/b.jpg", "1/1-1/c.jpg", "1/1-1/d.jpg", "2/x/y/e.jpg", "2\\x\\f.jpg")

        // 1、1/1-1、2、2/x、2/x/y
        assertEquals(5, GallerySourceSummary.subdirectoryCount(paths))
    }

    @Test
    fun rootOnlyImagesHaveNoSubdirectories() {
        assertEquals(0, GallerySourceSummary.subdirectoryCount(listOf("a.jpg", "b.png")))
        assertEquals(0, GallerySourceSummary.subdirectoryCount(emptyList()))
    }

    @Test
    fun everyKindHasTheP1Wording() {
        assertEquals(SourceType.FOLDER, GallerySourceSummary.type(P1SourceKind.DIRECTORY_TREE.name))
        assertEquals(SourceType.MULTI_IMAGES, GallerySourceSummary.type(P1SourceKind.MULTIPLE_DOCUMENTS.name))
        assertEquals(SourceType.SINGLE_IMAGE, GallerySourceSummary.type(P1SourceKind.SINGLE_DOCUMENT.name))
        assertEquals("单张图片", GallerySourceSummary.kindLabel(P1SourceKind.SINGLE_DOCUMENT.name))
        assertEquals("多张图片", GallerySourceSummary.kindLabel(P1SourceKind.MULTIPLE_DOCUMENTS.name))
        assertEquals("文件夹目录", GallerySourceSummary.kindLabel(P1SourceKind.DIRECTORY_TREE.name))
        assertEquals("UNKNOWN_KIND", GallerySourceSummary.kindLabel("UNKNOWN_KIND"))
    }

    @Test
    fun folderTitleIsTheFolderNameAndLocationIsReadable() {
        val root = "content://com.android.externalstorage.documents/tree/primary%3A1%2Ftest%2F2"

        assertEquals("2", GallerySourceSummary.title(P1SourceKind.DIRECTORY_TREE.name, root))
        assertEquals("多张图片", GallerySourceSummary.title(P1SourceKind.MULTIPLE_DOCUMENTS.name, root))
        val location = GallerySourceSummary.location(root)
        assertEquals("/storage/emulated/0/1/test/2/", location)
        assertFalse(location.contains("%3A"))
    }

    @Test
    fun unmappableFolderFallsBackToTheRootName() {
        val root = "content://com.android.providers.media.documents/tree/image%3A123"

        assertEquals(DIRECTORY_TREE_ROOT_NAME, GallerySourceSummary.title(P1SourceKind.DIRECTORY_TREE.name, root))
    }
}
