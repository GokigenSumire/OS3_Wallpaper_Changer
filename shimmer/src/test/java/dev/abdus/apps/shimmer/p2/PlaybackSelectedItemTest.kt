package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p3.ImageTransform
import org.junit.Assert.*
import org.junit.Test

/** 显式指定的纯状态合同；不执行Android／GPU，不代替手机显示验收。 */
class PlaybackSelectedItemTest {
    private val items = listOf("a", "b", "c", "d").map { PlaybackItem(it, "$it.jpg", "$it.jpg", "A") }

    private fun coordinator(order: PlaybackOrder = PlaybackOrder.NATURAL_ASC) = PlaybackCoordinator(19L).apply {
        setItems(items)
        setOrder(order)
    }

    private fun play(c: PlaybackCoordinator, transform: ImageTransform? = null): String =
        c.requestNext().also { assertTrue(c.commit(it.version, transform)) }.item!!.id

    private fun pick(c: PlaybackCoordinator, id: String): PlaybackRequest =
        c.requestSelected(id).also { assertEquals(id, it.item?.id); assertTrue(c.commit(it.version)) }

    @Test fun selectingCreatesOnlyProvisionalStateUntilCommit() {
        val c = coordinator()
        assertEquals("a", play(c))
        val before = c.snapshot()
        val request = c.requestSelected("c")
        assertTrue(request.selected)
        assertTrue(request.freshAdvance)
        assertNull(request.entryTransform)
        assertEquals(before, c.snapshot())
        assertEquals("a", c.current()!!.id)
        assertTrue(c.commit(request.version))
        assertEquals(listOf("a", "c"), c.snapshot().historyIds)
        assertEquals("c", c.snapshot().sourcePositions["A"])
        assertEquals(1, c.snapshot().sourceHistoryCursors["A"])
    }

    @Test fun selectingInEachOrderKeepsModeAndExactQueue() {
        for (order in PlaybackOrder.entries) {
            val c = coordinator(order)
            if (order == PlaybackOrder.MANUAL) c.reorder(listOf("c", "a", "d", "b"))
            play(c)
            val before = c.snapshot()
            val wanted = c.orderedItems().last().id
            pick(c, wanted)
            val after = c.snapshot()
            assertEquals(order, after.order)
            assertEquals(before.queueIds, after.queueIds)
            assertEquals(before.queueFingerprint, after.queueFingerprint)
            assertEquals(before.randomRound, after.randomRound)
            assertEquals(before.previousRoundLastId, after.previousRoundLastId)
            assertEquals(after.queueIds.indexOf(wanted), after.queueIndex)
        }
    }

    @Test fun previousAfterSelectionReturnsActualDepartedImageOnce() {
        val c = coordinator()
        play(c)
        play(c)
        pick(c, "d")
        val previous = c.requestPrevious()
        assertEquals("b", previous.item!!.id)
        assertFalse(previous.selected)
        assertTrue(c.commit(previous.version))
        assertEquals(listOf("a", "b", "d"), c.snapshot().historyIds)
        assertEquals("a", c.requestPrevious().item!!.id)
    }

    @Test fun nextAfterSelectionStartsAtSelectedNaturalAscendingPosition() {
        val c = coordinator()
        play(c)
        pick(c, "c")
        assertEquals("d", play(c))
        assertEquals(listOf("a", "c", "d"), c.snapshot().historyIds)
    }

    @Test fun nextAfterSelectionStartsAtSelectedNaturalDescendingPosition() {
        val c = coordinator(PlaybackOrder.NATURAL_DESC)
        assertEquals("d", play(c))
        pick(c, "b")
        assertEquals("a", play(c))
        assertEquals(listOf("d", "b", "a"), c.snapshot().historyIds)
    }

    @Test fun nextAfterSelectionStartsAtSelectedManualPositionWithoutMovingItems() {
        val c = coordinator(PlaybackOrder.MANUAL)
        val manual = listOf("c", "a", "d", "b")
        c.reorder(manual)
        play(c)
        pick(c, "a")
        assertEquals("d", play(c))
        assertEquals(manual, c.snapshot().queueIds)
    }

    @Test fun randomSelectionKeepsRoundAndAdvancesFromTargetPosition() {
        for (seed in 0L..31L) {
            val c = PlaybackCoordinator(seed).apply { setItems(items); setOrder(PlaybackOrder.RANDOM) }
            val queue = c.orderedItems().map { it.id }
            assertEquals(queue.first(), play(c))
            val before = c.snapshot()
            pick(c, queue[2])
            assertEquals(before.queueIds, c.snapshot().queueIds)
            assertEquals(before.randomRound, c.snapshot().randomRound)
            assertEquals(queue[3], play(c))
            assertEquals(listOf(queue[0], queue[2], queue[3]), c.snapshot().historyIds)
        }
    }

