package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p3.FitMode
import dev.abdus.apps.shimmer.p3.ImageTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 清空历史默认保留排序及当前画面，不过滤暂时停用的手动位置。 */
class PlaybackHistoryResetTest {
    private val placement = ImageTransform(fitMode = FitMode.FIT_WIDTH, userScale = 2f, offsetX = 0.25f)
    private val catalog = listOf("a", "b", "c").map { PlaybackItem(it, "$it.jpg", sourceId = "source") }
    private val previous = PlaybackSnapshot(
        order = PlaybackOrder.MANUAL,
        queueIds = listOf("c", "disabled", "a", "b"),
        queueIndex = 2,
        historyIds = listOf("b", "c", "a", "b"),
        historyCursor = 2,
        currentId = "a",
        historyTransforms = listOf(null, ImageTransform(userScale = 3f), placement, null),
        randomRound = 5,
        previousRoundLastId = "c",
        queueFingerprint = 17,
        sourcePositions = mapOf("source" to "a", "stopped" to "disabled"),
        resumeSourceIds = listOf("stopped"),
    )

    @Test
    fun defaultClearKeepsManualQueueIncludingDisabledPositionsAndCurrentMode() {
        val cleared = clearedPlaybackHistory(previous)

        assertEquals(previous.order, cleared.order)
        assertEquals(previous.queueIds, cleared.queueIds)
        assertEquals(2, cleared.queueIndex)
        assertEquals(previous.sourcePositions, cleared.sourcePositions)
        assertEquals(previous.resumeSourceIds, cleared.resumeSourceIds)
        assertEquals(cleared, cleared.forPersistence())
    }

    @Test
    fun clearDropsBothPastAndForwardHistoryAndRetainsOnlyCurrentPlacementBaseline() {
        val cleared = clearedPlaybackHistory(previous)

        assertEquals(listOf("a"), cleared.historyIds)
        assertEquals(0, cleared.historyCursor)
        assertEquals("a", cleared.currentId)
        assertEquals(listOf(placement), cleared.historyTransforms)
        assertEquals(placement, cleared.currentEntryTransform("a"))
        assertEquals(0, cleared.randomRound)
        assertNull(cleared.previousRoundLastId)
        assertEquals(0, cleared.queueFingerprint)
    }

    @Test
    fun explicitResetClearsAllManualSlotsAndSelectsAscendingWithoutChangingCurrent() {
        val cleared = clearedPlaybackHistory(previous, resetManualOrder = true)

        assertEquals(PlaybackOrder.NATURAL_ASC, cleared.order)
        assertTrue(cleared.queueIds.isEmpty())
        assertEquals(-1, cleared.queueIndex)
        assertEquals("a", cleared.currentId)
        assertEquals(listOf("a"), cleared.historyIds)
        assertEquals(listOf(placement), cleared.historyTransforms)
        assertEquals(previous.sourcePositions, cleared.sourcePositions)
    }

    @Test
    fun allFourModesAreKeptUnlessResetIsExplicitlySelected() {
        PlaybackOrder.entries.forEach { order ->
            val prior = previous.copy(order = order)
            val kept = clearedPlaybackHistory(prior)
            val reset = clearedPlaybackHistory(prior, true)

            assertEquals(order, kept.order)
            assertEquals("a", kept.currentId)
            assertEquals(if (order == PlaybackOrder.MANUAL) prior.queueIds else emptyList<String>(), kept.queueIds)
            assertEquals(PlaybackOrder.NATURAL_ASC, reset.order)
            assertEquals("a", reset.currentId)
            assertTrue(reset.queueIds.isEmpty())
        }
    }

    @Test
    fun randomClearStartsANewRoundOnlyOnANextGesture() {
        val coordinator = PlaybackCoordinator(randomSeed = 123)
        coordinator.setItems(catalog)
        val cleared = clearedPlaybackHistory(previous.copy(order = PlaybackOrder.RANDOM))
        coordinator.restore(cleared)

        assertEquals("a", coordinator.current()!!.id)
        assertTrue(coordinator.snapshot().queueIds.isEmpty())
        assertEquals(0, coordinator.snapshot().randomRound)
        assertEquals(listOf("a"), coordinator.snapshot().historyIds)
        val next = coordinator.requestNext()
        assertEquals("a", coordinator.snapshot().currentId)
        assertTrue(coordinator.commit(next.version))
        assertTrue(coordinator.snapshot().randomRound >= 0)
        assertEquals(catalog.size, coordinator.snapshot().queueIds.size)
        assertTrue(next.item != null)
    }

    @Test
    fun previousCannotReachTheClearedHistoryEvenAfterRestoringTheCoordinator() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        val cleared = clearedPlaybackHistory(previous)
        coordinator.restore(cleared)
        val back = coordinator.requestPrevious()

