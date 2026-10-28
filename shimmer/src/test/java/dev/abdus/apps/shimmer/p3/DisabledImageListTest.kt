package dev.abdus.apps.shimmer.p3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisabledImageListTest {

    @Test
    fun recordedImagesUseTheirDisplayNameAndFolder() {
        val entries = DisabledImageList.entries(
            listOf("content://a/b.jpg", "content://a/c.jpg"),
            mapOf(
                "content://a/b.jpg" to ("b.jpg" to "夏天/b.jpg"),
                "content://a/c.jpg" to ("" to "冬天/雪/c.jpg"),
            ),
        )

        assertEquals(
            listOf(
                DisabledImageEntry("content://a/b.jpg", "b.jpg", "夏天/b.jpg"),
                DisabledImageEntry("content://a/c.jpg", "c.jpg", "冬天/雪/c.jpg"),
            ),
            entries,
        )
    }

    @Test
    fun anImageMissingFromTheSourcesStillGetsAReadableName() {
        val id = "content://com.android.externalstorage.documents/tree/primary%3APictures/document/" +
            "primary%3APictures%2F%E5%A4%8F%E5%A4%A9%2F100%25.jpg"

        val entry = DisabledImageList.entries(listOf(id), emptyMap()).single()

        // 已解码一次的文件名；含 % 的名称不能再被解码一次
        assertEquals("100%.jpg", entry.name)
        assertEquals("来源记录中已没有这张图片", entry.detail)
        assertEquals(id, entry.id)
    }

    @Test
    fun anIdWithoutAPathFallsBackToItself() {
        assertEquals("opaque-id", DisabledImageList.fallbackName("opaque-id"))
        assertEquals("", DisabledImageList.fallbackName(""))
    }

    @Test
    fun anImageAtTheSourceRootSaysSo() {
        val entry = DisabledImageList.entries(listOf("file:///x/a.png"), mapOf("file:///x/a.png" to ("a.png" to ""))).single()

        assertEquals("来源根目录", entry.detail)
    }

    @Test
    fun entriesAreSortedByNameThenId() {
        val entries = DisabledImageList.entries(
            listOf("id-3", "id-1", "id-2"),
            mapOf("id-3" to ("a.jpg" to "x/a.jpg"), "id-1" to ("b.jpg" to "x/b.jpg"), "id-2" to ("a.jpg" to "y/a.jpg")),
        )

        assertEquals(listOf("id-2", "id-3", "id-1"), entries.map { it.id })
    }

    @Test
    fun theListIsCappedButTheCapIsStable() {
        val disabled = (1..250).map { "id-%03d".format(it) }.toSet()

        val shown = DisabledImageList.idsToShow(disabled)

        assertEquals(DisabledImageList.DISPLAY_LIMIT, shown.size)
        assertEquals(disabled.sorted().take(DisabledImageList.DISPLAY_LIMIT), shown)
    }

    @Test
    fun restoringOneOrAllRemovesTheStopRecords() {
        val preferences = WallpaperPreferences.create(ApplicationProvider.getApplicationContext<Context>())
        preferences.clearDisabledImages()
        preferences.setImageEnabled("a", false)
        preferences.setImageEnabled("b", false)
        preferences.setImageEnabled("c", false)

        preferences.setImageEnabled("b", true)
        assertEquals(setOf("a", "c"), preferences.getDisabledImages())

        preferences.clearDisabledImages()
        assertTrue(preferences.getDisabledImages().isEmpty())
    }

    @Test
    fun theEditorToastPointsToThePlaybackTab() {
        assertTrue(DisabledImageText.DISABLED_TOAST.contains("“播放”页"))
        assertTrue(DisabledImageText.DISABLED_TOAST.contains(DisabledImageText.SECTION_TITLE))
        // 旧提示指向不存在的“P2 设置”入口
        assertFalse(DisabledImageText.DISABLED_TOAST.contains("P2"))
    }
}
