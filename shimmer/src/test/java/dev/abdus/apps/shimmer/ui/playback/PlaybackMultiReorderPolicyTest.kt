package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import org.junit.Assert.*
import org.junit.Test

/** 临时整体的纯移动边界；不代表手机触摸或真实壁纸已验证。 */
class PlaybackMultiReorderPolicyTest {
    private val ids = listOf("a", "b", "c", "d", "e", "f")

    @Test fun reverseClickOrderStillUsesOriginalQueueOrder() {
        assertEquals(listOf("b", "d"), PlaybackMultiReorderPolicy.block(ids, linkedSetOf("d", "b"))!!.ids)
    }
    @Test fun nonContiguousUpAggregatesOnlyOnMove() {
        assertEquals(listOf("b", "d", "a", "c", "e", "f"), PlaybackMultiReorderPolicy.move(ids, setOf("b", "d"), -1)!!.ids)
    }
    @Test fun nonContiguousDownPreservesOthers() {
        assertEquals(listOf("a", "c", "b", "d", "e", "f"), PlaybackMultiReorderPolicy.move(ids, setOf("b", "d"), 1)!!.ids)
    }
    @Test fun sameStartCanStillAggregateScatteredSelection() {
        assertEquals(QueueMove(listOf("a", "b", "d", "c", "e", "f"), 2, 2),
            PlaybackMultiReorderPolicy.moveTo(ids, setOf("b", "d"), 2))
    }
    @Test fun contiguousSamePositionDoesNotWrite() {
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, setOf("b", "c"), 2))
    }
    @Test fun targetIsTheFinalBlockStartAndCannotOverflowTail() {
        val moved = PlaybackMultiReorderPolicy.moveTo(ids, setOf("b", "d"), 5)!!
        assertEquals(listOf("a", "c", "e", "f", "b", "d"), moved.ids)
        assertEquals(5, moved.to)
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, setOf("b", "d"), 6))
    }
    @Test fun emptyOrMissingSelectionRejectsEntireMove() {
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, emptySet(), 1))
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, setOf("b", "gone"), 1))
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, setOf("b", ""), 1))
    }
    @Test fun fullSelectionHasOnlyOneValidStartAndNoActualMove() {
        assertEquals(1, PlaybackMultiReorderPolicy.block(ids, ids.toSet())!!.maximumStart)
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, ids.toSet(), 1))
        assertNull(PlaybackMultiReorderPolicy.move(ids, ids.toSet(), -1))
        assertNull(PlaybackMultiReorderPolicy.move(ids, ids.toSet(), 1))
    }
    @Test fun invalidPositionAndDirectionCannotMutateOrder() {
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, setOf("b"), 0))
        assertNull(PlaybackMultiReorderPolicy.moveTo(ids, setOf("b"), -1))
        assertNull(PlaybackMultiReorderPolicy.move(ids, setOf("b"), 2))
        assertNull(PlaybackMultiReorderPolicy.move(ids, setOf("b"), 0))
    }
    @Test fun oneSelectedItemExactlyMatchesLegacySingleMoves() {
        for (id in ids) {
            for (delta in listOf(-1, 1)) assertEquals(moveItemIds(ids, id, delta), PlaybackMultiReorderPolicy.move(ids, setOf(id), delta))
            for (position in 1..ids.size) assertEquals(moveItemToPosition(ids, id, position), PlaybackMultiReorderPolicy.moveTo(ids, setOf(id), position))
        }
    }
    @Test fun duplicatesCannotPretendAllSelectedMembersExist() {
        assertNull(PlaybackMultiReorderPolicy.block(listOf("b", "b", "c"), setOf("b", "d")))
    }
    @Test fun movingDoesNotAlterCallerCollections() {
        val mutable = ids.toMutableList()
        val selected = linkedSetOf("d", "b")
        PlaybackMultiReorderPolicy.moveTo(mutable, selected, 1)
        assertEquals(ids, mutable)
        assertEquals(listOf("d", "b"), selected.toList())
    }
    @Test fun uiFiltersInactiveRowsButRecordsUnavailableCount() {
        val state = PlaybackMultiReorderPolicy.state(true, setOf("b", "gone"), mapOf("a" to 1, "b" to 2), 2)
        assertEquals(setOf("b"), state.selectedIds)
        assertEquals(1, state.unavailableCount)
        assertFalse(state.canMoveTo)
        assertFalse(state.canMoveUp)
        assertFalse(state.canMoveDown)
    }
    @Test fun disabledMultiStateExposesNoSelectionControls() {
        assertEquals(MultiReorderUiState(), PlaybackMultiReorderPolicy.state(false, setOf("b"), mapOf("b" to 1), 1))
    }
    @Test fun pureUiStateDoesNotReorderOrAggregate() {
        val state = PlaybackMultiReorderPolicy.state(true, setOf("d", "b"), ids.withIndex().associate { it.value to it.index + 1 }, 6)
        assertEquals(setOf("b", "d"), state.selectedIds)
        assertEquals(2, state.firstPosition)
        assertEquals(5, state.maximumStart)
        assertEquals(ids, ids.toList())
    }
    @Test fun arbitraryBlockMovementKeepsEveryItemExactlyOnce() {
        for (mask in 1 until (1 shl ids.size)) {
            val selected = ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            for (target in 1..(ids.size - selected.size + 1)) {
                val result = PlaybackMultiReorderPolicy.moveTo(ids, selected, target) ?: continue
                assertEquals(ids.size, result.ids.size)
                assertEquals(ids.toSet(), result.ids.toSet())
                assertEquals(ids.filter { it in selected }, result.ids.filter { it in selected })
                assertEquals(ids.filterNot { it in selected }, result.ids.filterNot { it in selected })
            }
        }
    }
    @Test fun rowSelectionUsesMultiIdsOnlyWhileMultiIsEnabled() {
        val model = buildQueueModel(ids.map { PlaybackItem(it, it, sourceId = it, sourceKind = "SINGLE_DOCUMENT") }, PlaybackOrder.MANUAL, null)
        val view = QueueViewState(selectedItemId = "a", multiReorderEnabled = true, multiSelectedIds = setOf("b", "d"))
        assertEquals(listOf("b", "d"), buildQueueRows(model, view).filterIsInstance<QueueRow.Image>().filter { it.selected }.map { it.item.id })
        assertEquals(listOf("a"), buildQueueRows(model, view.copy(multiReorderEnabled = false)).filterIsInstance<QueueRow.Image>().filter { it.selected }.map { it.item.id })
    }
    @Test fun everyMovedBlockMemberGetsTheOriginalHighlight() {
        val model = buildQueueModel(ids.map { PlaybackItem(it, it, sourceId = it, sourceKind = "SINGLE_DOCUMENT") }, PlaybackOrder.MANUAL, null)
        val rows = buildQueueRows(model, QueueViewState(movedItemIds = setOf("b", "d"))).filterIsInstance<QueueRow.Image>()
        assertEquals(listOf("b", "d"), rows.filter { it.moved }.map { it.item.id })
    }
    @Test fun nonManualRowsNeverDisplayTemporaryReorderSelection() {
        val model = buildQueueModel(ids.map { PlaybackItem(it, it, sourceId = it, sourceKind = "SINGLE_DOCUMENT") }, PlaybackOrder.NATURAL_ASC, null)
        assertTrue(buildQueueRows(model, QueueViewState(multiReorderEnabled = true, multiSelectedIds = setOf("b")))
            .filterIsInstance<QueueRow.Image>().none { it.selected })
    }
}
