package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p3.ImageTransform
import org.junit.Assert.*
import org.junit.Test

/** Actual coordinator work counters and state, not a phone benchmark or mocked cache hit. */
class PlaybackCoordinatorStableCatalogTest {
    private fun items(count: Int): List<PlaybackItem> = (0 until count).map { index ->
        PlaybackItem("id-$index", "$index.jpg", "dir/$index.jpg", "source", 1L, "DIRECTORY_TREE", "content://root")
    }
    private fun play(coordinator: PlaybackCoordinator, previous: Boolean = false): PlaybackRequest {
        val request = if (previous) coordinator.requestPrevious() else coordinator.requestNext()
        assertNotNull(request.item)
        assertTrue(coordinator.commit(request.version))
        return request
    }
    private fun warm(coordinator: PlaybackCoordinator, catalog: List<PlaybackItem>): CoordinatorPreparationWork =
        coordinator.setItemsAndRestore(catalog, coordinator.snapshot().forPersistence())

    @Test fun certifiedCatalogColdValidatesEveryItemAndHotVisitsNoneForAllFourOrders() {
        for (size in listOf(11890, 26000)) for (order in PlaybackOrder.entries) {
            val catalog = CertifiedPlaybackItems(items(size))
            val coordinator = PlaybackCoordinator(91)
            val cold = coordinator.setItemsAndRestore(catalog, PlaybackSnapshot(order = order))
            assertEquals(size.toLong(), cold.catalogItemsVisited)
            assertEquals(1, cold.fullRestoreCount)
            play(coordinator)
            repeat(12) {
                val expected = coordinator.snapshot()
                val work = warm(coordinator, catalog)
                assertEquals(CoordinatorPreparationWork(), work)
                assertSame(expected, coordinator.snapshot())
                val oldQueue = coordinator.snapshot().queueIds
                play(coordinator)
                assertSame(oldQueue, coordinator.snapshot().queueIds)
            }
            repeat(5) {
                assertEquals(CoordinatorPreparationWork(), warm(coordinator, catalog))
                play(coordinator, previous = true)
            }
        }
    }

