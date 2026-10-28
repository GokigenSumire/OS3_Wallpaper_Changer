package dev.abdus.apps.shimmer.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryBatchRowsTest {
    private fun images(count: Int) = (1..count).map { GalleryBatchImage("uri:$it", "图$it") }

    @Test
    fun aLargeGroupStartsCollapsedWithoutImageRows() {
        val page = GalleryBatchRows.page(images(10_000), true, setOf("uri:2"), GalleryBatchViewState())
        assertFalse(page.expanded)
        assertEquals(10_000, page.totalCount)
        assertEquals(1, page.stoppedCount)
        assertTrue(page.rows.isEmpty())
    }

    @Test
    fun anExpandedPageHasAtMostFiftyRowsInAssociationOrder() {
        val input = images(151)
        val page = GalleryBatchRows.page(input, true, emptySet(), GalleryBatchViewState(true, 1))
        assertEquals(50, page.rows.size)
        assertEquals((51..100).toList(), page.rows.map { it.position })
        assertEquals(input.subList(50, 100).map { it.uri }, page.rows.map { it.uri })
        assertEquals(4, page.pageCount)
    }

    @Test
    fun theLastPageDoesNotRepeatEarlierRows() {
        val input = images(101)
        val pages = (0..2).flatMap { index ->
            GalleryBatchRows.page(input, true, emptySet(), GalleryBatchViewState(true, index)).rows
        }
        assertEquals(input.map { it.uri }, pages.map { it.uri })
        assertEquals(101, pages.last().position)
    }

    @Test
    fun collapsingAndReopeningKeepsTheSelectedPage() {
        val browsing = GalleryBatchViewState(true, 2)
        val collapsed = browsing.toggle()
        assertFalse(collapsed.expanded)
        assertEquals(2, collapsed.pageIndex)
        assertEquals(browsing, collapsed.toggle())
    }

    @Test
    fun pageMovesClampAtBothEndsAndHandleLargeDeltas() {
        val first = GalleryBatchViewState(true, 0)
        assertEquals(0, first.movePage(-1, 101).pageIndex)
        assertEquals(2, first.movePage(Int.MAX_VALUE, 101).pageIndex)
        assertEquals(0, GalleryBatchViewState(true, 2).movePage(Int.MIN_VALUE, 101).pageIndex)
        assertEquals(0, first.movePage(1, 0).pageIndex)
    }

    @Test
    fun aShrinkingGroupClampsTheRenderedPageWithoutModifyingTheInput() {
        val input = images(2)
        val page = GalleryBatchRows.page(input, true, emptySet(), GalleryBatchViewState(true, 99))
        assertEquals(0, page.pageIndex)
        assertEquals(input.map { it.uri }, page.rows.map { it.uri })
        assertEquals(images(2), input)
    }

    @Test
    fun anEmptyGroupHasNoPagesOrRows() {
        val page = GalleryBatchRows.page(emptyList(), true, emptySet(), GalleryBatchViewState(true, 2))
        assertEquals(0, page.pageCount)
        assertEquals(0, page.pageIndex)
        assertTrue(page.rows.isEmpty())
    }

    @Test
    fun ownDisableRecordsAreMatchedByUriNotDisplayName() {
        val sameNames = listOf(GalleryBatchImage("first", "同名.png"), GalleryBatchImage("second", "同名.png"))
        val page = GalleryBatchRows.page(sameNames, true, setOf("first", "unrelated"), GalleryBatchViewState(true))
        assertFalse(page.rows[0].enabled)
        assertTrue(page.rows[1].enabled)
        assertTrue(page.rows.all { it.canToggle })
        assertEquals(1, page.stoppedCount)
    }

    @Test
    fun aStoppedSourceDisablesControlsWithoutOverwritingEachImageState() {
        val page = GalleryBatchRows.page(images(2), false, setOf("uri:2"), GalleryBatchViewState(true))
        assertTrue(page.rows[0].enabled)
        assertFalse(page.rows[1].enabled)
        assertTrue(page.rows.none { it.canToggle })
        assertEquals("受来源停用影响", page.rows[0].stateLabel)
        assertEquals("受来源停用影响（本图也已停用）", page.rows[1].stateLabel)
    }

    @Test
    fun resumingASourceRecoversTheImagesOwnControlStates() {
        val page = GalleryBatchRows.page(images(2), true, setOf("uri:2"), GalleryBatchViewState(true))
        assertTrue(page.rows.all { it.canToggle })
        assertEquals(listOf("播放中", "已停用"), page.rows.map { it.stateLabel })
    }

    @Test
    fun pageCountIsSafeAtIntegerBoundaries() {
        assertEquals(0, GalleryBatchRows.pageCount(-1))
        assertEquals(1, GalleryBatchRows.pageCount(50))
        assertEquals(2, GalleryBatchRows.pageCount(51))
        assertEquals(42_949_673, GalleryBatchRows.pageCount(Int.MAX_VALUE))
    }
}
