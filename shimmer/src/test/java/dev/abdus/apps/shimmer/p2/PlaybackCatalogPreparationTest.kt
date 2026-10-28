package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p3.ImageTransform
import org.junit.Assert.*
import org.junit.Test

/** 完整功能等价及实际精确比较路径；不以宿主秒数证明手机性能。 */
class PlaybackCatalogPreparationTest {
    private fun items(vararg ids: String): List<PlaybackItem> = ids.map {
        PlaybackItem(it, "$it.jpg", "dir/$it.jpg", "source", 3L, "DIRECTORY", "content://source")
    }

    private fun play(subject: PlaybackCoordinator): PlaybackRequest = subject.requestNext().also {
        assertTrue(subject.commit(it.version))
    }

    private fun exactCatalog(subject: PlaybackCoordinator, input: List<PlaybackItem>): Boolean =
        PlaybackCoordinator::class.java.getDeclaredMethod("sameCatalog", List::class.java)
            .apply { isAccessible = true }.invoke(subject, input) as Boolean

    private fun cache(subject: PlaybackCoordinator, name: String): Any? =
        PlaybackCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(subject)

    private fun expectInvalidId(block: () -> Unit) {
        try { block(); fail("无效身份必须拒绝，不能被稳定目录快路跳过") }
        catch (_: IllegalArgumentException) { }
    }

    private fun configured(catalog: List<PlaybackItem>, order: PlaybackOrder): PlaybackCoordinator =
        PlaybackCoordinator(37L).also { subject ->
            subject.setItems(catalog)
            subject.setOrder(order)
            repeat(3) { play(subject) }
        }

    private fun assertEquivalentPreparation(
        oldCatalog: List<PlaybackItem>, freshCatalog: List<PlaybackItem>, stored: PlaybackSnapshot,
        oldOrder: PlaybackOrder = stored.order,
    ) {
        val sequential = configured(oldCatalog, oldOrder)
        val combined = configured(oldCatalog, oldOrder)
        sequential.setItems(freshCatalog)
        sequential.restore(stored)
        combined.setItemsAndRestore(freshCatalog, stored)
        assertEquals(sequential.catalogItems(), combined.catalogItems())
        assertEquals(sequential.snapshot(), combined.snapshot())
        assertEquals(sequential.orderedItems(), combined.orderedItems())
        repeat(4) {
            val previous = it % 2 == 1
            val left = if (previous) sequential.requestPrevious() else sequential.requestNext()
            val right = if (previous) combined.requestPrevious() else combined.requestNext()
            assertEquals(left.item, right.item)
            assertEquals(left.freshAdvance, right.freshAdvance)
            assertEquals(left.entryTransform, right.entryTransform)
            assertTrue(sequential.commit(left.version))
            assertTrue(combined.commit(right.version))
            assertEquals(sequential.snapshot(), combined.snapshot())
        }
    }

    @Test fun stableUniqueCatalogUsesExactListComparisonWithoutHashOrReferenceIdentity() {
        val catalog = items("a", "b", "c")
        val subject = PlaybackCoordinator()
        subject.setItems(catalog)
        val input = catalog.map { it.copy() }
        assertNotSame(catalog, input)
        assertNotSame(catalog[0], input[0])
        assertTrue(exactCatalog(subject, input))
        assertFalse(exactCatalog(subject, input.reversed()))
        assertFalse(exactCatalog(subject, input.dropLast(1)))
        assertEquals(catalog, subject.catalogItems())
    }

