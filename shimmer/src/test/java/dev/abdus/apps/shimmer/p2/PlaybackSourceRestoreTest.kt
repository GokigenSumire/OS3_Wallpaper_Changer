package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p3.ImageTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 单根来源接管与真实历史保护；不执行Android/GLES，不代替手机自动恢复验收。 */
class PlaybackSourceRestoreTest {
    private fun source(name: String, count: Int = 6) = (1..count).map {
        PlaybackItem("$name$it", "$it.jpg", "$it.jpg", name, if (name == "A") 0L else 1L,
            "DIRECTORY_TREE", "content://$name")
    }

    private fun next(c: PlaybackCoordinator, transform: ImageTransform? = null): String? =
        c.requestNext().also { assertTrue(c.commit(it.version, transform)) }.item?.id

    private fun previous(c: PlaybackCoordinator): String? =
        c.requestPrevious().also { assertTrue(c.commit(it.version)) }.item?.id

    private fun restore(c: PlaybackCoordinator, items: List<PlaybackItem>): String? {
        val stored = c.snapshot().forPersistence()
        c.setItemsAndRestore(items, stored)
        val request = c.requestSourceRestore() ?: return null
        assertFalse(request.freshAdvance)
        assertTrue(c.commit(request.version))
        return request.item?.id
    }

    private fun atA2(): PlaybackCoordinator = PlaybackCoordinator(7L).apply {
        setItems(source("A"))
        assertEquals("A1", next(this))
        assertEquals("A2", next(this))
    }

    @Test fun newSourceBeginsAtItsOwnFirstIdentityNotOtherSourcesIndex() {
        val c = atA2()
        val old = c.snapshot()
        c.setItemsAndRestore(source("B"), old.forPersistence())
        val before = c.snapshot()
        val restored = c.requestSourceRestore()!!
        assertEquals("B1", restored.item!!.id)
        assertFalse(restored.freshAdvance)
        assertNull(restored.entryTransform)
        assertEquals(before, c.snapshot())
        assertTrue(c.commit(restored.version))
        assertEquals("B1", c.current()!!.id)
        assertEquals("B2", next(c))
        assertEquals("A2", c.snapshot().sourcePositions["A"])
    }

    @Test fun repeatedA2B4SwitchesResumeActualImagesAndAdvanceExactlyOnce() {
        val c = atA2()
        assertEquals("B1", restore(c, source("B")))
        repeat(3) { next(c) }
        assertEquals("B4", c.current()!!.id)
        repeat(3) {
            val aPosition = c.snapshot().sourcePositions.getValue("A")
            assertEquals(aPosition, restore(c, source("A")))
            assertEquals("A${aPosition.drop(1).toInt() + 1}", next(c))
            val bPosition = c.snapshot().sourcePositions.getValue("B")
            assertEquals(bPosition, restore(c, source("B")))
            assertEquals(bPosition, c.current()!!.id)
        }
        assertEquals("B3", previous(c))
        assertEquals("B2", previous(c))
        assertEquals("B1", previous(c))
    }

    @Test fun inactiveForwardBranchAndItsTransformsSurviveFreshAdvance() {
        val c = atA2()
        assertEquals("B1", restore(c, source("B")))
        val placement = ImageTransform(userScale = 2f, offsetX = .2f)
        assertEquals("B2", next(c, placement))
        assertEquals("B3", next(c))
        val before = c.snapshot()
        assertEquals("A2", restore(c, source("A")))
        assertEquals("A3", next(c))
        val after = c.snapshot()
        assertTrue(after.historyIds.containsAll(listOf("B1", "B2", "B3")))
        assertEquals(placement, after.historyTransforms.getOrNull(after.historyIds.indexOf("B2")))
        assertEquals(before.sourcePositions["B"], after.sourcePositions["B"])
        assertEquals("B3", restore(c, source("B")))
        val back = c.requestPrevious()
        assertEquals("B2", back.item!!.id)
        assertEquals(placement, back.entryTransform)
        assertTrue(c.commit(back.version))
    }