    @Test fun selectingLastRandomItemCrossesRoundOnFollowingNextNotOnClick() {
        val c = coordinator(PlaybackOrder.RANDOM)
        val queue = c.orderedItems().map { it.id }
        play(c)
        val oldRound = c.snapshot().randomRound
        pick(c, queue.last())
        assertEquals(oldRound, c.snapshot().randomRound)
        val firstNext = play(c)
        assertNotEquals(queue.last(), firstNext)
        assertEquals(oldRound + 1, c.snapshot().randomRound)
    }

    @Test fun manualInactiveSavedSlotsSurviveSelectionAndLaterReenable() {
        val hidden = PlaybackItem("hidden", "hidden.jpg", sourceId = "B")
        val c = PlaybackCoordinator().apply {
            setItems(items + hidden)
            reorder(listOf("c", "hidden", "a", "d", "b"))
            restoreCurrent("a")
            setItems(items)
        }
        val queue = c.snapshot().queueIds
        pick(c, "d")
        assertEquals(queue, c.snapshot().queueIds)
        c.setItems(items + hidden)
        assertEquals(listOf("c", "hidden", "a", "d", "b"), c.orderedItems().map { it.id })
    }

    @Test fun hiddenManualItemCannotBeSelectedEvenThoughSavedQueueContainsIt() {
        val c = coordinator(PlaybackOrder.MANUAL)
        c.restore(c.snapshot().copy(queueIds = listOf("a", "hidden", "b", "c", "d")))
        play(c)
        val before = c.snapshot()
        val request = c.requestSelected("hidden")
        assertNull(request.item)
        assertFalse(c.commit(request.version))
        assertEquals(before, c.snapshot())
    }

    @Test fun selectingUnknownOrBlankIdentityNeverCreatesCurrent() {
        val c = coordinator()
        play(c)
        val before = c.snapshot()
        for (id in listOf("", " ", "unknown", "a ")) {
            val request = c.requestSelected(id)
            assertNull(request.item)
            assertFalse(request.freshAdvance)
            assertFalse(c.commit(request.version))
            assertEquals(before, c.snapshot())
        }
    }

    @Test fun selectingAlreadyCurrentIsNonCommittableNoOpEvenWithOldFutureHistory() {
        val c = coordinator()
        repeat(3) { play(c) }
        c.requestPrevious().also { assertTrue(c.commit(it.version)) }
        val before = c.snapshot()
        val request = c.requestSelected("b")
        assertNull(request.item)
        assertFalse(c.commit(request.version))
        assertEquals(before, c.snapshot())
        assertEquals("c", play(c))
    }

    @Test fun sameCurrentSelectionCancelsPreviousPendingTarget() {
        val c = coordinator()
        play(c)
        val old = c.requestSelected("d")
        val same = c.requestSelected("a")
        assertNull(same.item)
        assertFalse(c.commit(old.version))
        assertFalse(c.commit(same.version))
        assertEquals(listOf("a"), c.snapshot().historyIds)
    }

    @Test fun selectingFromEmptyHistoryInitializesTargetWithoutInventedPast() {
        val c = coordinator()
        pick(c, "d")
        assertEquals(listOf("d"), c.snapshot().historyIds)
        assertEquals(0, c.snapshot().historyCursor)
        assertEquals("d", c.requestPrevious().item!!.id)
    }

    @Test fun emptyCatalogSelectionRetainsOfflineSnapshotExactly() {
        val c = coordinator()
        play(c)
        c.setItems(emptyList())
        val before = c.snapshot()
        val request = c.requestSelected("a")
        assertNull(request.item)
        assertFalse(c.commit(request.version))
        assertEquals(before, c.snapshot())
    }

    @Test fun legacyActualCurrentWithoutHistoryIsAnchoredOnlyAfterSuccessfulSelection() {
        val c = coordinator().apply { restore(PlaybackSnapshot(currentId = "a")) }
        val before = c.snapshot()
        val failed = c.requestSelected("c")
        assertEquals(before, c.snapshot())
        assertTrue(c.reject(failed.version))
        assertEquals(before, c.snapshot())
        pick(c, "c")
        assertEquals(listOf("a", "c"), c.snapshot().historyIds)
        assertEquals("a", c.requestPrevious().item!!.id)
    }

    @Test fun offlineLegacyCurrentIsRetainedButNeverGuessedOrMadeSelectable() {
        val c = coordinator().apply { restore(PlaybackSnapshot(currentId = "offline")) }
        pick(c, "c")
        assertEquals(listOf("offline", "c"), c.snapshot().historyIds)
        assertEquals(listOf("", "A"), c.snapshot().historySourceIds)
        assertNull(c.requestSelected("offline").item)
    }

