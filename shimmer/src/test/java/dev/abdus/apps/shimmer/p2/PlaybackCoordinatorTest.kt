package dev.abdus.apps.shimmer.p2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    private val items = listOf(
        PlaybackItem("a", "2.jpg", "dir/2.jpg", "source-a"),
        PlaybackItem("b", "10.jpg", "dir/10.jpg", "source-a"),
        PlaybackItem("c", "same.jpg", "z/same.jpg", "source-z"),
        PlaybackItem("d", "same.jpg", "a/same.jpg", "source-a"),
    )

    /** P4 resume fixtures: three P1 sources in list order A, B, C. */
    private fun source(id: String, order: Long, count: Int) = (1..count).map {
        PlaybackItem("$id$it", "$it.jpg", "$it.jpg", id, sourceOrder = order)
    }
    private val sourceA = source("A", 0, 5)
    private val sourceB = source("B", 1, 3)
    private val sourceC = source("C", 2, 3)

    private fun PlaybackCoordinator.play(count: Int): List<String> = (1..count).map {
        requestNext().also { request -> assertTrue(commit(request.version)) }.item!!.id
    }

    @Test
    fun naturalOrderUsesNumericChunksAndStableTieBreakers() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items)
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)

        assertEquals(listOf("d", "a", "b", "c"), coordinator.orderedItems().map { it.id })
    }

    @Test
    fun naturalOrderGroupsParentAndChildDirectoriesBeforeComparingFileNames() {
        val hierarchicalItems = listOf(
            PlaybackItem("root-2", "2.jpg", "root/2.jpg", "source"),
            PlaybackItem("root-10", "10.jpg", "root/10.jpg", "source"),
            PlaybackItem("sub-1", "1.jpg", "root/sub/1.jpg", "source"),
            PlaybackItem("sub-20", "20.jpg", "root/sub/20.jpg", "source"),
            PlaybackItem("z-1", "1.jpg", "root/z/1.jpg", "source"),
            PlaybackItem("root2-1", "1.jpg", "root2/1.jpg", "source"),
        )
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(hierarchicalItems)

        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        assertEquals(
            listOf("root-2", "root-10", "sub-1", "sub-20", "z-1", "root2-1"),
            coordinator.orderedItems().map { it.id },
        )

        coordinator.setOrder(PlaybackOrder.NATURAL_DESC)
        assertEquals(
            listOf("root2-1", "root-10", "root-2", "z-1", "sub-20", "sub-1"),
            coordinator.orderedItems().map { it.id },
        )
    }

    @Test
    fun naturalOrderComparesLongDigitRunsAndLeadingZerosNumerically() {
        val numbered = listOf("100000000000000000000.jpg", "9.jpg", "010.jpg", "2.jpg", "10.jpg")
            .map { PlaybackItem(it, it, it, "source") }
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(numbered)
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)

        // 010 and 10 are numerically equal; the raw text keeps their order deterministic.
        assertEquals(
            listOf("2.jpg", "9.jpg", "010.jpg", "10.jpg", "100000000000000000000.jpg"),
            coordinator.orderedItems().map { it.id },
        )
    }

    @Test
    fun naturalOrderIsRecomputedWhenCatalogChangesButReusedWhenUnchanged() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items)
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        val first = coordinator.orderedItems().map { it.id }

        coordinator.setItems(items.toList())
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        assertEquals(first, coordinator.orderedItems().map { it.id })

        coordinator.setItems(items + PlaybackItem("e", "1.jpg", "dir/1.jpg", "source-a"))
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        assertEquals(listOf("d", "e", "a", "b", "c"), coordinator.orderedItems().map { it.id })

        coordinator.setItems(items.map { if (it.id == "b") it.copy(relativePath = "dir/0.jpg") else it })
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        assertEquals(listOf("d", "b", "a", "c"), coordinator.orderedItems().map { it.id })
    }

    @Test
    fun largeCatalogSortsWithinInteractiveTime() {
        val large = (1..5_000).map { index ->
            PlaybackItem("id-$index", "IMG_$index.jpg", "dir${index % 7}/IMG_$index.jpg", "source")
        }.shuffled(java.util.Random(7))
        val coordinator = PlaybackCoordinator()
        val started = System.nanoTime()
        coordinator.setItems(large)
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        coordinator.setOrder(PlaybackOrder.NATURAL_DESC)
        coordinator.setItems(large)
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        val ordered = coordinator.orderedItems()
        assertEquals("dir0/IMG_7.jpg", ordered.first().relativePath)
        assertEquals("dir0/IMG_14.jpg", ordered[1].relativePath)
        // Generous bound for slow CI hosts; the previous per-comparison regex took far longer.
        assertTrue("sorting took ${elapsedMillis}ms", elapsedMillis < 5_000)
    }

    @Test
    fun manualOrderKeepsExistingPositionsAndAppendsNewItems() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))
        coordinator.setOrder(PlaybackOrder.MANUAL)
        coordinator.reorder(listOf("c", "a", "b"))
        coordinator.setItems(items)

        assertEquals(listOf("c", "a", "b", "d"), coordinator.orderedItems().map { it.id })
    }

    @Test
    fun randomRoundDoesNotRepeatAndAvoidsAdjacentRoundBoundary() {
        val coordinator = PlaybackCoordinator(randomSeed = 42L)
        coordinator.setItems(items.take(3))
        coordinator.setOrder(PlaybackOrder.RANDOM)

        val firstRound = buildList {
            repeat(3) {
                add(requireNotNull(coordinator.requestNext().also { coordinator.commit(it.version) }.item).id)
            }
        }
        val lastOfFirstRound = firstRound.last()
        val firstOfSecondRound = requireNotNull(coordinator.requestNext().also { coordinator.commit(it.version) }.item).id

        assertEquals(3, firstRound.toSet().size)
        assertTrue(firstRound.contains(firstOfSecondRound))
        assertTrue(lastOfFirstRound != firstOfSecondRound)
    }

    @Test
    fun previousAndForwardUseCommittedHistoryWithoutCreatingDuplicates() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))

        val first = coordinator.requestNext().also { coordinator.commit(it.version) }.item
        val second = coordinator.requestNext().also { coordinator.commit(it.version) }.item
        val third = coordinator.requestNext().also { coordinator.commit(it.version) }.item
        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)

        val previous = coordinator.requestPrevious().also { coordinator.commit(it.version) }.item
        val forward = coordinator.requestNext().also { coordinator.commit(it.version) }.item

        assertEquals(second, previous)
        assertEquals(third, forward)
        assertEquals(listOf(first!!.id, second!!.id, third!!.id), coordinator.snapshot().historyIds)
    }

    @Test
    fun rejectedCandidateDoesNotPolluteHistory() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(2))

        val first = coordinator.requestNext()
        assertTrue(coordinator.commit(first.version))
        val candidate = coordinator.requestNext()
        assertTrue(coordinator.reject(candidate.version))

        assertEquals(listOf(first.item!!.id), coordinator.snapshot().historyIds)
        assertEquals(first.item, coordinator.current())
    }

    @Test
    fun staleResultCannotCommitOverNewerRequest() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))

        val first = coordinator.requestNext()
        assertTrue(coordinator.commit(first.version))
        val stale = coordinator.requestNext()
        val latest = coordinator.requestPrevious()

        assertFalse(coordinator.commit(stale.version))
        assertTrue(coordinator.commit(latest.version))
        assertEquals(first.item, coordinator.current())
    }

    @Test
    fun snapshotRestoresHistoryCursorAndCurrentItem() {
        val original = PlaybackCoordinator(randomSeed = 7L)
        original.setItems(items.take(3))
        repeat(3) { original.requestNext().also { original.commit(it.version) } }
        original.requestPrevious().also { original.commit(it.version) }
        val snapshot = original.snapshot()

        val restored = PlaybackCoordinator(randomSeed = 999L)
        restored.setItems(items.take(3))
        restored.restore(snapshot)

        assertEquals(snapshot, restored.snapshot())
        assertEquals(original.current(), restored.current())
        assertEquals(original.requestNext().item, restored.requestNext().item)
    }

    @Test
    fun restoringNewOrderKeepsTheImageAlreadyShownOnScreen() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))
        coordinator.setOrder(PlaybackOrder.NATURAL_ASC)
        repeat(3) { coordinator.requestNext().also { coordinator.commit(it.version) } }
        val snapshot = coordinator.snapshot()

        coordinator.restoreKeepingCurrent(snapshot.copy(order = PlaybackOrder.NATURAL_DESC), "a")

        assertEquals("a", coordinator.current()?.id)
        assertEquals(PlaybackOrder.NATURAL_DESC, coordinator.snapshot().order)
        assertEquals(snapshot.historyIds, coordinator.snapshot().historyIds)
    }

    @Test
    fun snapshotNotificationsMustNotResetPreviousCursorToHistoryEnd() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))
        repeat(3) { coordinator.requestNext().also { coordinator.commit(it.version) } }
        val seen = mutableListOf<String?>()
        repeat(4) {
            val request = coordinator.requestPrevious()
            coordinator.commit(request.version)
            seen += request.item?.id
            // Reproduce the service's SharedPreferences callback after a committed frame.
            coordinator.restoreKeepingCurrent(coordinator.snapshot(), request.item?.id)
        }
        assertEquals(listOf("b", "a", "a", "a"), seen)
    }

    @Test
    fun disablingEarlierHistoryDoesNotShiftCursorPastCurrentOccurrence() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))
        repeat(4) { coordinator.requestNext().also { coordinator.commit(it.version) } }
        coordinator.requestPrevious().also { coordinator.commit(it.version) } // c, history a b c a
        coordinator.setItems(items.take(3).filter { it.id != "a" })
        assertEquals("b", coordinator.requestPrevious().item?.id)
    }

    @Test
    fun disablingCurrentImageDoesNotConsumeThePreviousHistoryBeforeTheGesture() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))
        repeat(3) { coordinator.requestNext().also { coordinator.commit(it.version) } }
        coordinator.setItems(items.take(2))
        assertEquals("b", coordinator.requestPrevious().item?.id)
    }

    @Test
    fun randomCatalogGrowthIncludesTheNewImageWithoutReplayingCompletedItems() {
        val coordinator = PlaybackCoordinator(42L)
        coordinator.setItems(items.take(3))
        coordinator.setOrder(PlaybackOrder.RANDOM)
        val first = coordinator.requestNext().also { coordinator.commit(it.version) }.item!!.id
        coordinator.setItems(items)
        val remaining = (1..3).map {
            coordinator.requestNext().also { coordinator.commit(it.version) }.item!!.id
        }
        assertEquals(setOf("a", "b", "c", "d"), (listOf(first) + remaining).toSet())
        assertFalse(first in remaining)
        assertEquals(0, coordinator.snapshot().randomRound)
    }

    @Test
    fun disablingAllSourcesAndReenablingDoesNotDeleteSavedManualPositions() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))
        coordinator.reorder(listOf("c", "b", "a"))
        coordinator.setItems(emptyList())
        val savedWhileDisabled = coordinator.snapshot()
        coordinator.setItems(items.take(3))
        coordinator.restore(savedWhileDisabled)
        assertEquals(listOf("c", "b", "a"), coordinator.orderedItems().map { it.id })
    }

    @Test
    fun manualOrderKeepsInactivePositionsWhileNavigationAndNewItemsUseActiveCatalog() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(3))
        coordinator.reorder(listOf("c", "a", "b"))
        coordinator.restoreCurrent("c")
        coordinator.setItems(items.filter { it.id != "a" })

        assertEquals(listOf("c", "a", "b", "d"), coordinator.snapshot().queueIds)
        assertEquals(listOf("c", "b", "d"), coordinator.orderedItems().map { it.id })
        val next = coordinator.requestNext()
        assertEquals("b", next.item?.id)
        assertTrue(coordinator.commit(next.version))
        assertEquals(2, coordinator.snapshot().queueIndex)

        val restored = PlaybackCoordinator().apply {
            setItems(items.filter { it.id != "a" })
            restore(coordinator.snapshot())
        }
        val following = restored.requestNext()
        assertEquals("d", following.item?.id)
        assertTrue(restored.commit(following.version))
        restored.setItems(items)
        assertEquals(listOf("c", "a", "b", "d"), restored.orderedItems().map { it.id })
    }

    @Test
    fun reorderingActiveManualItemsRetainsInactiveSlotsForReenable() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items)
        coordinator.reorder(listOf("c", "a", "b", "d"))
        coordinator.setItems(items.filter { it.id != "a" })
        coordinator.reorder(listOf("d", "c", "b"))

        assertEquals(listOf("d", "c", "b"), coordinator.orderedItems().map { it.id })
        coordinator.setItems(items)
        assertEquals(listOf("d", "a", "c", "b"), coordinator.orderedItems().map { it.id })
    }

    @Test
    fun randomRoundIsRebuiltFromItsSeedSoThePersistedSnapshotNeedsNoQueueIds() {
        val live = PlaybackCoordinator(7L)
        live.setItems(items)
        live.setOrder(PlaybackOrder.RANDOM)
        repeat(2) { live.requestNext().also { live.commit(it.version) } }
        val expected = live.orderedItems().map { it.id }

        val restored = PlaybackCoordinator(7L)
        restored.setItems(items)
        restored.restore(live.snapshot().forPersistence())

        assertEquals(emptyList<String>(), live.snapshot().forPersistence().queueIds)
        assertEquals(expected, restored.orderedItems().map { it.id })
        assertEquals(live.snapshot().currentId, restored.snapshot().currentId)
        assertEquals(live.snapshot().randomRound, restored.snapshot().randomRound)
    }

    @Test
    fun catalogChangeKeepsTheRoundAndDropsOnlyTheDisabledImages() {
        val coordinator = PlaybackCoordinator(11L)
        coordinator.setItems(items)
        coordinator.setOrder(PlaybackOrder.RANDOM)
        val first = coordinator.requestNext().also { coordinator.commit(it.version) }.item!!.id
        val dropped = items.map { it.id }.first { it != first }

        coordinator.setItems(items.filter { it.id != dropped })

        assertEquals(0, coordinator.snapshot().randomRound)
        assertFalse(dropped in coordinator.orderedItems().map { it.id })
        assertEquals(first, coordinator.snapshot().currentId)
        // The rest of the round still plays every remaining image exactly once.
        val remaining = (1..2).map { coordinator.requestNext().also { r -> coordinator.commit(r.version) }.item!!.id }
        assertEquals(2, remaining.toSet().size)
        assertFalse(first in remaining)
    }

    @Test
    fun storedHistoryStaysBoundedWhileKeepingTheCurrentImageReachableBackwards() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items.take(2))
        repeat(PlaybackCoordinator.HISTORY_LIMIT + 200) {
            coordinator.requestNext().also { coordinator.commit(it.version) }
        }
        val snapshot = coordinator.snapshot()

        assertEquals(PlaybackCoordinator.HISTORY_LIMIT, snapshot.historyIds.size)
        assertEquals(snapshot.currentId, snapshot.historyIds[snapshot.historyCursor])
        // Forward gestures keep the newest window, so the cursor stays at its end.
        assertEquals(snapshot.historyIds.lastIndex, snapshot.historyCursor)
        assertTrue(PlaybackCoordinator.HISTORY_BEFORE_CURSOR < PlaybackCoordinator.HISTORY_LIMIT)
        // Trimming bounds the stored window; it does not disable the previous-image gesture.
        assertNotNull(coordinator.requestPrevious().item)
    }

    @Test
    fun reenabledSourceResumesAtItsLastImageInsteadOfItsFirst() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(sourceA + sourceB)
        assertEquals(listOf("A1", "A2", "A3"), coordinator.play(3))

        coordinator.setItems(sourceB)
        assertEquals(listOf("B1", "B2", "B3"), coordinator.play(3))
        assertEquals("A3", coordinator.snapshot().sourcePositions["A"])
        assertEquals(listOf("A"), coordinator.snapshot().resumeSourceIds)

        coordinator.setItems(sourceA + sourceB)
        assertEquals(listOf("A3", "A4", "A5", "B1"), coordinator.play(4))
        assertEquals(emptyList<String>(), coordinator.snapshot().resumeSourceIds)
    }

    @Test
    fun resumePointSurvivesPersistenceAndRestoresEqual() {
        val live = PlaybackCoordinator()
        live.setItems(sourceA + sourceB)
        live.play(2)
        live.setItems(sourceB)
        live.play(1)
        val stored = live.snapshot().forPersistence()

        val restarted = PlaybackCoordinator()
        restarted.setItems(sourceB)
        restarted.restore(stored)
        // The Engine's refresh compares these; an inequality would reload on every notification.
        assertEquals(stored, restarted.snapshot().forPersistence())

        restarted.setItems(sourceA + sourceB)
        assertEquals(listOf("B2", "B3", "A2", "A3"), restarted.play(4))
    }

    @Test
    fun resumePointOnTheSourcesLastImageStartsTheSourceOver() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(sourceA + sourceB)
        coordinator.play(5)
        coordinator.setItems(sourceB)
        assertEquals(listOf("B1", "B2", "B3"), coordinator.play(3))

        coordinator.setItems(sourceA + sourceB)
        assertEquals(listOf("A1", "A2"), coordinator.play(2))
    }

    @Test
    fun unavailableResumePointFallsBackToTheNormalNextImage() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(sourceA + sourceB)
        coordinator.play(3)
        coordinator.setItems(sourceB)
        coordinator.play(3)
        coordinator.setItems(sourceA + sourceB)

        val skipped = coordinator.requestNext(unavailableIds = setOf("A3"))
        assertEquals("A1", skipped.item?.id)
        assertTrue(coordinator.commit(skipped.version))
        assertEquals("A1", coordinator.snapshot().sourcePositions["A"])
        assertEquals(emptyList<String>(), coordinator.snapshot().resumeSourceIds)

        // A resume point removed by a rescan is also ignored.
        val rescanned = PlaybackCoordinator()
        rescanned.setItems(sourceA + sourceB)
        rescanned.play(3)
        rescanned.setItems(sourceB)
        rescanned.play(3)
        rescanned.setItems(sourceA.filter { it.id != "A3" } + sourceB)
        assertEquals(listOf("A1", "A2"), rescanned.play(2))
    }

    @Test
    fun disablingAMiddleSourceContinuesWithTheFollowingSource() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(sourceA + sourceB + sourceC)
        coordinator.play(6) // A1..A5, B1
        coordinator.play(1) // B2
        coordinator.setItems(sourceA + sourceC)
        assertEquals(listOf("C1", "C2"), coordinator.play(2))

        coordinator.setItems(sourceA + sourceB + sourceC)
        assertEquals(listOf("C3", "A1"), coordinator.play(2))
        // The whole round has to come around before B resumes at B2.
        assertEquals(listOf("A2", "A3", "A4", "A5", "B2", "B3"), coordinator.play(6))
    }

    @Test
    fun sourceThatNeverLeftTheCatalogIsNotRedirected() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(sourceA + sourceB)
        coordinator.play(2)
        coordinator.setItems(sourceA + sourceB + sourceC)
        // C has no position and A never left: both play in plain order.
        assertEquals(listOf("A3", "A4", "A5", "B1", "B2", "B3", "C1"), coordinator.play(7))
        assertEquals(emptyList<String>(), coordinator.snapshot().resumeSourceIds)
    }

    @Test
    fun previousGestureIntoAReenabledSourceClearsItsResumeMark() {
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(sourceA + sourceB)
        coordinator.play(2)
        coordinator.setItems(sourceB)
        coordinator.play(1)
        coordinator.setItems(sourceA + sourceB)

        val back = coordinator.requestPrevious()
        assertEquals("A2", back.item?.id)
        assertTrue(coordinator.commit(back.version))
        assertEquals(emptyList<String>(), coordinator.snapshot().resumeSourceIds)
        // Forward history is replayed as usual, then the queue continues after B1.
        assertEquals(listOf("B1", "B2"), coordinator.play(2))
    }

    @Test
    fun interleavedManualOrderOnlyResumesForwardInsideOneRun() {
        val manual = listOf("A1", "A2", "B1", "A3", "A4", "C1").map {
            PlaybackItem(it, "$it.jpg", "$it.jpg", it.take(1))
        }
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(manual)
        coordinator.reorder(manual.map { it.id })
        assertEquals(listOf("A1", "A2", "B1", "A3"), coordinator.play(4))
        coordinator.setItems(manual.filter { it.sourceId != "A" })
        assertEquals(listOf("C1"), coordinator.play(1))

        coordinator.setItems(manual)
        // A1..A2 is its own run; the resume point A3 is in a later run, so no jump happens.
        assertEquals(listOf("A1", "A2", "B1", "A3", "A4", "C1"), coordinator.play(6))
    }

    @Test
    fun randomOrderIgnoresResumePoints() {
        val catalog = sourceA + sourceB
        val coordinator = PlaybackCoordinator(5L)
        coordinator.setItems(catalog)
        coordinator.setOrder(PlaybackOrder.RANDOM)
        repeat(catalog.size) { if (coordinator.snapshot().sourcePositions["A"] == null) coordinator.play(1) }
        assertNotNull(coordinator.snapshot().sourcePositions["A"])
        coordinator.setItems(sourceB)
        coordinator.play(1)
        coordinator.setItems(catalog)
        val withResume = coordinator.snapshot()
        assertEquals(listOf("A"), withResume.resumeSourceIds)

        val plain = PlaybackCoordinator(5L).apply {
            setItems(catalog)
            restore(withResume.copy(sourcePositions = emptyMap(), resumeSourceIds = emptyList()))
        }
        // A re-enabled image joins the running round; it is never redirected to its old position.
        assertEquals(plain.play(8), coordinator.play(8))
    }

    @Test
    fun removingASourceForgetsOnlyItsResumePoint() {
        val snapshot = PlaybackSnapshot(
            sourcePositions = mapOf("A" to "A3", "B" to "B1"),
            resumeSourceIds = listOf("A", "B"),
        )

        val forgotten = snapshot.withoutSource("A")

        assertEquals(mapOf("B" to "B1"), forgotten.sourcePositions)
        assertEquals(listOf("B"), forgotten.resumeSourceIds)
        assertTrue(snapshot.withoutSource("unknown") === snapshot)
    }

    @Test
    fun emptyAndSingleItemAreSafe() {
        val empty = PlaybackCoordinator()
        assertNull(empty.requestNext().item)
        assertNull(empty.requestPrevious().item)

        val single = PlaybackCoordinator()
        single.setItems(items.take(1))
        val first = single.requestNext().also { single.commit(it.version) }
        val second = single.requestNext().also { single.commit(it.version) }
        assertEquals(first.item, second.item)
        assertEquals(1, single.snapshot().historyIds.toSet().size)
    }
}