    @Test fun activeSourceFutureBranchIsStillDiscardedWhenAReadablySkippedNewBranchAppears() {
        val c = atA2()
        next(c)
        previous(c)
        val request = c.requestNext(setOf("A3"))
        assertEquals("A4", request.item!!.id)
        assertTrue(c.commit(request.version))
        assertEquals(listOf("A1", "A2", "A4"), c.snapshot().historyIds)
    }

    @Test fun sameRootRemovedFutureImageAndItsPlacementAreDiscardedByFreshBranch() {
        val c = atA2()
        next(c, ImageTransform(userScale = 3f))
        previous(c)
        c.setItems(source("A").filterNot { it.id == "A3" })
        assertEquals("A4", next(c))
        assertEquals(listOf("A1", "A2", "A4"), c.snapshot().historyIds)
        assertTrue(c.snapshot().historyTransforms.isEmpty())
    }

    @Test fun savedPointWinsOverHistoryLastIdentityAndPreservesExactTransform() {
        val c = PlaybackCoordinator()
        val transform = ImageTransform(userScale = 3f)
        c.setItemsAndRestore(source("B"), PlaybackSnapshot(
            historyIds = listOf("B1", "B2", "B3", "A2"), historyCursor = 3, currentId = "A2",
            historyTransforms = listOf(null, transform), sourcePositions = mapOf("A" to "A2", "B" to "B2"),
        ))
        val request = c.requestSourceRestore()!!
        assertEquals("B2", request.item!!.id)
        assertEquals(transform, request.entryTransform)
        assertTrue(c.commit(request.version))
        assertEquals(1, c.snapshot().historyCursor)
        assertEquals("B", c.snapshot().historySourceIds[1])
        assertEquals("B3", next(c))
    }

    @Test fun absentPositionFallsBackToLastActualHistoryIdentityOfEnabledRoot() {
        val c = PlaybackCoordinator()
        c.setItemsAndRestore(source("B"), PlaybackSnapshot(
            historyIds = listOf("B1", "B2", "A2"), historyCursor = 2, currentId = "A2",
            sourcePositions = mapOf("A" to "A2"),
        ))
        assertEquals("B2", c.requestSourceRestore()!!.item!!.id)
    }

    @Test fun staleRecordedPositionDoesNotSilentlyUseUnrelatedHistoryPoint() {
        val c = PlaybackCoordinator()
        c.setItemsAndRestore(source("B"), PlaybackSnapshot(
            historyIds = listOf("B2", "A2"), historyCursor = 1, currentId = "A2",
            sourcePositions = mapOf("B" to "removed-B4"),
        ))
        assertEquals("B1", c.requestSourceRestore()!!.item!!.id)
    }

    @Test fun lastImageIsReallyRestoredAndNextWrapsNormally() {
        val c = atA2()
        restore(c, source("B"))
        repeat(5) { next(c) }
        assertEquals("B6", c.current()!!.id)
        restore(c, source("A"))
        next(c)
        assertEquals("B6", restore(c, source("B")))
        assertEquals("B1", next(c))
    }

    @Test fun everyOrderRestoresTheSavedIdentityRatherThanItsPosition() {
        for (order in PlaybackOrder.entries) {
            val c = PlaybackCoordinator(23L)
            c.setItemsAndRestore(source("B"), PlaybackSnapshot(
                order = order, queueIds = if (order == PlaybackOrder.MANUAL) listOf("B4", "B2", "B1", "B3", "B5", "B6") else emptyList(),
                historyIds = listOf("A1", "A2", "B4", "A3"), historyCursor = 3, currentId = "A3",
                sourcePositions = mapOf("B" to "B4"), randomRound = 4, queueFingerprint = 17,
            ))
            val request = c.requestSourceRestore()!!
            assertEquals("$order", "B4", request.item!!.id)
            assertTrue(c.commit(request.version))
            assertEquals(order, c.snapshot().order)
            assertEquals(4, c.snapshot().randomRound)
        }
    }

