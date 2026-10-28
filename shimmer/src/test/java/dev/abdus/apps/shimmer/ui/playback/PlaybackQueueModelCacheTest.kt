package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** P9-QUEUE-CACHE-01：缓存命中、当前标识更新及完整元数据失效边界。 */
class PlaybackQueueModelCacheTest {
    private val a1 = item("a1", "A", "a1.jpg")
    private val a2 = item("a2", "A", "子目录/a2.jpg")
    private val b1 = item("b1", "B", "b1.jpg")
    private val ordered = listOf(a1, a2, b1)

    private fun item(id: String, source: String, path: String) = PlaybackItem(
        id = id,
        displayName = path.substringAfterLast('/'),
        relativePath = path,
        sourceId = source,
        sourceKind = "DIRECTORY_TREE",
        sourceRootUri = "root-$source",
    )

    private fun assertSharedStructure(first: QueueModel, next: QueueModel) {
        assertSame(first.ordered, next.ordered)
        assertSame(first.blocks, next.blocks)
        assertSame(first.positions, next.positions)
        assertSame(first.blockOf, next.blockOf)
        first.blocks.indices.forEach { index ->
            assertSame(first.blocks[index].tree, next.blocks[index].tree)
        }
        assertEquals(first.groupCount, next.groupCount)
        assertEquals(first.order, next.order)
    }

    @Test
    fun firstResolveMatchesTheUncachedBuilder() {
        val actual = PlaybackQueueModelCache().resolve(ordered, PlaybackOrder.NATURAL_ASC, "a2")

        assertEquals(buildQueueModel(ordered, PlaybackOrder.NATURAL_ASC, "a2"), actual)
        assertEquals(2, actual.groupCount)
        assertEquals("a2", actual.currentId)
    }

    @Test
    fun sameInputAndCurrentReuseTheWholeModelWithoutCallingResolvers() {
        var pathCalls = 0
        var folderCalls = 0
        val cache = PlaybackQueueModelCache(
            sourcePathOf = { pathCalls++; it.sourceRootUri },
            folderNameOf = { folderCalls++; "相册" },
        )
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")
        val firstPathCalls = pathCalls
        val firstFolderCalls = folderCalls

        repeat(3) { assertSame(first, cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")) }

        assertEquals(firstPathCalls, pathCalls)
        assertEquals(firstFolderCalls, folderCalls)
        assertTrue(pathCalls > 0)
        assertTrue(folderCalls > 0)
    }

    @Test
    fun equalMetadataInANewListStillHitsTheCache() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.MANUAL, "a1")
        val separatelyRead = ordered.map { it.copy() }

        assertNotSame(ordered, separatelyRead)
        assertSame(first, cache.resolve(separatelyRead, PlaybackOrder.MANUAL, "a1"))
    }