    @Test fun eachOfSevenPlaybackFieldsAndInsertionOrderIsPartOfExactKey() {
        val catalog = items("a", "b")
        val original = catalog[0]
        val changes = listOf(
            original.copy(id = "different"),
            original.copy(displayName = "new-name.jpg"),
            original.copy(relativePath = "other/a.jpg"),
            original.copy(sourceId = "other-source"),
            original.copy(sourceOrder = original.sourceOrder + 1),
            original.copy(sourceKind = "MULTIPLE_IMAGES"),
            original.copy(sourceRootUri = "content://other-source"),
        )
        for (changed in changes) {
            val subject = PlaybackCoordinator()
            subject.setItems(catalog)
            val input = listOf(changed, catalog[1])
            assertFalse("全部字段均必须参与精确比较：$changed", exactCatalog(subject, input))
            subject.setItems(input)
            assertEquals(input, subject.catalogItems())
            assertTrue(exactCatalog(subject, input))
        }
        val subject = PlaybackCoordinator()
        subject.setItems(catalog)
        subject.setItems(catalog.reversed())
        assertEquals(catalog.reversed(), subject.catalogItems())
    }

    @Test fun sameMutableListReferenceStillChecksCurrentContentsAndOrder() {
        val input = items("a", "b", "c").toMutableList()
        val subject = PlaybackCoordinator()
        subject.setItems(input)
        assertTrue(exactCatalog(subject, input))
        input[0] = input[0].copy(displayName = "changed.jpg")
        assertFalse(exactCatalog(subject, input))
        subject.setItemsAndRestore(input, PlaybackSnapshot())
        assertEquals("changed.jpg", subject.catalogItems()[0].displayName)
        input.reverse()
        assertFalse(exactCatalog(subject, input))
        subject.setItems(input)
        assertEquals(input, subject.catalogItems())
    }

    @Test fun duplicateSameCountCannotEnterStablePathAndFirstOccurrenceStillWins() {
        val catalog = items("a", "b", "c")
        val subject = PlaybackCoordinator()
        subject.setItems(catalog)
        val secondA = catalog[0].copy(displayName = "must-not-win.jpg")
        val duplicate = listOf(catalog[0], secondA, catalog[2])
        assertEquals(catalog.size, duplicate.size)
        assertFalse(exactCatalog(subject, duplicate))
        subject.setItems(duplicate)
        assertEquals(listOf(catalog[0], catalog[2]), subject.catalogItems())
        subject.setItemsAndRestore(listOf(secondA, catalog[0], catalog[1]), PlaybackSnapshot())
        assertEquals(listOf(secondA, catalog[1]), subject.catalogItems())
    }

    @Test fun duplicateUnchangedDedupStillValidatesAllInputAndRetainsMemo() {
        val catalog = items("a", "b", "c")
        val subject = configured(catalog, PlaybackOrder.MANUAL)
        val queue = subject.snapshot().queueIds
        val position = cache(subject, "queuePositionCache")
        val duplicate = listOf(catalog[0], catalog[0].copy(displayName = "ignored.jpg"), catalog[1], catalog[2])
        assertFalse(exactCatalog(subject, duplicate))
        subject.setItemsAndRestore(duplicate, subject.snapshot())
        assertEquals(catalog, subject.catalogItems())
        assertSame(queue, subject.snapshot().queueIds)
        assertSame(position, cache(subject, "queuePositionCache"))
    }

    @Test fun invalidIdentityAfterAnEarlyDifferenceRejectsWithoutPublishingPartialCatalog() {
        val catalog = items("a", "b", "c")
        val invalid = listOf(catalog[0].copy(displayName = "changed"), catalog[1], catalog[2].copy(id = "  "))
        for (combined in listOf(false, true)) {
            val subject = configured(catalog, PlaybackOrder.NATURAL_ASC)
            val before = subject.snapshot()
            expectInvalidId {
                if (combined) subject.setItemsAndRestore(invalid, PlaybackSnapshot()) else subject.setItems(invalid)
            }
            assertEquals(catalog, subject.catalogItems())
            assertEquals(before, subject.snapshot())
        }
    }