    @Test fun unplayedSourceFirstPointUsesEachActualQueueOrder() {
        for (order in PlaybackOrder.entries) {
            val c = PlaybackCoordinator(41L)
            val stored = PlaybackSnapshot(
                order = order, currentId = "A2", historyIds = listOf("A1", "A2"), historyCursor = 1,
                queueIds = if (order == PlaybackOrder.MANUAL) listOf("A2", "B4", "B3", "B2", "B1", "B5", "B6") else emptyList(),
            )
            c.setItemsAndRestore(source("B"), stored)
            val expected = if (order == PlaybackOrder.RANDOM) {
                val plain = PlaybackCoordinator(41L).apply { setItems(source("B")); restore(stored) }
                plain.requestNext().item!!.id
            } else c.orderedItems().first().id
            assertEquals(expected, c.requestSourceRestore()!!.item!!.id)
        }
    }

    @Test fun restoreOfSingleImageAndPreviousAtFirstPointAreSafe() {
        val c = atA2()
        assertEquals("B1", restore(c, source("B", 1)))
        assertEquals("B1", previous(c))
        repeat(3) { assertEquals("B1", next(c)) }
        assertEquals(listOf("A1", "A2", "B1"), c.snapshot().historyIds)
    }

    @Test fun currentNullDoesNotTurnInitialStartupIntoRootReplacement() {
        val c = PlaybackCoordinator()
        c.setItems(source("B"))
        assertFalse(c.sourceRestoreNeeded())
        assertNull(c.requestSourceRestore())
        assertEquals("B1", next(c))
    }

    @Test fun currentStillActiveDoesNotRestoreAnotherPositionOrJumpOnAddedSource() {
        val c = atA2()
        c.setItems(source("A") + source("B"))
        assertFalse(c.sourceRestoreNeeded())
        assertNull(c.requestSourceRestore())
        assertEquals("A3", next(c))
    }

    @Test fun multipleAndEmptySourceIdsRetainOldMixedCatalogSemantics() {
        for (items in listOf(source("B") + source("C"), source("B").map { it.copy(sourceId = "") })) {
            val c = atA2()
            c.setItems(items)
            assertFalse(c.sourceRestoreNeeded())
            assertNull(c.requestSourceRestore())
            assertEquals("B1", next(c))
        }
    }

    @Test fun allSourcesStoppedHoldStateAndReenablingRestoresWithoutAnInterimGesture() {
        val c = atA2()
        val stored = c.snapshot().forPersistence()
        c.setItemsAndRestore(emptyList(), stored)
        assertFalse(c.sourceRestoreNeeded())
        assertNull(c.requestSourceRestore())
        // 当前图与持久历史保持；空集没有有效派生位置，不能沿用旧目录的数字index。
        assertEquals(stored.copy(queueIndex = -1), c.snapshot().forPersistence())
        c.setItemsAndRestore(source("B"), c.snapshot().forPersistence())
        assertEquals("B1", c.requestSourceRestore()!!.item!!.id)
    }

    @Test fun rejectedRestoreDoesNotPublishBaselineOrLoseSourcePosition() {
        val c = atA2()
        c.setItemsAndRestore(source("B"), c.snapshot().forPersistence())
        val before = c.snapshot()
        val request = c.requestSourceRestore()!!
        assertTrue(c.reject(request.version))
        assertEquals(before, c.snapshot())
        assertTrue(c.sourceRestoreNeeded())
        assertEquals("B1", c.requestSourceRestore()!!.item!!.id)
    }

    @Test fun laterPreparationInvalidatesPendingRestoreSoItCannotCommit() {
        val c = atA2()
        c.setItems(source("B"))
        val request = c.requestSourceRestore()!!
        c.setItems(source("A"))
        assertFalse(c.commit(request.version))
        assertEquals("A2", c.current()!!.id)
    }