    @Test fun newCertifiedInstanceWithEqualContentIsStillComparedCompletelyOnce() {
        val coordinator = PlaybackCoordinator()
        val catalog = items(25)
        coordinator.setItems(CertifiedPlaybackItems(catalog))
        val second = CertifiedPlaybackItems(catalog)
        val checked = warm(coordinator, second)
        assertEquals(25L, checked.catalogItemsVisited)
        assertEquals(0, checked.fullRestoreCount)
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, second))
    }

    @Test fun ordinaryMutableCatalogNeverGetsIdentityCertificationAndItsMutationIsObserved() {
        val coordinator = PlaybackCoordinator()
        val catalog = items(5).toMutableList()
        coordinator.setItems(catalog)
        assertEquals(5L, warm(coordinator, catalog).catalogItemsVisited)
        catalog[2] = catalog[2].copy(displayName = "replacement")
        val changed = warm(coordinator, catalog)
        assertTrue(changed.catalogItemsVisited > 0)
        assertEquals(1, changed.fullRestoreCount)
        assertEquals("replacement", coordinator.catalogItems()[2].displayName)
        assertEquals(5L, warm(coordinator, catalog).catalogItemsVisited)
    }

    @Test fun readOnlyViewOverMutableBackingIsNotAControlledCatalog() {
        val backing = items(5).toMutableList()
        val view = java.util.Collections.unmodifiableList(backing)
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(view)
        backing[0] = backing[0].copy(sourceKind = "changed")
        assertEquals(1, warm(coordinator, view).fullRestoreCount)
        assertEquals("changed", coordinator.catalogItems()[0].sourceKind)
    }

    @Test fun certifiedWrapperCopiesInputAndCannotBeMutatedByItsCaller() {
        val original = items(3).toMutableList()
        val certified = CertifiedPlaybackItems(original)
        original.clear()
        assertEquals(3, certified.size)
        assertThrows(UnsupportedOperationException::class.java) { (certified as MutableList<PlaybackItem>)[0] = PlaybackItem("x", "x") }
        assertThrows(UnsupportedOperationException::class.java) { (certified as MutableList<PlaybackItem>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { certified.iterator().let { it.next(); (it as MutableIterator<PlaybackItem>).remove() } }
    }

    @Test fun firstCertifiedInputStillRejectsBlankIdentityAndPreservesPreviousCatalog() {
        val coordinator = PlaybackCoordinator()
        val old = CertifiedPlaybackItems(items(2))
        coordinator.setItems(old)
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.setItems(CertifiedPlaybackItems(listOf(PlaybackItem(" ", "invalid"))))
        }
        assertEquals(items(2), coordinator.catalogItems())
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, old))
    }

    @Test fun firstCertifiedDuplicateInputKeepsFirstRecordExactlyLikeOrdinaryInput() {
        val first = PlaybackItem("same", "first")
        val second = PlaybackItem("same", "second")
        val catalog = CertifiedPlaybackItems(listOf(first, second, PlaybackItem("other", "other")))
        val coordinator = PlaybackCoordinator()
        assertEquals(3L, coordinator.setItems(catalog).catalogItemsVisited)
        assertEquals(listOf(first, PlaybackItem("other", "other")), coordinator.catalogItems())
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, catalog))
    }

    @Test fun everySameCountMetadataAndOrderChangeInvalidatesPreparedState() {
        val base = items(4)
        val changes = listOf<(PlaybackItem) -> PlaybackItem>(
            { it.copy(id = "different-id") }, { it.copy(displayName = "different-name") },
            { it.copy(relativePath = "elsewhere/file") }, { it.copy(sourceId = "different-source") },
            { it.copy(sourceOrder = 100L) }, { it.copy(sourceKind = "MULTIPLE_DOCUMENTS") },
            { it.copy(sourceRootUri = "content://different-root") },
        )
        for (change in changes) {
            val coordinator = PlaybackCoordinator()
            val original = CertifiedPlaybackItems(base)
            coordinator.setItems(original)
            coordinator.setOrder(PlaybackOrder.MANUAL)
            play(coordinator)
            val updated = base.toMutableList().also { it[1] = change(it[1]) }
            val newCatalog = CertifiedPlaybackItems(updated)
            val work = warm(coordinator, newCatalog)
            assertTrue(work.catalogItemsVisited >= 4)
            assertEquals(1, work.fullRestoreCount)
            assertTrue(work.queueRebuilds > 0)
            assertEquals(updated, coordinator.catalogItems())
        }
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(CertifiedPlaybackItems(base))
        assertEquals(1, warm(coordinator, CertifiedPlaybackItems(base.reversed())).fullRestoreCount)
        assertEquals(base.reversed(), coordinator.catalogItems())
    }

    @Test fun acceptedIdentityIsOnlyTheLastCatalogNotAnUnboundedIdentityRegistry() {
        val coordinator = PlaybackCoordinator()
        val a = CertifiedPlaybackItems(items(8))
        val b = CertifiedPlaybackItems(items(8).map { it.copy(sourceKind = "different") })
        coordinator.setItems(a)
        coordinator.setItems(b)
        val back = coordinator.setItems(a)
        assertTrue(back.catalogItemsVisited > 0)
        assertEquals(1, back.fullRestoreCount)
        assertEquals(items(8), coordinator.catalogItems())
    }

    @Test fun separateCoordinatorsMustEachValidateTheSharedCertifiedCatalog() {
        val catalog = CertifiedPlaybackItems(items(9))
        val formal = PlaybackCoordinator(3)
        val preview = PlaybackCoordinator(3)
        assertEquals(9L, formal.setItems(catalog).catalogItemsVisited)
        assertEquals(9L, preview.setItems(catalog).catalogItemsVisited)
        play(preview)
        assertNull(formal.snapshot().currentId)
        assertNotNull(preview.snapshot().currentId)
    }

    @Test fun stablePreparationStillCancelsThePendingRequestAndRunsTheCheckpoint() {
        val catalog = CertifiedPlaybackItems(items(8))
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        val pending = coordinator.requestNext()
        var checkpoints = 0
        val work = coordinator.setItemsAndRestore(catalog, coordinator.snapshot().forPersistence()) { checkpoints++ }
        assertEquals(CoordinatorPreparationWork(), work)
        assertEquals(1, checkpoints)
        assertFalse(coordinator.commit(pending.version))
        assertNull(coordinator.snapshot().currentId)
    }

    @Test fun eachExternalPersistedFieldChangePerformsARealFullRestore() {
        val catalog = CertifiedPlaybackItems(items(8))
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        play(coordinator)
        play(coordinator)
        val original = coordinator.snapshot().forPersistence()
        val changes = listOf(
            original.copy(order = PlaybackOrder.NATURAL_DESC),
            original.copy(queueIndex = original.queueIndex + 1),
            original.copy(historyIds = original.historyIds + "id-2"),
            original.copy(historyCursor = 0), original.copy(currentId = "id-7"),
            original.copy(randomRound = original.randomRound + 1),
            original.copy(previousRoundLastId = "id-7"), original.copy(queueFingerprint = 42),
            original.copy(historyTransforms = listOf(ImageTransform(userScale = 2f))),
            original.copy(historySourceIds = listOf("source")),
            original.copy(sourcePositions = original.sourcePositions + ("other" to "id-other")),
            original.copy(sourceHistoryCursors = original.sourceHistoryCursors + ("other" to 0)),
            original.copy(resumeSourceIds = listOf("other")),
            original.copy(historyResetSerial = 1L), original.copy(forwardHistoryInvalidated = true),
        )
        for (changed in changes) {
            coordinator.restore(original)
            val baseline = PlaybackCoordinator().apply { setItems(catalog); restore(changed) }.snapshot()
            val work = coordinator.setItemsAndRestore(catalog, changed)
            assertEquals(1, work.fullRestoreCount)
            assertEquals(0L, work.catalogItemsVisited)
            assertEquals(baseline, coordinator.snapshot())
        }
    }

    @Test fun externalManualQueueReorderingCannotUseTheStableRuntimeFastPath() {
        val catalog = CertifiedPlaybackItems(items(7))
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        coordinator.setOrder(PlaybackOrder.MANUAL)
        val external = coordinator.snapshot().copy(queueIds = coordinator.snapshot().queueIds.reversed())
        assertEquals(1, coordinator.setItemsAndRestore(catalog, external).fullRestoreCount)
        assertEquals(external.queueIds, coordinator.snapshot().queueIds)
    }

    @Test fun arbitraryMutableSnapshotCollectionsCannotAliasCommittedState() {
        val catalog = CertifiedPlaybackItems(items(5))
        val history = mutableListOf("id-0", "id-1")
        val transforms = mutableListOf<ImageTransform?>(ImageTransform(userScale = 2f))
        val owners = mutableListOf("source", "source")
        val positions = mutableMapOf("source" to "id-1")
        val cursors = mutableMapOf("source" to 1)
        val resume = mutableListOf("inactive")
        val external = PlaybackSnapshot(historyIds = history, historyCursor = 1, currentId = "id-1",
            historyTransforms = transforms, historySourceIds = owners, sourcePositions = positions,
            sourceHistoryCursors = cursors, resumeSourceIds = resume)
        val coordinator = PlaybackCoordinator()
        coordinator.setItemsAndRestore(catalog, external)
        val saved = coordinator.snapshot()
        history[1] = "id-3"; transforms.clear(); owners.clear(); positions.clear(); cursors.clear(); resume.clear()
        assertEquals("id-1", saved.currentId)
        assertEquals(listOf("id-0", "id-1"), saved.historyIds)
        assertEquals(mapOf("source" to "id-1"), saved.sourcePositions)
        assertEquals(1, coordinator.setItemsAndRestore(catalog, external).fullRestoreCount)
        assertEquals("id-3", coordinator.snapshot().currentId)
    }

    @Test fun committedCollectionsRejectMutationAfterRestoreAndAfterCommit() {
        val catalog = CertifiedPlaybackItems(items(5))
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        play(coordinator)
        val snap = coordinator.snapshot()
        assertThrows(UnsupportedOperationException::class.java) { (snap.historyIds as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snap.queueIds as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snap.sourcePositions as MutableMap<String, String>).clear() }
        coordinator.restoreKeepingCurrent(snap.copy(currentId = "id-2"), "id-2")
        assertThrows(UnsupportedOperationException::class.java) { (coordinator.snapshot().historyIds as MutableList<String>).clear() }
    }

    @Test fun previewStableCertifiedInputAvoidsRestoreButOrdinaryInputKeepsOriginalPath() {
        val catalog = CertifiedPlaybackItems(items(6))
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(catalog)
        play(coordinator)
        val prior = coordinator.snapshot()
        assertEquals(CoordinatorPreparationWork(), coordinator.setItems(catalog))
        assertSame(prior, coordinator.snapshot())
        val ordinary = catalog.toList()
        val work = coordinator.setItems(ordinary)
        assertEquals(6L, work.catalogItemsVisited)
        assertEquals(1, work.fullRestoreCount)
    }

    @Test fun randomCatalogMemberChangeInvalidatesRoundEvenWithEqualCount() {
        val coordinator = PlaybackCoordinator(5)
        val original = CertifiedPlaybackItems(items(12))
        coordinator.setItems(original)
        coordinator.setOrder(PlaybackOrder.RANDOM)
        play(coordinator)
        val replaced = CertifiedPlaybackItems(items(12).map { if (it.id == "id-11") it.copy(id = "replacement") else it })
        val work = warm(coordinator, replaced)
        assertEquals(1, work.fullRestoreCount)
        assertFalse(coordinator.snapshot().queueIds.contains("id-11"))
        assertTrue(coordinator.snapshot().queueIds.contains("replacement"))
    }

    @Test fun randomRoundBoundaryStillBuildsARealFullRoundAndKeepsNoRepeatRules() {
        val coordinator = PlaybackCoordinator(22)
        val catalog = CertifiedPlaybackItems(items(6))
        coordinator.setItems(catalog)
        coordinator.setOrder(PlaybackOrder.RANDOM)
        val round = (0 until 6).map { warm(coordinator, catalog); play(coordinator).item!!.id }
        assertEquals(6, round.toSet().size)
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, catalog))
        val next = play(coordinator).item!!.id
        assertNotEquals(round.last(), next)
        assertEquals(1, coordinator.snapshot().randomRound)
        assertEquals(6, coordinator.snapshot().queueIds.toSet().size)
    }

    @Test fun manualHiddenSlotsSurviveStableReuseCatalogChangesAndRestoration() {
        val original = items(6).mapIndexed { i, value -> value.copy(sourceId = if (i % 2 == 0) "A" else "B") }
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(CertifiedPlaybackItems(original))
        coordinator.reorder(listOf("id-4", "id-5", "id-0", "id-1", "id-2", "id-3"))
        val order = coordinator.snapshot().queueIds
        val onlyA = CertifiedPlaybackItems(original.filter { it.sourceId == "A" })
        warm(coordinator, onlyA)
        assertEquals(order, coordinator.snapshot().queueIds)
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, onlyA))
        warm(coordinator, CertifiedPlaybackItems(original))
        assertEquals(order, coordinator.snapshot().queueIds)
    }

    @Test fun selectedAndActualPreviousHistoryStayEquivalentAcrossStableReuse() {
        val coordinator = PlaybackCoordinator()
        val catalog = CertifiedPlaybackItems(items(20))
        coordinator.setItems(catalog)
        play(coordinator)
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, catalog))
        val selected = coordinator.requestSelected("id-12")
        assertTrue(coordinator.commit(selected.version))
        assertEquals("id-12", coordinator.snapshot().currentId)
        warm(coordinator, catalog)
        assertEquals("id-0", play(coordinator, true).item!!.id)
        warm(coordinator, catalog)
        assertEquals("id-12", play(coordinator).item!!.id)
    }

    @Test fun entirelyUnavailableCatalogDoesNotAdvanceOrPolluteHistoryAfterStableReuse() {
        val coordinator = PlaybackCoordinator()
        val catalog = CertifiedPlaybackItems(items(4))
        coordinator.setItems(catalog)
        play(coordinator)
        val before = coordinator.snapshot()
        warm(coordinator, catalog)
        val request = coordinator.requestNext(catalog.map { it.id }.toSet())
        assertNull(request.item)
        assertEquals(before, coordinator.snapshot())
        assertTrue(coordinator.reject(request.version))
        assertEquals(before, coordinator.snapshot())
    }

    @Test fun diagnosticsAreLocalToTheCallAndCannotCountPreviousPreparationsAgain() {
        val coordinator = PlaybackCoordinator()
        val catalog = CertifiedPlaybackItems(items(11))
        val first = coordinator.setItems(catalog)
        assertEquals(11L, first.catalogItemsVisited)
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, catalog))
        assertEquals(11L, first.catalogItemsVisited)
        val changed = coordinator.setItemsAndRestore(catalog, coordinator.snapshot().copy(historyResetSerial = 1))
        assertEquals(0L, changed.catalogItemsVisited)
        assertEquals(1, changed.fullRestoreCount)
        assertEquals(CoordinatorPreparationWork(), warm(coordinator, catalog))
    }
}
