package dev.abdus.apps.shimmer.ui.playback

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisabledPlaybackListTest {
    private val ids = (1..120).map { "file:///stopped/%03d.jpg".format(it) }
    private val records = ids.associateWith { "图${it.substringAfterLast('/')}" to "目录/${it.substringAfterLast('/')}" }

    @Test fun firstReadIsLimitedToFiftyEvenForTwelveThousandStops() {
        val large = (1..12_000).map { "uri-%05d".format(it) }.toSet()
        assertEquals(50, DisabledPlaybackList.idsToLoad(large, 50).size)
    }

    @Test fun nextPagePreservesTheExactExistingPrefixWithoutDuplicates() {
        val first = DisabledPlaybackList.page(ids.toSet(), 50, records)
        val next = DisabledPlaybackList.page(ids.reversed().toSet(), 100, records)
        assertEquals(120, first.totalCount)
        assertEquals(70, first.remainingCount)
        assertEquals(first.entries, next.entries.take(50))
        assertEquals(100, next.entries.map { it.id }.distinct().size)
        assertEquals(20, next.remainingCount)
    }

    @Test fun lastPageContainsEveryStopExactlyOnce() {
        val page = DisabledPlaybackList.page(ids.toSet(), 150, records)
        assertEquals(ids, page.entries.map { it.id })
        assertEquals(0, page.remainingCount)
    }

    @Test fun metadataRenameDoesNotReorderOrShiftAnyPage() {
        val first = DisabledPlaybackList.page(ids.toSet(), 50, records)
        val renamed = records + (ids[0] to ("ZZZ重命名.jpg" to "其他/ZZZ重命名.jpg"))
        val next = DisabledPlaybackList.page(ids.toSet(), 100, renamed)
        assertEquals(first.entries.map { it.id }, next.entries.take(50).map { it.id })
        assertEquals("ZZZ重命名.jpg", next.entries.first().name)
    }

    @Test fun recordsOutsideTheActualStopSetNeverBecomeDisabledRows() {
        val page = DisabledPlaybackList.page(setOf(ids[1]), 50, records)
        assertEquals(listOf(ids[1]), page.entries.map { it.id })
        assertEquals(1, page.totalCount)
    }

    @Test fun deletedSourceRecordKeepsARecoverableNamedRow() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3AAlbums%2F100%25.jpg"
        val page = DisabledPlaybackList.page(setOf(uri), 50, emptyMap())
        assertEquals(uri, page.entries.single().id)
        assertEquals("100%.jpg", page.entries.single().name)
        assertEquals("来源记录中已没有这张图片", page.entries.single().detail)
    }

    @Test fun negativeVisibleLimitStillProvidesOnlyTheFirstPage() {
        assertEquals(50, DisabledPlaybackList.idsToLoad(ids.toSet(), -1).size)
    }

    @Test fun emptyDisabledSetProducesNoRowsRegardlessOfAvailableRecords() {
        val page = DisabledPlaybackList.page(emptySet(), 500, records)
        assertTrue(page.entries.isEmpty())
        assertEquals(0, page.totalCount)
        assertEquals(0, page.remainingCount)
    }

    @Test fun keysHaveIndependentNamespacesAndStableUriIdentity() {
        val page = DisabledPlaybackList.page(ids.toSet(), 150, records)
        val keys = page.entries.map { DisabledPlaybackList.rowKey(it.id) }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.none { it == DisabledPlaybackList.TITLE_KEY || it == DisabledPlaybackList.MORE_KEY })
        assertTrue(page.entries.none { DisabledPlaybackList.rowKey(it.id) == imageRowKey(it.id) })
        assertEquals("disabled|${ids[0]}", DisabledPlaybackList.rowKey(ids[0]))
    }

    @Test fun rowReusesTheRealImageUriNamePathAndLocalPosition() {
        val entry = DisabledPlaybackList.page(setOf(ids[0]), 50, records).entries.single()
        val row = DisabledPlaybackList.row(entry, 4)
        assertEquals(entry.id, row.item.id)
        assertEquals(entry.name, row.item.displayName)
        assertEquals(entry.detail, row.item.relativePath)
        assertEquals(5, row.position)
        assertEquals(0, row.depth)
        assertEquals(DisabledPlaybackList.rowKey(entry.id), row.key)
    }

    @Test fun stoppedRowsNeverAcquireCurrentOrManualReorderFlags() {
        val entry = DisabledPlaybackList.page(setOf(ids[0]), 50, records).entries.single()
        val row = DisabledPlaybackList.row(entry, 0)
        assertFalse(row.isCurrent)
        assertFalse(row.selected)
        assertFalse(row.moved)
        assertFalse(row.canMoveUp)
        assertFalse(row.canMoveDown)
    }

    @Test fun stoppedRowsUseTheSameDisclosureRulesWithoutPlayOrSelectionSideEffects() {
        val id = ids[0]
        val available = setOf(id)
        val expanded = PlaybackRowDisclosure.toggle(null, id, available)
        assertEquals(id, expanded)
        val actions = PlaybackRowDisclosure.actions(expanded == id, manual = false, selectedForReorder = false)
        assertTrue(actions.showPlay) // 共用操作区开合信号，停用行将主操作接到显式恢复按钮。
        assertFalse(actions.showReorderSelection)
        assertFalse(actions.showMoveToPosition)
        assertNull(PlaybackRowDisclosure.toggle(expanded, id, available))
    }
}