        assertEquals("a", back.item!!.id)
        assertFalse(back.freshAdvance)
        assertEquals(placement, back.entryTransform)
        assertTrue(coordinator.commit(back.version))
        assertEquals(listOf("a"), coordinator.snapshot().historyIds)
        assertEquals(0, coordinator.snapshot().historyCursor)
    }

    @Test
    fun manualNextContinuesAfterCurrentAndCanReturnOnlyToNewBaseline() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        coordinator.restore(clearedPlaybackHistory(previous))
        val next = coordinator.requestNext()
        assertEquals("b", next.item!!.id)
        assertTrue(coordinator.commit(next.version))
        assertEquals(listOf("a", "b"), coordinator.snapshot().historyIds)
        val back = coordinator.requestPrevious()
        assertEquals("a", back.item!!.id)
        assertEquals(placement, back.entryTransform)
        assertTrue(coordinator.commit(back.version))
        assertEquals(0, coordinator.snapshot().historyCursor)
    }

    @Test
    fun restoringADisabledSourceKeepsItsOriginalManualPosition() {
        val cleared = clearedPlaybackHistory(previous)
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        coordinator.restore(cleared)
        assertEquals(listOf("c", "a", "b"), coordinator.orderedItems().map { it.id })

        coordinator.setItems(catalog + PlaybackItem("disabled", "disabled.jpg", sourceId = "stopped"))
        coordinator.restore(cleared)

        assertEquals(previous.queueIds, coordinator.orderedItems().map { it.id })
        assertEquals("a", coordinator.current()!!.id)
    }

    @Test
    fun currentOutsideTheCatalogIsRetainedWithoutInventingANewImage() {
        val cleared = clearedPlaybackHistory(previous.copy(currentId = "disabled", historyCursor = 1,
            historyIds = listOf("b", "disabled"), historyTransforms = listOf(null, placement)))
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        coordinator.restore(cleared)

        assertEquals("disabled", coordinator.snapshot().currentId)
        assertEquals(listOf("disabled"), coordinator.snapshot().historyIds)
        assertEquals(placement, coordinator.snapshot().currentEntryTransform("disabled"))
        assertNull(coordinator.current())
        assertEquals(previous.queueIds, cleared.queueIds)
    }

    @Test
    fun missingCurrentFieldFallsBackToTheCommittedHistoryCursor() {
        val cleared = clearedPlaybackHistory(previous.copy(currentId = null))

        assertEquals("a", cleared.currentId)
        assertEquals(listOf("a"), cleared.historyIds)
        assertEquals(listOf(placement), cleared.historyTransforms)
    }

    @Test
    fun aMismatchingCurrentDoesNotInheritAnotherImagesPlacement() {
        val cleared = clearedPlaybackHistory(previous.copy(currentId = "c"))

        assertEquals("c", cleared.currentId)
        assertEquals(listOf("c"), cleared.historyIds)
        assertTrue(cleared.historyTransforms.isEmpty())
        assertEquals(0, cleared.queueIndex)
    }

    @Test
    fun absentTransformStaysEmptyAndInvalidCursorDoesNotThrow() {
        val cleared = clearedPlaybackHistory(previous.copy(historyCursor = 99))

        assertEquals("a", cleared.currentId)
        assertEquals(listOf("a"), cleared.historyIds)
        assertTrue(cleared.historyTransforms.isEmpty())
    }

    @Test
    fun clearingWithoutAnyCurrentDoesNotChooseTheFirstQueuedItem() {
        val cleared = clearedPlaybackHistory(previous.copy(currentId = null, historyCursor = -1))

        assertNull(cleared.currentId)
        assertTrue(cleared.historyIds.isEmpty())
        assertTrue(cleared.historyTransforms.isEmpty())
        assertEquals(-1, cleared.historyCursor)
        assertEquals(-1, cleared.queueIndex)
        assertEquals(previous.queueIds, cleared.queueIds)
    }

    @Test
    fun firstUseWithNoSnapshotRemainsAnEmptyAscendingState() {
        val cleared = clearedPlaybackHistory(null)

        assertEquals(PlaybackSnapshot(historyResetSerial = 1), cleared)
    }

    @Test
    fun explicitResetKeepsCurrentThroughAscendingCoordinatorRestore() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        val cleared = clearedPlaybackHistory(previous.copy(currentId = "c", historyCursor = 1), true)
        coordinator.restore(cleared)

        assertEquals(PlaybackOrder.NATURAL_ASC, coordinator.snapshot().order)
        assertEquals(listOf("a", "b", "c"), coordinator.orderedItems().map { it.id })
        assertEquals("c", coordinator.current()!!.id)
        assertEquals(listOf("c"), coordinator.snapshot().historyIds)
    }

    @Test
    fun eachClearCreatesANewCommitBoundaryEvenIfTheBaselineIsAlreadyEmptyOfPastHistory() {
        val first = clearedPlaybackHistory(previous)
        val second = clearedPlaybackHistory(first)

        assertEquals(1L, first.historyResetSerial)
        assertEquals(2L, second.historyResetSerial)
        assertEquals(first.copy(historyResetSerial = 2), second)
    }

    @Test
    fun resetSerialSurvivesRestoreOrderChangesAndGestureCommit() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        coordinator.restore(clearedPlaybackHistory(previous.copy(historyResetSerial = 8)))
        assertEquals(9L, coordinator.snapshot().historyResetSerial)
        coordinator.setOrder(PlaybackOrder.NATURAL_DESC)
        assertEquals(9L, coordinator.snapshot().historyResetSerial)
        val next = coordinator.requestNext()
        assertTrue(coordinator.commit(next.version))
        assertEquals(9L, coordinator.snapshot().historyResetSerial)
    }

    @Test
    fun serialAtMaximumWrapsWithoutKeepingTheOldCommitBoundary() {
        val cleared = clearedPlaybackHistory(previous.copy(historyResetSerial = Long.MAX_VALUE))

        assertEquals(0L, cleared.historyResetSerial)
        assertEquals("a", cleared.currentId)
    }
}