    @Test fun explicitRestoreOfNewStoredSnapshotInvalidatesOldSourceRestore() {
        val c = atA2()
        c.setItems(source("B"))
        val request = c.requestSourceRestore()!!
        val latest = c.snapshot().copy(historyResetSerial = 11)
        c.restore(latest)
        assertFalse(c.commit(request.version))
        assertEquals(11L, c.snapshot().historyResetSerial)
    }

    @Test fun restoreDoesNotAcceptPreviousSourceInheritedPlacement() {
        val c = atA2()
        c.setItems(source("B"))
        val request = c.requestSourceRestore()!!
        assertTrue(c.commit(request.version, ImageTransform(userScale = 4f)))
        assertNull(c.snapshot().currentEntryTransform("B1"))
    }

    @Test fun manualHiddenPositionsAndClearSerialSurviveRootTakeover() {
        val c = PlaybackCoordinator()
        val complete = listOf("A1", "B4", "A2", "B2", "B1", "B3", "B5", "B6")
        c.setItemsAndRestore(source("B"), PlaybackSnapshot(
            order = PlaybackOrder.MANUAL, queueIds = complete, currentId = "A2",
            historyIds = listOf("A1", "A2"), historyCursor = 1, historyResetSerial = 9,
        ))
        assertEquals("B4", c.requestSourceRestore()!!.let { assertTrue(c.commit(it.version)); it.item!!.id })
        assertEquals(complete, c.snapshot().queueIds)
        assertEquals(9L, c.snapshot().historyResetSerial)
    }

    @Test fun twoRootSourcesUseOriginalGlobalFutureBranchCut() {
        val c = PlaybackCoordinator()
        c.setItems(source("A", 2) + source("B", 2))
        repeat(4) { next(c) }
        repeat(2) { previous(c) }
        // 两个未来历史项均不可读才形成新分支；仅跳过B1会沿旧未来历史到B2，不裁剪。
        val request = c.requestNext(setOf("B1", "B2"))
        assertEquals("A1", request.item!!.id)
        assertTrue(request.freshAdvance)
        assertTrue(c.commit(request.version))
        assertEquals(listOf("A1", "A2", "A1"), c.snapshot().historyIds)
    }

    @Test fun historyTrimRemainsBoundedAndKeepsTheCurrentRestorePlacementAligned() {
        val c = PlaybackCoordinator()
        val ids = (0..PlaybackCoordinator.HISTORY_LIMIT + 30).map { if (it % 2 == 0) "B1" else "A1" }
        val position = ImageTransform(userScale = 2f)
        c.setItemsAndRestore(source("B", 1), PlaybackSnapshot(
            historyIds = ids + "A2", historyCursor = ids.size, currentId = "A2",
            sourcePositions = mapOf("B" to "B1"),
            historyTransforms = ids.map { if (it == "B1") position else null },
        ))
        val request = c.requestSourceRestore()!!
        assertEquals(position, request.entryTransform)
        assertTrue(c.commit(request.version))
        assertTrue(c.snapshot().historyIds.size <= PlaybackCoordinator.HISTORY_LIMIT)
        assertEquals("B1", c.snapshot().historyIds[c.snapshot().historyCursor])
        assertEquals(position, c.snapshot().currentEntryTransform("B1"))
    }

    @Test fun resolverTriesBadResumeAndEveryOtherIdentityAtMostOnceWithoutPublishing() = runBlocking {
        val c = atA2()
        c.setItemsAndRestore(source("B", 3), c.snapshot().copy(sourcePositions = mapOf("B" to "B2")))
        val before = c.snapshot()
        val attempts = mutableListOf<String>()
        val loaded = PlaybackImageResolver<String>(c).resolveSourceRestore { _, item ->
            attempts += item.id
            assertEquals(before, c.snapshot())
            null
        }
        assertNull(loaded)
        assertEquals(listOf("B2", "B1", "B3"), attempts)
        assertEquals(attempts.size, attempts.toSet().size)
        assertEquals(before, c.snapshot())
        assertTrue(c.sourceRestoreNeeded())
    }