    @Test fun rejectedTargetDoesNotAdvanceHistorySourcePointOrInvalidationMarker() {
        val c = coordinator()
        play(c)
        c.setOrder(PlaybackOrder.NATURAL_DESC)
        val before = c.snapshot()
        val request = c.requestSelected("c")
        assertTrue(c.reject(request.version))
        assertEquals(before, c.snapshot())
        assertFalse(c.commit(request.version))
    }

    @Test fun successfulSelectionConsumesForwardInvalidationWithoutChangingMode() {
        val c = coordinator()
        repeat(3) { play(c) }
        c.requestPrevious().also { c.commit(it.version) }
        c.setOrder(PlaybackOrder.NATURAL_DESC)
        assertTrue(c.snapshot().forwardHistoryInvalidated)
        pick(c, "d")
        assertFalse(c.snapshot().forwardHistoryInvalidated)
        assertEquals(PlaybackOrder.NATURAL_DESC, c.snapshot().order)
        assertEquals(listOf("a", "b", "d"), c.snapshot().historyIds)
        assertEquals("c", play(c))
    }

    @Test fun selectionBranchesFromCommittedCursorAndDropsDiscardedPlacements() {
        val c = coordinator()
        play(c)
        play(c, ImageTransform(userScale = 2f))
        play(c, ImageTransform(userScale = 3f))
        repeat(2) { c.requestPrevious().also { assertTrue(c.commit(it.version)) } }
        pick(c, "d")
        assertEquals(listOf("a", "d"), c.snapshot().historyIds)
        assertTrue(c.snapshot().historyTransforms.isEmpty())
        assertEquals(listOf("A", "A"), c.snapshot().historySourceIds)
    }

    @Test fun selectionOfExistingHistoricalImageAppendsNewSlotWithoutOldPlacement() {
        val c = coordinator()
        val oldPlacement = ImageTransform(userScale = 2f)
        play(c, oldPlacement)
        play(c)
        val request = c.requestSelected("a")
        assertNull(request.entryTransform)
        assertTrue(c.commit(request.version))
        assertEquals(listOf("a", "b", "a"), c.snapshot().historyIds)
        assertEquals(oldPlacement, c.snapshot().historyTransforms[0])
        assertNull(c.snapshot().currentEntryTransform("a"))
        assertEquals(2, c.snapshot().sourceHistoryCursors["A"])
    }

    @Test fun inheritedTransformArgumentIsIgnoredForExplicitSelection() {
        val c = coordinator()
        val fromPlacement = ImageTransform(userScale = 4f, offsetX = .3f)
        play(c, fromPlacement)
        val request = c.requestSelected("d")
        assertTrue(c.commit(request.version, fromPlacement))
        assertNull(c.snapshot().currentEntryTransform("d"))
        assertEquals(fromPlacement, c.snapshot().historyTransforms[0])
    }

    @Test fun ordinaryNextStillAnnotatesNewEntryAfterSelection() {
        val c = coordinator()
        play(c)
        pick(c, "c")
        val placement = ImageTransform(userScale = 2f)
        val next = c.requestNext()
        assertFalse(next.selected)
        assertTrue(next.freshAdvance)
        assertTrue(c.commit(next.version, placement))
        assertEquals(placement, c.snapshot().currentEntryTransform("d"))
    }

    @Test fun newerSelectionStartsFromCommittedPastNotPendingTarget() {
        val c = coordinator()
        play(c)
        val older = c.requestSelected("b")
        val newest = c.requestSelected("d")
        assertFalse(c.commit(older.version))
        assertTrue(c.commit(newest.version))
        assertEquals(listOf("a", "d"), c.snapshot().historyIds)
    }

    @Test fun selectionSupersedesUncommittedNextWithoutInventedIntermediateHistory() {
        val c = coordinator()
        play(c)
        val pendingNext = c.requestNext()
        val selected = c.requestSelected("d")
        assertFalse(c.commit(pendingNext.version))
        assertTrue(c.commit(selected.version))
        assertEquals(listOf("a", "d"), c.snapshot().historyIds)
    }

    @Test fun catalogChangeWhileSelectedInvalidatesPendingRequestAndKeepsCurrent() {
        val c = coordinator()
        play(c)
        val selected = c.requestSelected("d")
        c.setItems(items.filterNot { it.id == "d" })
        assertFalse(c.commit(selected.version))
        assertEquals("a", c.snapshot().currentId)
        assertEquals(listOf("a"), c.snapshot().historyIds)
    }

