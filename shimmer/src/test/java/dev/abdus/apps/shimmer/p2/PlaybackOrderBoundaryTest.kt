package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.ui.playback.PlayOrder
import dev.abdus.apps.shimmer.ui.playback.PlaybackOrderUpdate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 显式配置边界不清历史、不自动推进；NEXT 采用新队列，PREVIOUS 保持真实历史。 */
class PlaybackOrderBoundaryTest {
    private val items = (1..5).map { PlaybackItem("u$it", "$it.jpg", sourceId = "A") }
    private val past = PlaybackSnapshot(
        historyIds = listOf("u1", "u2", "u3", "u4"), historyCursor = 1, currentId = "u2",
        historyTransforms = listOf(null, ImageTransform(userScale = 2f), ImageTransform(userScale = 3f)),
        historySourceIds = listOf("A", "A", "A", "A"),
        sourcePositions = mapOf("A" to "u2", "B" to "b2"), sourceHistoryCursors = mapOf("A" to 1),
    )
    private fun restored(snapshot: PlaybackSnapshot = past) = PlaybackOrderUpdate.newCoordinator().apply {
        setItems(items); restore(snapshot)
    }

    @Test fun modeSelectionWithoutCatalogKeepsTheCurrentHistoryAndSourcePoints() {
        for (mode in PlayOrder.entries.filter { it != PlayOrder.NATURAL_ASC }) {
            val selected = PlaybackOrderUpdate.select(past, mode)
            assertEquals("u2", selected.currentId)
            assertEquals(past.historyIds, selected.historyIds)
            assertEquals(past.historyTransforms, selected.historyTransforms)
            assertEquals(past.historySourceIds, selected.historySourceIds)
            assertEquals(past.sourcePositions, selected.sourcePositions)
            assertEquals(past.sourceHistoryCursors, selected.sourceHistoryCursors)
            assertTrue(selected.forwardHistoryInvalidated)
        }
    }
    @Test fun sameModeSelectionReturnsTheExactSnapshotWithoutResettingItsBoundary() {
        val manual = past.copy(order = PlaybackOrder.MANUAL, queueIds = listOf("hidden", "u2", "u1"), forwardHistoryInvalidated = true)
        assertSame(manual, PlaybackOrderUpdate.select(manual, PlayOrder.MANUAL))
        assertSame(past, PlaybackOrderUpdate.select(past, PlayOrder.NATURAL_ASC))
    }
    @Test fun nextUsesDescendingQueueRatherThanOldAscendingFuture() {
        val c = restored(PlaybackOrderUpdate.select(past, PlayOrder.NATURAL_DESC))
        val request = c.requestNext()
        assertEquals("u1", request.item!!.id)
        assertTrue(request.freshAdvance)
        assertTrue(c.snapshot().forwardHistoryInvalidated)
        assertTrue(c.commit(request.version))
        assertFalse(c.snapshot().forwardHistoryInvalidated)
        assertEquals(listOf("u1", "u2", "u1"), c.snapshot().historyIds)
    }
    @Test fun previousUsesActualHistoryAndRetainsNewQueueSelection() {
        val c = restored(PlaybackOrderUpdate.select(past, PlayOrder.NATURAL_DESC))
        val request = c.requestPrevious()
        assertEquals("u1", request.item!!.id)
        assertFalse(request.freshAdvance)
        c.commit(request.version)
        assertTrue(c.snapshot().forwardHistoryInvalidated)
        assertEquals(past.historyIds, c.snapshot().historyIds)
        assertEquals("u5", c.requestNext().item!!.id)
    }
    @Test fun rejectDoesNotConsumeTheNewModeBoundary() {
        val c = restored(PlaybackOrderUpdate.select(past, PlayOrder.NATURAL_DESC))
        val request = c.requestNext()
        assertEquals("u1", request.item!!.id)
        assertTrue(c.reject(request.version))
        assertTrue(c.snapshot().forwardHistoryInvalidated)
        assertEquals("u2", c.current()!!.id)
        assertEquals("u1", c.requestNext().item!!.id)
    }
    @Test fun supersededRequestCannotClearNewSelection() {
        val c = restored()
        val oldRequest = c.requestNext()
        c.setOrder(PlaybackOrder.NATURAL_DESC)
        assertFalse(c.commit(oldRequest.version))
        assertTrue(c.snapshot().forwardHistoryInvalidated)
        assertEquals("u1", c.requestNext().item!!.id)
    }
    @Test fun noReadableCandidateCannotConsumeTheBoundaryOrMutateHistory() {
        val c = restored(PlaybackOrderUpdate.select(past, PlayOrder.NATURAL_DESC))
        val before = c.snapshot()
        val request = c.requestNext(items.map { it.id }.toSet())
        assertNull(request.item)
        c.reject(request.version)
        assertEquals(before, c.snapshot())
    }
    @Test fun revertingToOriginalModeStillFormsAnExplicitBoundary() {
        val selected = PlaybackOrderUpdate.select(PlaybackOrderUpdate.select(past, PlayOrder.NATURAL_DESC), PlayOrder.NATURAL_ASC)
        assertTrue(selected.forwardHistoryInvalidated)
        val c = restored(selected)
        assertTrue(c.requestNext().freshAdvance)
    }
    @Test fun sameOrderCoordinatorCallDoesNotCreateNewBoundary() {
        val c = restored()
        c.setOrder(PlaybackOrder.NATURAL_ASC)
        assertFalse(c.snapshot().forwardHistoryInvalidated)
        val next = c.requestNext()
        assertEquals("u3", next.item!!.id)
        assertFalse(next.freshAdvance)
    }
    @Test fun manualReorderOnlyInvalidatesWhenItChangesTheActualOrder() {
        val manual = past.copy(order = PlaybackOrder.MANUAL, queueIds = items.map { it.id })
        val c = restored(manual)
        c.reorder(items.map { it.id })
        assertFalse(c.snapshot().forwardHistoryInvalidated)
        c.reorder(listOf("u1", "u2", "u5", "u4", "u3"))
        assertTrue(c.snapshot().forwardHistoryInvalidated)
        assertEquals("u5", c.requestNext().item!!.id)
    }
    @Test fun manuallyHiddenSlotsArePreservedByUnchangedReorder() {
        val manual = past.copy(order = PlaybackOrder.MANUAL, queueIds = listOf("hidden") + items.map { it.id })
        val c = restored(manual)
        c.reorder(items.map { it.id })
        assertEquals(manual.queueIds, c.snapshot().queueIds)
        assertFalse(c.snapshot().forwardHistoryInvalidated)
    }
    @Test fun randomModeUsesNewRoundQueueAndMatchesPageSeed() {
        val selected = PlaybackOrderUpdate.select(past, PlayOrder.RANDOM)
        val c = restored(selected)
        val page = restored(selected).apply { setOrder(PlaybackOrder.RANDOM) }
        val queue = page.orderedItems().map { it.id }
        val nextIndex = (queue.indexOf("u2") + 1) % queue.size
        val request = c.requestNext()
        assertEquals(queue[nextIndex], request.item!!.id)
        assertTrue(request.freshAdvance)
        assertEquals(past.historyIds, c.snapshot().historyIds)
    }
    @Test fun sourceRestorationDoesNotConsumeExplicitOrderBoundary() {
        val c = PlaybackOrderUpdate.newCoordinator().apply {
            setItems(items)
            restore(past.copy(currentId = "b2", historyIds = listOf("u1", "u2", "b2"), historyCursor = 2,
                sourcePositions = mapOf("A" to "u2", "B" to "b2"), forwardHistoryInvalidated = true))
        }
        val request = c.requestSourceRestore()!!
        assertEquals("u2", request.item!!.id)
        assertFalse(request.freshAdvance)
        c.commit(request.version)
        assertTrue(c.snapshot().forwardHistoryInvalidated)
    }
    @Test fun confirmedOtherRootFutureSurvivesNewModeBranch() {
        val mixed = past.copy(historyIds = listOf("u1", "u2", "u3", "b1", "b2"),
            historySourceIds = listOf("A", "A", "A", "B", "B"), sourceHistoryCursors = mapOf("A" to 1, "B" to 4))
        val c = restored(PlaybackOrderUpdate.select(mixed, PlayOrder.NATURAL_DESC))
        c.requestNext().also { c.commit(it.version) }
        assertEquals(listOf("u1", "u2", "b1", "b2", "u1"), c.snapshot().historyIds)
        assertEquals(3, c.snapshot().sourceHistoryCursors["B"])
        assertEquals("b2", c.snapshot().sourcePositions["B"])
    }
    @Test fun oldJsonDefaultsFalseAndNewMarkerRoundTrips() {
        val json = Json
        assertFalse(json.decodeFromString<PlaybackSnapshot>("{\"currentId\":\"u1\"}").forwardHistoryInvalidated)
        val marked = past.copy(forwardHistoryInvalidated = true)
        assertEquals(marked, json.decodeFromString<PlaybackSnapshot>(json.encodeToString(marked)))
    }
    @Test fun restoringAndPersistingMarkedSnapshotDoesNotConsumeBoundary() {
        val marked = past.copy(forwardHistoryInvalidated = true)
        assertTrue(marked.forPersistence().forwardHistoryInvalidated)
        assertTrue(restored(marked).snapshot().forwardHistoryInvalidated)
    }
    @Test fun historyPlacementIsNotOverwrittenOnPreviousAfterModeChange() {
        val marked = past.copy(historyCursor = 2, currentId = "u3", forwardHistoryInvalidated = true)
        val c = restored(marked)
        val request = c.requestPrevious()
        assertEquals(ImageTransform(userScale = 2f), request.entryTransform)
        c.commit(request.version, ImageTransform(userScale = 9f))
        assertEquals(marked.historyTransforms, c.snapshot().historyTransforms)
    }
    @Test fun firstSelectionCanBeSavedWithNoCatalogOrCurrentImage() {
        val selected = PlaybackOrderUpdate.select(null, PlayOrder.RANDOM)
        assertEquals(PlaybackOrder.RANDOM, selected.order)
        assertNull(selected.currentId)
        assertTrue(selected.historyIds.isEmpty())
        assertTrue(selected.forwardHistoryInvalidated)
    }
    @Test fun newManualModeStartsFromTheActuallyDisplayedOrderNotDiscoveryOrder() {
        val seed = listOf("u5", "u4", "u3", "u2", "u1")
        val selected = PlaybackOrderUpdate.select(past, PlayOrder.MANUAL, seed)
        assertEquals(seed, selected.queueIds)
        assertEquals(3, selected.queueIndex)
        val c = restored(selected)
        assertEquals("u1", c.requestNext().item!!.id)
    }
    @Test fun staleManualSeedCannotOverrideTheAlreadySavedManualOrder() {
        val manual = past.copy(order = PlaybackOrder.MANUAL, queueIds = listOf("hidden", "u1", "u3", "u2"))
        assertSame(manual, PlaybackOrderUpdate.select(manual, PlayOrder.MANUAL, listOf("u3", "u2", "u1")))
    }
    @Test fun manualSeedIsDefensivelyCopiedAndNewCatalogItemsAreAppended() {
        val seed = mutableListOf("u3", "u2", "u1", "u1", "")
        val selected = PlaybackOrderUpdate.select(past, PlayOrder.MANUAL, seed)
        seed.clear()
        assertEquals(listOf("u3", "u2", "u1"), selected.queueIds)
        assertEquals(listOf("u3", "u2", "u1", "u4", "u5"), restored(selected).orderedItems().map { it.id })
    }
    @Test fun seedingARealDatabaseCurrentDoesNotConsumeTheNewModeBoundary() {
        val c = restored(PlaybackOrderUpdate.select(null, PlayOrder.NATURAL_DESC))
        c.restoreCurrent("u2")
        assertEquals("u2", c.current()!!.id)
        assertTrue(c.snapshot().forwardHistoryInvalidated)
        c.requestNext().also { c.commit(it.version) }
        assertFalse(c.snapshot().forwardHistoryInvalidated)
    }
}