    @Test fun resolverSkipsBadSavedImageThenCommitsOnlyActualLoadedFallback() = runBlocking {
        val c = atA2()
        c.setItemsAndRestore(source("B", 3), c.snapshot().copy(sourcePositions = mapOf("B" to "B2")))
        val attempts = mutableListOf<String>()
        val loaded = PlaybackImageResolver<String>(c).resolveSourceRestore { _, item ->
            attempts += item.id
            item.id.takeIf { it == "B3" }
        }!!
        assertEquals(listOf("B2", "B1", "B3"), attempts)
        assertEquals("A2", c.snapshot().currentId)
        assertTrue(c.commit(loaded.request.version))
        assertEquals("B3", c.snapshot().sourcePositions["B"])
        assertFalse(c.snapshot().historyIds.contains("B2"))
    }

    @Test fun resolverCancellationRejectsPendingAndRetainsAllCommittedState() = runBlocking {
        val c = atA2()
        c.setItems(source("B"))
        val before = c.snapshot()
        var version = -1L
        try {
            PlaybackImageResolver<String>(c).resolveSourceRestore { request, _ ->
                version = request.version
                throw CancellationException("cancel-restore")
            }
            fail("取消必须传播")
        } catch (_: CancellationException) { }
        assertEquals(before, c.snapshot())
        assertFalse(c.commit(version))
    }

    @Test fun resolverOrdinaryFailureRejectsPendingAndPropagatesOriginalFailure() = runBlocking {
        val c = atA2()
        c.setItems(source("B"))
        val before = c.snapshot()
        var version = -1L
        val failed = IllegalStateException("provider-failure")
        try {
            PlaybackImageResolver<String>(c).resolveSourceRestore { request, _ ->
                version = request.version
                throw failed
            }
            fail("异常必须传播")
        } catch (actual: IllegalStateException) { assertSame(failed, actual) }
        assertEquals(before, c.snapshot())
        assertFalse(c.commit(version))
    }

    @Test fun resolverRestoredBaselineThenGestureMovesExactlyOneActualHistoryStep() = runBlocking {
        val c = atA2()
        restore(c, source("B"))
        repeat(3) { next(c) }
        restore(c, source("A"))
        next(c)
        c.setItems(source("B"))
        val resolver = PlaybackImageResolver<String>(c)
        val restored = resolver.resolveSourceRestore { _, item -> item.id }!!
        assertEquals("B4", restored.image)
        assertTrue(c.commit(restored.request.version))
        val user = resolver.resolve(PlaybackDirection.PREVIOUS) { it.id }!!
        assertEquals("B3", user.image)
        assertTrue(c.commit(user.request.version))
        assertEquals("B3", c.snapshot().currentId)
    }

    @Test fun stoppedSingleItemOfTheSameRootDoesNotRestoreOrRestartFromFirst() {
        val c = atA2()
        c.setItemsAndRestore(source("A").filterNot { it.id == "A2" }, c.snapshot().forPersistence())
        assertFalse(c.sourceRestoreNeeded())
        assertNull(c.requestSourceRestore())
        assertEquals("A1", previous(c))
        assertEquals("A3", next(c))
    }

    @Test fun repeatedUriAfterBackwardVisitRestoresExactEarlierSlotAndItsTransform() {
        val c = PlaybackCoordinator()
        val first = ImageTransform(userScale = 2f, offsetX = .2f)
        val second = ImageTransform(userScale = 3f, offsetX = .3f)
        c.setItems(source("B", 3))
        next(c); next(c, first); next(c); next(c); next(c, second)
        repeat(3) { previous(c) }
        assertEquals(1, c.snapshot().historyCursor)
        assertEquals(first, c.snapshot().currentEntryTransform("B2"))
        assertEquals("A1", restore(c, source("A")))
        val stored = c.snapshot().forPersistence()
        // 模拟进程重建／从JSON读取后仍保留第一次B2，而非盲选末次B2。
        val rebuilt = PlaybackCoordinator().apply { setItemsAndRestore(source("B", 3), stored) }
        val request = rebuilt.requestSourceRestore()!!
        assertEquals("B2", request.item!!.id)
        assertEquals(first, request.entryTransform)
        assertTrue(rebuilt.commit(request.version))
        assertEquals(1, rebuilt.snapshot().historyCursor)
        assertEquals("B3", next(rebuilt))
        assertEquals("B2", previous(rebuilt))
        assertEquals(first, rebuilt.snapshot().currentEntryTransform("B2"))
    }

