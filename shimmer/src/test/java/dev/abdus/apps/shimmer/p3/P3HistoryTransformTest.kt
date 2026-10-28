package dev.abdus.apps.shimmer.p3

import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.withoutTransformsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3 placements recorded on history entries by inheritance (WP3-02). */
class P3HistoryTransformTest {
    private val items = listOf("a", "b", "c", "d").map { PlaybackItem(it, "$it.jpg", "dir/$it.jpg", "source") }
    private val inherited = ImageTransform(userScale = 2f, offsetX = 0.25f)

    private fun coordinator(): PlaybackCoordinator = PlaybackCoordinator().apply {
        setItems(items)
        setOrder(PlaybackOrder.NATURAL_ASC)
    }

    private fun PlaybackCoordinator.next(transform: ImageTransform? = null): String? {
        val request = requestNext()
        assertTrue(commit(request.version, transform))
        return request.item?.id
    }

    private fun PlaybackCoordinator.previous(): String? {
        val request = requestPrevious()
        assertTrue(commit(request.version))
        return request.item?.id
    }

    @Test
    fun onlyAFreshAdvanceIsMarkedFreshAndCarriesNoEntryPlacement() {
        val coordinator = coordinator()
        val first = coordinator.requestNext()
        assertTrue(first.freshAdvance)
        assertNull(first.entryTransform)
        coordinator.commit(first.version)
        coordinator.next(inherited)

        val back = coordinator.requestPrevious()
        assertFalse(back.freshAdvance)
        coordinator.commit(back.version)

        val forward = coordinator.requestNext()
        assertFalse(forward.freshAdvance)
        assertEquals("b", forward.item?.id)
        assertEquals(inherited, forward.entryTransform)
    }

    @Test
    fun inheritedPlacementIsRecordedOnTheNewEntryAndSurvivesRevisits() {
        val coordinator = coordinator()
        assertEquals("a", coordinator.next())
        assertEquals("b", coordinator.next(inherited))

        assertEquals(listOf(null, inherited), coordinator.snapshot().historyTransforms)
        assertEquals("a", coordinator.previous())
        assertEquals(listOf(null, inherited), coordinator.snapshot().historyTransforms)
    }

    @Test
    fun revisitCommitCannotOverwriteAnEntryPlacement() {
        val coordinator = coordinator()
        coordinator.next()
        coordinator.next(inherited)
        coordinator.previous()

        val forward = coordinator.requestNext()
        assertTrue(coordinator.commit(forward.version, ImageTransform(userScale = 5f)))
        assertEquals(listOf(null, inherited), coordinator.snapshot().historyTransforms)
    }

    @Test
    fun branchingFromHistoryDropsTheDiscardedForwardPlacements() {
        val coordinator = coordinator()
        coordinator.next(inherited)
        coordinator.next(inherited)
        coordinator.next(inherited)
        assertEquals(3, coordinator.snapshot().historyTransforms.size)
        coordinator.previous()
        coordinator.previous()
        // Forward entries b and c are disabled, so the next gesture appends a new branch after a.
        coordinator.setItems(items.filter { it.id == "a" || it.id == "d" })

        val branch = coordinator.requestNext()
        assertTrue(branch.freshAdvance)
        assertEquals("d", branch.item?.id)
        coordinator.commit(branch.version)

        val snapshot = coordinator.snapshot()
        assertEquals(listOf("a", "d"), snapshot.historyIds)
        assertEquals(listOf(inherited), snapshot.historyTransforms)
    }

    @Test
    fun rejectedRequestLeavesPlacementsUntouched() {
        val coordinator = coordinator()
        coordinator.next(inherited)
        val before = coordinator.snapshot()
        val request = coordinator.requestNext()
        assertTrue(coordinator.reject(request.version))
        assertFalse(coordinator.commit(request.version, inherited))
        assertEquals(before, coordinator.snapshot())
    }

    @Test
    fun restoreCurrentResetsHistoryPlacements() {
        val coordinator = coordinator()
        coordinator.next(inherited)
        coordinator.restoreCurrent("c")
        assertEquals(emptyList<ImageTransform?>(), coordinator.snapshot().historyTransforms)
    }

    @Test
    fun trimmedHistoryKeepsPlacementsAlignedWithTheirEntries() {
        val ids = (0 until PlaybackCoordinator.HISTORY_LIMIT + 10).map { items[it % items.size].id }
        val marker = ImageTransform(offsetY = 0.5f)
        // Placement at index 1_700 sits after the 10 trimmed leading entries.
        val transforms = List(1_701) { index -> if (index == 1_700) marker else null }
        val coordinator = coordinator()
        coordinator.restore(
            PlaybackSnapshot(
                historyIds = ids,
                historyCursor = ids.lastIndex,
                currentId = ids.last(),
                historyTransforms = transforms,
            ),
        )

        val snapshot = coordinator.snapshot()
        assertEquals(PlaybackCoordinator.HISTORY_LIMIT, snapshot.historyIds.size)
        assertEquals(1_691, snapshot.historyTransforms.size)
        assertEquals(marker, snapshot.historyTransforms[1_690])
        assertEquals(ids[1_700], snapshot.historyIds[1_690])
    }

    @Test
    fun placementListLongerThanHistoryIsCutAndTrailingNullsDropped() {
        val coordinator = coordinator()
        coordinator.restore(
            PlaybackSnapshot(
                historyIds = listOf("a", "b"),
                historyCursor = 1,
                currentId = "b",
                historyTransforms = listOf(inherited, null, inherited, inherited),
            ),
        )
        assertEquals(listOf(inherited), coordinator.snapshot().historyTransforms)
    }

    @Test
    fun savingAnImageDropsEveryInheritedCopyOfIt() {
        val snapshot = PlaybackSnapshot(
            historyIds = listOf("a", "b", "a", "c"),
            historyCursor = 3,
            currentId = "c",
            historyTransforms = listOf(inherited, inherited, inherited, null),
        )
        assertEquals(listOf(null, inherited), snapshot.withoutTransformsFor("a").historyTransforms)
        assertEquals(emptyList<ImageTransform?>(), snapshot.withoutTransformsFor("a").withoutTransformsFor("b").historyTransforms)
        assertEquals(snapshot, snapshot.withoutTransformsFor("d"))
    }
}
