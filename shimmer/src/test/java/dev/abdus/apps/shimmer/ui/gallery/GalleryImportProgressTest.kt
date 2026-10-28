package dev.abdus.apps.shimmer.ui.gallery

import dev.abdus.apps.shimmer.p1.P1ScanPhase
import dev.abdus.apps.shimmer.p1.P1ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryImportProgressTest {
    @Test
    fun anUnknownProgressDoesNotInventACountOrTime() {
        val display = GalleryImportProgress.from(null)
        assertEquals("正在导入…", display.summary)
        assertNull(display.elapsedLabel)
        assertNull(display.currentPath)
    }

    @Test
    fun enumerationShowsDiscoveryAndTheCurrentPath() {
        val display = GalleryImportProgress.from(P1ScanProgress(12, 7, "dir/a", elapsedMillis = 2_999))
        assertEquals("枚举目录：已发现 7 张，已检查 12 项", display.summary)
        assertEquals("dir/a", display.currentPath)
        assertEquals("本次已用 2 秒", display.elapsedLabel)
    }

    @Test
    fun metadataShowsItsActualProcessedFractionWithoutRepeatingThePath() {
        val display = GalleryImportProgress.from(P1ScanProgress(50, 20, "old/path", P1ScanPhase.METADATA, 9, 20, 10_000))
        assertEquals("校验图片：已检查 9 / 20 张", display.summary)
        assertNull(display.currentPath)
        assertEquals("本次已用 10 秒", display.elapsedLabel)
    }

    @Test
    fun committingShowsTheTotalAndNotTheEarlierDirectoryPath() {
        val display = GalleryImportProgress.from(P1ScanProgress(50, 20, "old/path", P1ScanPhase.COMMIT, 20, 20, 11_111))
        assertEquals("提交配置：共 20 张", display.summary)
        assertNull(display.currentPath)
        assertEquals("本次已用 11 秒", display.elapsedLabel)
    }

    @Test
    fun blankPathsAndNegativeElapsedTimeAreDisplayedSafely() {
        val display = GalleryImportProgress.from(P1ScanProgress(0, 0, " ", elapsedMillis = -1))
        assertNull(display.currentPath)
        assertEquals("本次已用 0 秒", display.elapsedLabel)
    }

    @Test
    fun aLargeElapsedValueIsConvertedWithoutIntegerOverflow() {
        val display = GalleryImportProgress.from(P1ScanProgress(1, 1, "", elapsedMillis = Long.MAX_VALUE))
        assertTrue(display.elapsedLabel!!.contains((Long.MAX_VALUE / 1000).toString()))
    }
}