    @Test fun freshBranchRemapsInactiveSourceCursorWithoutChangingItsAlignedTransform() {
        val c = PlaybackCoordinator()
        val bPlacement = ImageTransform(userScale = 2f)
        c.setItemsAndRestore(source("A", 4), PlaybackSnapshot(
            historyIds = listOf("A1", "A2", "A3", "B1", "B2", "A4"),
            historyCursor = 1, currentId = "A2", historyTransforms = listOf(null, null, null, null, bPlacement),
            sourcePositions = mapOf("A" to "A2", "B" to "B2"), sourceHistoryCursors = mapOf("A" to 1, "B" to 4),
            historySourceIds = listOf("A", "A", "A", "B", "B", "A"),
        ))
        val request = c.requestNext(setOf("A3", "A4"))
        assertEquals("A1", request.item!!.id)
        assertTrue(c.commit(request.version))
        assertEquals(listOf("A1", "A2", "B1", "B2", "A1"), c.snapshot().historyIds)
        assertEquals(3, c.snapshot().sourceHistoryCursors["B"])
        assertEquals(listOf("A", "A", "B", "B", "A"), c.snapshot().historySourceIds)
        c.setItemsAndRestore(source("B", 2), c.snapshot().forPersistence())
        val restored = c.requestSourceRestore()!!
        assertEquals("B2", restored.item!!.id)
        assertEquals(bPlacement, restored.entryTransform)
    }

    @Test fun trimmingShiftsKeptCursorsAndDropsOutOfWindowWithoutLosingSourceUris() {
        val c = PlaybackCoordinator()
        val ids = (0 until PlaybackCoordinator.HISTORY_LIMIT + 100).map { "B$it" }
        val kept = ids.lastIndex - 3
        c.setItemsAndRestore(source("A"), PlaybackSnapshot(
            historyIds = ids, historyCursor = ids.lastIndex, currentId = ids.last(),
            sourcePositions = mapOf("old" to ids[0], "kept" to ids[kept]),
            sourceHistoryCursors = mapOf("old" to 0, "kept" to kept),
            historySourceIds = ids.map { "B" },
        ))
        val state = c.snapshot()
        assertEquals(PlaybackCoordinator.HISTORY_LIMIT, state.historyIds.size)
        assertEquals(kept - 100, state.sourceHistoryCursors["kept"])
        assertFalse(state.sourceHistoryCursors.containsKey("old"))
        assertEquals(ids[0], state.sourcePositions["old"])
        assertEquals(ids[kept], state.historyIds[state.sourceHistoryCursors.getValue("kept")])
        assertEquals(List(PlaybackCoordinator.HISTORY_LIMIT) { "B" }, state.historySourceIds)
    }