    @Test fun blankDuplicateTailCannotBeIgnoredByFirstWinsOrUnchangedPrefix() {
        val catalog = items("a", "b")
        val subject = PlaybackCoordinator()
        subject.setItems(catalog)
        val invalid = catalog + catalog[0].copy(id = "")
        assertFalse(exactCatalog(subject, invalid))
        expectInvalidId { subject.setItemsAndRestore(invalid, PlaybackSnapshot(order = PlaybackOrder.MANUAL)) }
        assertEquals(catalog, subject.catalogItems())
    }

    @Test fun stableSetItemsAndCombinedPreparationBothCancelPendingRequests() {
        val catalog = items("a", "b", "c")
        val subject = configured(catalog, PlaybackOrder.NATURAL_ASC)
        val before = subject.snapshot()
        val first = subject.requestNext()
        subject.setItems(catalog.map { it.copy() })
        assertFalse(subject.commit(first.version))
        assertEquals(before, subject.snapshot())
        val second = subject.requestPrevious()
        subject.setItemsAndRestore(catalog, before.forPersistence())
        assertFalse(subject.commit(second.version))
        assertEquals(before, subject.snapshot())
        assertEquals(before.currentId, subject.current()?.id)
    }

    @Test fun combinedPreparationMatchesSequentialContractForEveryOrderAndChangedCatalog() {
        val original = items("a", "b", "c", "d")
        val scenarios = listOf(original, original.reversed(), original.drop(1), original + items("new"), emptyList())
        for (order in PlaybackOrder.entries) {
            val stored = configured(original, order).snapshot().forPersistence()
            for (fresh in scenarios) assertEquivalentPreparation(original, fresh, stored)
        }
    }

    @Test fun latestAuthoritativeStateOverridesOlderCommittedOrderHistoryAndTransforms() {
        val catalog = items("a", "b", "c")
        val subject = configured(catalog, PlaybackOrder.NATURAL_ASC)
        val placement = ImageTransform(userScale = 2f, offsetX = 0.2f)
        val stored = PlaybackSnapshot(
            order = PlaybackOrder.MANUAL, queueIds = listOf("c", "hidden", "a", "b"),
            historyIds = listOf("c", "b"), historyCursor = 1, currentId = "b",
            historyTransforms = listOf(null, placement), historyResetSerial = 12L,
        )
        val old = subject.requestNext()
        subject.setItemsAndRestore(catalog, stored)
        assertFalse(subject.commit(old.version))
        assertEquals(PlaybackOrder.MANUAL, subject.snapshot().order)
        assertEquals(stored.historyIds, subject.snapshot().historyIds)
        assertEquals(stored.historyTransforms, subject.snapshot().historyTransforms)
        assertEquals(stored.queueIds, subject.snapshot().queueIds)
        assertEquals(12L, subject.snapshot().historyResetSerial)
        val previous = subject.requestPrevious()
        assertEquals("c", previous.item?.id)
        assertFalse(previous.freshAdvance)
    }

    @Test fun completeManualHiddenPositionsRemainDuringDisableAddAndReenable() {
        val full = items("a", "b", "c")
        val hiddenState = PlaybackSnapshot(order = PlaybackOrder.MANUAL,
            queueIds = listOf("c", "hidden", "b", "a"), historyIds = listOf("a"), historyCursor = 0, currentId = "a")
        val subject = PlaybackCoordinator()
        subject.setItemsAndRestore(full.filterNot { it.id == "b" }, hiddenState)
        assertEquals(hiddenState.queueIds, subject.snapshot().queueIds)
        val updated = full + items("new")
        subject.setItemsAndRestore(updated, subject.snapshot().forPersistence())
        assertEquals(listOf("c", "hidden", "b", "a", "new"), subject.snapshot().queueIds)
        assertEquals(listOf("c", "b", "a", "new"), subject.orderedItems().map { it.id })
        assertEquals(hiddenState.historyIds, subject.snapshot().historyIds)
    }