    @Test
    fun currentOnlyChangesCopyTheIdentifierAndReuseEveryStructure() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.RANDOM, "a1")
        val next = cache.resolve(ordered, PlaybackOrder.RANDOM, "b1")

        assertNotSame(first, next)
        assertEquals("a1", first.currentId)
        assertEquals("b1", next.currentId)
        assertSharedStructure(first, next)
        assertSame(next, cache.resolve(ordered, PlaybackOrder.RANDOM, "b1"))
    }

    @Test
    fun currentChangeUpdatesVisibleMarkersWithoutChangingTheDirectoryTree() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")
        val view = QueueViewState().reveal(first, "a2").reveal(first, "b1")
        val next = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "b1")
        val rows = buildQueueRows(next, view)

        assertSharedStructure(first, next)
        assertEquals(listOf("b1"), rows.filterIsInstance<QueueRow.Image>().filter { it.isCurrent }.map { it.item.id })
        assertEquals(listOf("B"), rows.filterIsInstance<QueueRow.GroupHeader>().filter { it.containsCurrent }.map { it.group.sourceId })
    }

    @Test
    fun invalidInitialCurrentIsNotRetained() {
        val model = PlaybackQueueModelCache().resolve(ordered, PlaybackOrder.NATURAL_ASC, "gone")

        assertNull(model.currentId)
        assertFalse("gone" in model.blockOf)
    }

    @Test
    fun invalidCurrentAfterAValidOneClearsOnlyTheIdentifier() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")
        val next = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "gone")

        assertNotSame(first, next)
        assertNull(next.currentId)
        assertSharedStructure(first, next)
        assertSame(next, cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "another-gone"))
    }

    @Test
    fun nullCurrentDoesNotRebuildTheModel() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")
        val next = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, null)

        assertNull(next.currentId)
        assertSharedStructure(first, next)
        assertSame(next, cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, null))
    }

    @Test
    fun sortingTypeChangeRebuildsEvenWhenTheItemSequenceIsEqual() {
        val cache = PlaybackQueueModelCache()
        val interleaved = listOf(a1, b1, a2)
        val first = cache.resolve(interleaved, PlaybackOrder.RANDOM, "a1")
        val manual = cache.resolve(interleaved, PlaybackOrder.MANUAL, "a1")

        assertNotSame(first.blocks, manual.blocks)
        assertEquals(2, first.blocks.size)
        assertEquals(3, manual.blocks.size)
        assertTrue(manual.blocks.last().continued)
        assertEquals(PlaybackOrder.MANUAL, manual.order)
    }

    @Test
    fun allSortingTypeChangesInvalidateTheCache() {
        val cache = PlaybackQueueModelCache()
        var previous = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")

        listOf(PlaybackOrder.NATURAL_DESC, PlaybackOrder.RANDOM, PlaybackOrder.MANUAL, PlaybackOrder.NATURAL_ASC)
            .forEach { order ->
                val next = cache.resolve(ordered, order, "a1")
                assertNotSame(previous, next)
                assertNotSame(previous.blocks, next.blocks)
                assertEquals(order, next.order)
                previous = next
            }
    }

    @Test
    fun manualMoveRebuildsThePositionTablesAndSegments() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.MANUAL, "a2")
        val moved = cache.resolve(listOf(a1, b1, a2), PlaybackOrder.MANUAL, "a2")

        assertNotSame(first.positions, moved.positions)
        assertEquals(3, moved.positions["a2"])
        assertEquals(3, moved.blocks.size)
        assertEquals(2, moved.blockOf["a2"])
    }

    @Test
    fun aNewRandomRoundSequenceCannotReuseOldPlaybackPositions() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.RANDOM, "b1")
        val next = cache.resolve(listOf(b1, a2, a1), PlaybackOrder.RANDOM, "b1")

        assertNotSame(first.blocks, next.blocks)
        assertEquals(3, first.positions["b1"])
        assertEquals(1, next.positions["b1"])
        assertEquals(listOf("B", "A"), next.blocks.map { it.group.sourceId })
        assertEquals(listOf("a2", "a1"), next.blocks.last().items.map { it.id })
    }

    @Test
    fun addingAnItemRebuildsTheTreeAndPositions() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")
        val a3 = item("a3", "A", "新增/a3.jpg")
        val next = cache.resolve(ordered + a3, PlaybackOrder.NATURAL_ASC, "a1")

        assertNotSame(first.blocks, next.blocks)
        assertNotSame(first.blocks.first().tree, next.blocks.first().tree)
        assertEquals(4, next.size)
        assertEquals(4, next.positions["a3"])
        assertTrue(next.blocks.first().tree.children.any { it.path == "新增" })
    }

    @Test
    fun removingAnItemRebuildsAndDropsAnUnavailableCurrent() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a2")
        val next = cache.resolve(listOf(a1, b1), PlaybackOrder.NATURAL_ASC, "a2")

        assertNotSame(first.blocks, next.blocks)
        assertEquals(2, next.size)
        assertFalse("a2" in next.positions)
        assertFalse("a2" in next.blockOf)
        assertNull(next.currentId)
        assertTrue(next.blocks.first().tree.children.isEmpty())
    }

    @Test
    fun relativePathMetadataChangeRebuildsTheDirectoryTree() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a2")
        val changed = listOf(a1, a2.copy(relativePath = "新目录/下一层/a2.jpg"), b1)
        val next = cache.resolve(changed, PlaybackOrder.NATURAL_ASC, "a2")

        assertNotSame(first.blocks.first().tree, next.blocks.first().tree)
        assertEquals("新目录", next.blocks.first().tree.children.single().path)
        assertEquals("新目录/下一层", next.blocks.first().tree.children.single().children.single().path)
    }

    @Test
    fun sourceRootMetadataChangeRefreshesTheFormattedPathAndRootName() {
        val cache = PlaybackQueueModelCache(
            sourcePathOf = { "路径：${it.sourceRootUri}" },
            folderNameOf = { "目录：$it" },
        )
        val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")
        val changed = ordered.map { if (it.sourceId == "A") it.copy(sourceRootUri = "new-root") else it }
        val next = cache.resolve(changed, PlaybackOrder.NATURAL_ASC, "a1")

        assertNotSame(first.blocks, next.blocks)
        assertEquals("路径：new-root", next.blocks.first().sourcePath)
        assertEquals("目录：new-root", next.blocks.first().rootName)
    }

    @Test
    fun everyPlaybackItemMetadataFieldParticipatesInInvalidation() {
        // 即使该字段不改变目录结构，也必须保留最新显示元数据；不能只比较 ID 或路径。
        val changedItems = listOf(
            a1.copy(id = "new-id"),
            a1.copy(displayName = "新文件名.jpg"),
            a1.copy(relativePath = "新目录/a1.jpg"),
            a1.copy(sourceId = "new-source"),
            a1.copy(sourceOrder = 2),
            a1.copy(sourceKind = "MULTIPLE_DOCUMENTS"),
            a1.copy(sourceRootUri = "new-root"),
        )
        changedItems.forEach { changedItem ->
            val cache = PlaybackQueueModelCache()
            val first = cache.resolve(ordered, PlaybackOrder.NATURAL_ASC, "a1")
            val next = cache.resolve(listOf(changedItem, a2, b1), PlaybackOrder.NATURAL_ASC, "a1")

            assertNotSame(first, next)
            assertNotSame(first.blocks, next.blocks)
            assertEquals(changedItem, next.ordered.first())
            assertEquals(buildQueueModel(next.ordered, next.order, "a1"), next)
        }
    }

    @Test
    fun emptyInputNormalizesCurrentAndRepeatedEmptyInputReusesTheModel() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(emptyList(), PlaybackOrder.RANDOM, "a1")

        assertEquals(PlaybackOrder.RANDOM, first.order)
        assertNull(first.currentId)
        assertTrue(first.blocks.isEmpty())
        assertTrue(first.positions.isEmpty())
        assertTrue(first.blockOf.isEmpty())
        assertSame(first, cache.resolve(emptyList(), PlaybackOrder.RANDOM, null))
    }

    @Test
    fun nonEmptyToEmptyInputClearsTheOldStructure() {
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(ordered, PlaybackOrder.MANUAL, "a1")
        val empty = cache.resolve(emptyList(), PlaybackOrder.MANUAL, "a1")

        assertNotSame(first, empty)
        assertEquals(0, empty.size)
        assertEquals(0, empty.groupCount)
        assertNull(empty.currentId)
        assertTrue(empty.blocks.isEmpty())
        assertTrue(empty.positions.isEmpty())
        assertTrue(empty.blockOf.isEmpty())
    }

    @Test
    fun emptyToNonEmptyInputBuildsTheAvailableStructure() {
        val cache = PlaybackQueueModelCache()
        val empty = cache.resolve(emptyList(), PlaybackOrder.MANUAL, null)
        val next = cache.resolve(ordered, PlaybackOrder.MANUAL, "a2")

        assertNotSame(empty, next)
        assertEquals(ordered, next.ordered)
        assertEquals(2, next.groupCount)
        assertEquals("a2", next.currentId)
    }

    @Test
    fun disablingAndRestoringASourceUsesOnlyTheLatestCatalog() {
        val cache = PlaybackQueueModelCache()
        val full = cache.resolve(ordered, PlaybackOrder.MANUAL, "b1")
        val disabled = cache.resolve(listOf(a1, a2), PlaybackOrder.MANUAL, "b1")
        val restored = cache.resolve(ordered, PlaybackOrder.MANUAL, "b1")

        assertNull(disabled.currentId)
        assertFalse("b1" in disabled.blockOf)
        assertEquals(listOf("A"), disabled.blocks.map { it.group.sourceId })
        assertNotSame(full.blocks, restored.blocks)
        assertNotSame(disabled.blocks, restored.blocks)
        assertEquals(full, restored)
        assertEquals("b1", restored.currentId)
    }

    @Test
    fun callerMutatingTheOriginalListDoesNotAlterTheCachedSnapshotOrCauseAFalseHit() {
        val input = ordered.toMutableList()
        val cache = PlaybackQueueModelCache()
        val first = cache.resolve(input, PlaybackOrder.MANUAL, "a1")
        input.reverse()
        val next = cache.resolve(input, PlaybackOrder.MANUAL, "a1")

        assertEquals(listOf("a1", "a2", "b1"), first.ordered.map { it.id })
        assertEquals(listOf("b1", "a2", "a1"), next.ordered.map { it.id })
        assertNotSame(first, next)
        input.clear()
        assertEquals(3, next.size)
        val empty = cache.resolve(input, PlaybackOrder.MANUAL, "a1")
        assertEquals(0, empty.size)
        assertNull(empty.currentId)
    }

    @Test
    fun defaultResolversMatchTheBuilderIncludingABlankRootFallback() {
        val blankRoot = a1.copy(sourceRootUri = "")
        val model = PlaybackQueueModelCache().resolve(listOf(blankRoot), PlaybackOrder.NATURAL_ASC, "a1")

        assertEquals(buildQueueModel(listOf(blankRoot), PlaybackOrder.NATURAL_ASC, "a1"), model)
        assertEquals("A", model.blocks.single().sourcePath)
        assertEquals("根目录", model.blocks.single().rootName)
    }
}