    @Test fun invalidRecordedCursorNeverAttachesWrongHistoryPlacement() {
        for (cursor in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, 1)) {
            val c = PlaybackCoordinator()
            val right = ImageTransform(userScale = 2f)
            val wrong = ImageTransform(userScale = 4f)
            c.setItemsAndRestore(source("B"), PlaybackSnapshot(
                historyIds = listOf("B2", "B3", "A2"), historyCursor = 2, currentId = "A2",
                historyTransforms = listOf(right, wrong), sourcePositions = mapOf("B" to "B2"),
                sourceHistoryCursors = mapOf("B" to cursor),
            ))
            assertFalse(c.snapshot().sourceHistoryCursors.containsKey("B"))
            val request = c.requestSourceRestore()!!
            assertEquals("B2", request.item!!.id)
            assertEquals(right, request.entryTransform)
        }
    }

    @Test fun legacySnapshotWithoutCursorMapKeepsLastMatchingIdentityFallback() {
        val c = PlaybackCoordinator()
        val last = ImageTransform(userScale = 3f)
        c.setItemsAndRestore(source("B"), PlaybackSnapshot(
            historyIds = listOf("B2", "B1", "B2", "A2"), historyCursor = 3, currentId = "A2",
            historyTransforms = listOf(ImageTransform(userScale = 2f), null, last),
            sourcePositions = mapOf("B" to "B2"),
        ))
        val request = c.requestSourceRestore()!!
        assertEquals(last, request.entryTransform)
        assertTrue(c.commit(request.version))
        assertEquals(2, c.snapshot().sourceHistoryCursors["B"])
    }

    @Test fun restoreCurrentAndDeletingSourceRemoveStaleCursorReferences() {
        val c = atA2()
        restore(c, source("B")); next(c)
        c.setItems(source("A") + source("B"))
        c.restoreCurrent("A2")
        assertEquals(mapOf("A" to 0), c.snapshot().sourceHistoryCursors)
        val deleted = c.snapshot().withoutSource("A")
        assertFalse(deleted.sourcePositions.containsKey("A"))
        assertFalse(deleted.sourceHistoryCursors.containsKey("A"))
    }

    @Test fun sourceWithoutIdentityKeepsOriginalDiscardedFuturePlacementContract() {
        val c = PlaybackCoordinator()
        val items = source("A", 4).map { it.copy(sourceId = "") }
        c.setItems(items)
        next(c); next(c, ImageTransform(userScale = 3f)); next(c, ImageTransform(userScale = 4f))
        previous(c); previous(c)
        c.setItems(items.filter { it.id == "A1" || it.id == "A4" })
        assertEquals("A4", next(c))
        assertEquals(listOf("A1", "A4"), c.snapshot().historyIds)
        assertTrue(c.snapshot().historyTransforms.isEmpty())
        assertTrue(c.snapshot().sourceHistoryCursors.isEmpty())
    }

    @Test fun sameRootRemovedFutureIsNotKeptEvenWhenAnotherSourceHasAValidResumePoint() {
        val c = PlaybackCoordinator()
        c.setItemsAndRestore(source("A", 4), PlaybackSnapshot(
            historyIds = listOf("A1", "A2", "A3", "B1", "B2", "A4"), historyCursor = 1, currentId = "A2",
            historyTransforms = listOf(null, null, ImageTransform(userScale = 4f), null, ImageTransform(userScale = 2f)),
            historySourceIds = listOf("A", "A", "A", "B", "B", "A"),
            sourcePositions = mapOf("A" to "A2", "B" to "B2"), sourceHistoryCursors = mapOf("A" to 1, "B" to 4),
        ))
        c.setItems(source("A", 4).filterNot { it.id == "A3" || it.id == "A4" })
        assertEquals("A1", next(c))
        assertEquals(listOf("A1", "A2", "B1", "B2", "A1"), c.snapshot().historyIds)
        assertEquals(listOf("A", "A", "B", "B", "A"), c.snapshot().historySourceIds)
        assertEquals(ImageTransform(userScale = 2f), c.snapshot().historyTransforms[3])
        assertFalse(c.snapshot().historyTransforms.contains(ImageTransform(userScale = 4f)))
    }

    @Test fun unknownLegacyFutureOwnersAreNotGuessedFromOtherSourcesSavedCursor() {
        val c = PlaybackCoordinator()
        c.setItemsAndRestore(source("A", 2), PlaybackSnapshot(
            historyIds = listOf("A1", "B1", "B2"), historyCursor = 0, currentId = "A1",
            sourcePositions = mapOf("B" to "B2"), sourceHistoryCursors = mapOf("B" to 2),
        ))
        assertEquals("A2", next(c))
        assertEquals(listOf("A1", "A2"), c.snapshot().historyIds)
        assertEquals(listOf("A", "A"), c.snapshot().historySourceIds)
        assertFalse(c.snapshot().sourceHistoryCursors.containsKey("B"))
        assertEquals("B2", c.snapshot().sourcePositions["B"])
    }

    @Test fun deletedSourcesRecordedFutureHistoryIsNotKeptByAnOwnershipLabelAlone() {
        val c = PlaybackCoordinator()
        val initial = PlaybackSnapshot(
            historyIds = listOf("A1", "B1", "B2"), historyCursor = 0, currentId = "A1",
            historySourceIds = listOf("A", "B", "B"), sourcePositions = mapOf("B" to "B2"),
            sourceHistoryCursors = mapOf("B" to 2),
        )
        c.setItemsAndRestore(source("A", 2), initial.withoutSource("B"))
        assertEquals("A2", next(c))
        assertEquals(listOf("A1", "A2"), c.snapshot().historyIds)
        assertEquals(listOf("A", "A"), c.snapshot().historySourceIds)
    }

    @Test fun restartRestoresRecordedOwnerSlotsThenPreservesOnlyOtherRootFuture() {
        val c = atA2()
        restore(c, source("B")); next(c); next(c)
        restore(c, source("A"))
        val saved = c.snapshot().forPersistence()
        val restarted = PlaybackCoordinator().apply { setItemsAndRestore(source("A"), saved) }
        assertEquals("A3", next(restarted))
        assertEquals(listOf("A1", "A2", "B1", "B2", "B3", "A3"), restarted.snapshot().historyIds)
        assertEquals(listOf("A", "A", "B", "B", "B", "A"), restarted.snapshot().historySourceIds)
        assertEquals(4, restarted.snapshot().sourceHistoryCursors["B"])
        assertEquals("B3", restore(restarted, source("B")))
        assertEquals("B2", previous(restarted))
    }

    @Test fun retainedPreviewCurrentAndDirectSeedHaveActualSlotOwnersNotStaleLabels() {
        val c = PlaybackCoordinator().apply { setItems(source("A")) }
        c.restoreKeepingCurrent(PlaybackSnapshot(historyIds = listOf("missing"), historyCursor = 0,
            currentId = "missing", historySourceIds = listOf("B")), "A2")
        assertEquals(listOf("missing", "A2"), c.snapshot().historyIds)
        assertEquals(listOf("B", "A"), c.snapshot().historySourceIds)
        c.restoreCurrent("A3")
        assertEquals(listOf("A3"), c.snapshot().historyIds)
        assertEquals(listOf("A"), c.snapshot().historySourceIds)
    }

    @Test fun activeSharedUriAlwaysUsesCurrentAuthoritativeOwnerNotAnOldSlotLabel() {
        val c = PlaybackCoordinator()
        c.setItemsAndRestore(source("A", 1), PlaybackSnapshot(historyIds = listOf("A1"), historyCursor = 0,
            currentId = "A1", historySourceIds = listOf("B")))
        assertEquals(listOf("A"), c.snapshot().historySourceIds)
        c.setItems(source("A", 1).map { it.copy(sourceId = "B") })
        assertEquals(listOf("B"), c.snapshot().historySourceIds)
    }

    @Test fun restoringOldUnknownOwnersIsPureAndOnlyActualCommitConfirmsActiveHistory() {
        val c = PlaybackCoordinator()
        val old = PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf("B1", "B2", "B3"),
            queueIndex = 1, historyIds = listOf("B1", "B2", "B3"), historyCursor = 1, currentId = "B2")
        c.setItemsAndRestore(source("B", 3), old)
        assertEquals(old, c.snapshot())
        c.restore(old)
        assertEquals(old, c.snapshot())
        val request = c.requestPrevious()
        assertEquals("B1", request.item!!.id)
        assertEquals(old, c.snapshot())
        assertTrue(c.commit(request.version))
        assertEquals(listOf("B", "B", "B"), c.snapshot().historySourceIds)
    }
}