    @Test fun equalIdentityHashesStillInvalidateCatalogAndRandomMembership() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val original = items("Aa", "z")
        val subject = configured(original, PlaybackOrder.RANDOM)
        val old = subject.snapshot()
        val changed = items("BB", "z")
        assertFalse(exactCatalog(subject, changed))
        subject.setItemsAndRestore(changed, old)
        assertEquals(old.queueFingerprint, subject.snapshot().queueFingerprint)
        assertEquals(setOf("BB", "z"), subject.snapshot().queueIds.toSet())
        assertFalse(subject.snapshot().queueIds.contains("Aa"))
        assertEquivalentPreparation(original, changed, old)
    }

    @Test fun defaultOldSnapshotEmptyAndSingleCatalogRemainSafeInEveryOrder() {
        for (order in PlaybackOrder.entries) {
            val subject = PlaybackCoordinator()
            subject.setItemsAndRestore(emptyList(), PlaybackSnapshot(order = order))
            assertNull(play(subject).item)
            val single = items("a")
            subject.setItemsAndRestore(single, PlaybackSnapshot(order = order))
            repeat(4) { assertEquals("a", play(subject).item?.id) }
            assertEquals(listOf("a"), subject.snapshot().historyIds)
            subject.setItems(emptyList())
            assertNull(subject.current())
            assertEquals(listOf("a"), subject.snapshot().historyIds)
        }
    }

    @Test fun elevenThousandCatalogWarmPreparationIsEquivalentAndRetainsImmutableDerivedObjects() {
        val catalog = (1..11_890).map {
            PlaybackItem("id-$it", "$it.jpg", "dir/$it.jpg", "source", 3L, "DIRECTORY", "content://source")
        }
        for (order in PlaybackOrder.entries) {
            val sequential = configured(catalog, order)
            val combined = configured(catalog, order)
            val firstStored = combined.snapshot().forPersistence()
            combined.setItemsAndRestore(catalog, firstStored)
            val queue = combined.snapshot().queueIds
            val positions = cache(combined, "queuePositionCache")
            repeat(8) {
                val exactCopy = catalog.map { item -> item.copy() }
                // 这个纯比较必须对完整11890项／次序为true。省map的早return另由接线及反例检查。
                assertTrue(exactCatalog(combined, exactCopy))
                val stored = combined.snapshot().forPersistence()
                sequential.setItems(exactCopy)
                sequential.restore(stored)
                combined.setItemsAndRestore(exactCopy, stored)
                assertEquals(sequential.snapshot(), combined.snapshot())
                assertSame(queue, combined.snapshot().queueIds)
                assertSame(positions, cache(combined, "queuePositionCache"))
                val left = play(sequential)
                val right = play(combined)
                assertEquals(left.item, right.item)
                assertEquals(sequential.snapshot(), combined.snapshot())
            }
            assertEquals(11_890, combined.snapshot().queueIds.size)
            try { (combined.snapshot().queueIds as MutableList<*>).clear(); fail("派生队列仍须不可变") }
            catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun transformedHistoryAlignmentAndSourceResumeMetadataAreEquivalent() {
        val original = items("a", "b", "c", "d")
        val stored = PlaybackSnapshot(
            order = PlaybackOrder.MANUAL, queueIds = listOf("d", "inactive", "b", "c", "a"),
            historyIds = listOf("inactive", "a", "b", "c"), historyCursor = 2, currentId = "b",
            historyTransforms = listOf(null, ImageTransform(userScale = 2f), ImageTransform(userScale = 3f), null),
            sourcePositions = mapOf("source" to "b", "stopped-source" to "inactive"),
            resumeSourceIds = listOf("stopped-source"), historyResetSerial = 6L,
        )
        assertEquivalentPreparation(original, original.filterNot { it.id == "b" }, stored)
        assertEquivalentPreparation(original, original, stored)
    }

    @Test fun failedInputValidationDoesNotCancelAnOtherwiseCommittableRequest() {
        val catalog = items("a", "b", "c")
        for (combined in listOf(false, true)) {
            val subject = configured(catalog, PlaybackOrder.MANUAL)
            val request = subject.requestPrevious()
            val invalid = catalog + catalog[0].copy(id = "\t")
            expectInvalidId {
                if (combined) subject.setItemsAndRestore(invalid, PlaybackSnapshot()) else subject.setItems(invalid)
            }
            assertTrue("验证失败前没有发布目录或取消合法pending", subject.commit(request.version))
            assertEquals(request.item?.id, subject.current()?.id)
        }
    }

    @Test fun standaloneSetItemsPreservesPreviewCurrentAndHistoryWithoutFormalRestore() {
        val catalog = items("a", "b", "c")
        val preview = configured(catalog, PlaybackOrder.NATURAL_DESC)
        val before = preview.snapshot()
        preview.setItems(catalog.map { it.copy() })
        assertEquals(before, preview.snapshot())
        preview.setItems(catalog.filterNot { it.id == before.currentId })
        assertEquals(before.currentId, preview.snapshot().currentId)
        assertEquals(before.historyIds, preview.snapshot().historyIds)
        assertEquals(before.historyCursor, preview.snapshot().historyCursor)
        assertEquals(PlaybackOrder.NATURAL_DESC, preview.snapshot().order)
    }

    @Test fun combinedPreparationMatchesSanitizationOfLongAndMalformedTransformsHistory() {
        val catalog = items("a", "b", "c", "d")
        val history = (0 until PlaybackCoordinator.HISTORY_LIMIT + 13).map { catalog[it % catalog.size].id }
        for (order in PlaybackOrder.entries) {
            val derived = configured(catalog, order).snapshot()
            val stored = derived.copy(
                historyIds = history, historyCursor = history.lastIndex - 3,
                currentId = "stale-current-not-at-cursor",
                historyTransforms = List(history.size + 4) { index ->
                    if (index % 3 == 0) ImageTransform(userScale = Float.NaN,
                        offsetY = Float.POSITIVE_INFINITY, rotationDegrees = 720f) else null
                },
                sourcePositions = mapOf("source" to "b", "disabled" to "hidden"),
                resumeSourceIds = listOf("disabled"),
            )
            assertEquivalentPreparation(catalog, catalog, stored)
            assertEquivalentPreparation(catalog, catalog.drop(1), stored)
        }
    }

    @Test fun diagnosticCheckpointRunsOnceAfterCatalogApplicationBeforeAuthoritativeRestore() {
        val original = items("a", "b", "c")
        val fresh = original + items("new")
        val subject = configured(original, PlaybackOrder.NATURAL_ASC)
        val before = subject.snapshot()
        val stored = PlaybackSnapshot(order = PlaybackOrder.MANUAL,
            queueIds = listOf("new", "c", "b", "a"), currentId = "b", historyIds = listOf("b"), historyCursor = 0)
        var callbacks = 0
        subject.setItemsAndRestore(fresh, stored) {
            callbacks++
            assertEquals(fresh, subject.catalogItems())
            assertSame("目录应用后但尚未归一化权威快照", before, subject.snapshot())
        }
        assertEquals(1, callbacks)
        assertEquals(stored.copy(queueIndex = 2), subject.snapshot())
        val stableBefore = subject.snapshot()
        subject.setItemsAndRestore(fresh, stored) {
            callbacks++
            assertSame(stableBefore, subject.snapshot())
        }
        assertEquals("稳定快路亦有真实计时检查点", 2, callbacks)
    }

    @Test fun diagnosticCheckpointIsNotCalledWhenCatalogInputValidationFails() {
        val catalog = items("a", "b", "c")
        val subject = configured(catalog, PlaybackOrder.MANUAL)
        val before = subject.snapshot()
        var callbacks = 0
        expectInvalidId {
            subject.setItemsAndRestore(catalog + catalog[0].copy(id = ""), before) { callbacks++ }
        }
        assertEquals(0, callbacks)
        assertEquals(catalog, subject.catalogItems())
        assertEquals(before, subject.snapshot())
    }
}