    @Test fun snapshotRestoreWhileSelectedInvalidatesPendingRequest() {
        val c = coordinator()
        play(c)
        val selected = c.requestSelected("d")
        val current = c.snapshot().copy(historyResetSerial = 5)
        c.restore(current)
        assertFalse(c.commit(selected.version))
        assertEquals(current, c.snapshot())
    }

    @Test fun selectionPreservesForeignInactiveHistoryAndItsExactCursors() {
        val b = PlaybackItem("B1", "B1.jpg", sourceId = "B")
        val c = PlaybackCoordinator().apply {
            setItems(items + b)
            restore(PlaybackSnapshot(
                historyIds = listOf("a", "b", "B1"), historyCursor = 1, currentId = "b",
                historyTransforms = listOf(null, null, ImageTransform(userScale = 3f)),
                historySourceIds = listOf("A", "A", "B"),
                sourcePositions = mapOf("A" to "b", "B" to "B1"),
                sourceHistoryCursors = mapOf("A" to 1, "B" to 2),
            ))
            setItems(items)
        }
        pick(c, "d")
        assertEquals(listOf("a", "b", "B1", "d"), c.snapshot().historyIds)
        assertEquals("B1", c.snapshot().sourcePositions["B"])
        assertEquals(2, c.snapshot().sourceHistoryCursors["B"])
        assertEquals(3, c.snapshot().sourceHistoryCursors["A"])
        val previous = c.requestPrevious()
        assertEquals("b", previous.item!!.id)
    }

    @Test fun mixedActiveRootsUseOriginalGlobalBranchContract() {
        val b = PlaybackItem("B1", "B1.jpg", sourceId = "B")
        val c = PlaybackCoordinator().apply {
            setItems(items + b)
            restore(PlaybackSnapshot(
                historyIds = listOf("a", "B1", "b"), historyCursor = 0, currentId = "a",
                historySourceIds = listOf("A", "B", "A"),
                sourcePositions = mapOf("A" to "b", "B" to "B1"),
                sourceHistoryCursors = mapOf("A" to 2, "B" to 1),
            ))
        }
        pick(c, "d")
        assertEquals(listOf("a", "d"), c.snapshot().historyIds)
        assertFalse(c.snapshot().sourceHistoryCursors.containsKey("B"))
        assertEquals("a", c.requestPrevious().item!!.id)
    }

    @Test fun unknownInactiveFutureIsNotGuessedAsForeignSource() {
        val c = coordinator().apply {
            restore(PlaybackSnapshot(
                historyIds = listOf("a", "unknown"), historyCursor = 0, currentId = "a",
                sourcePositions = mapOf("B" to "unknown"),
            ))
        }
        pick(c, "d")
        assertEquals(listOf("a", "d"), c.snapshot().historyIds)
    }

    @Test fun selectionUsesLatestCatalogMetadataAndFirstSharedIdentityWinner() {
        val c = coordinator().apply {
            setItems(items + items[2].copy(sourceId = "B", displayName = "other.jpg"))
        }
        val first = c.requestSelected("c")
        assertEquals("A", first.item!!.sourceId)
        c.reject(first.version)
        c.setItems(items.map { if (it.id == "c") it.copy(displayName = "renamed.jpg") else it })
        val changed = c.requestSelected("c")
        assertEquals("renamed.jpg", changed.item!!.displayName)
    }

    @Test fun persistedSelectionRestoresAndContinuesInEveryOrder() {
        for (order in PlaybackOrder.entries) {
            val c = coordinator(order)
            if (order == PlaybackOrder.MANUAL) c.reorder(listOf("d", "a", "c", "b"))
            val orderIds = c.orderedItems().map { it.id }
            play(c)
            pick(c, orderIds[2])
            val stored = c.snapshot().forPersistence()
            val restored = coordinator(order).apply { restore(stored) }
            assertEquals(orderIds[2], restored.current()!!.id)
            assertEquals(orderIds[3], play(restored))
        }
    }

    @Test fun repeatedSelectionsRespectOriginalTwoThousandHistoryWindow() {
        val c = coordinator()
        repeat(PlaybackCoordinator.HISTORY_LIMIT + 20) { pick(c, if (it % 2 == 0) "a" else "d") }
        val after = c.snapshot()
        assertEquals(PlaybackCoordinator.HISTORY_LIMIT, after.historyIds.size)
        assertEquals(after.historyIds.lastIndex, after.historyCursor)
        assertEquals(after.historyCursor, after.sourceHistoryCursors["A"])
        assertEquals(after.historyIds.size, after.historySourceIds.size)
        assertEquals("a", c.requestPrevious().item!!.id)
    }
}
