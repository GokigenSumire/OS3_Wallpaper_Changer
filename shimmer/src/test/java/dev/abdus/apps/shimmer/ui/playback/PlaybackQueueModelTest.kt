package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackGroupKey
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P9-ORDER-GROUP-01：播放页按来源／目录分级、组别调序、"刚移动"提示与定位当前。 */
class PlaybackQueueModelTest {
    private fun item(id: String, path: String, source: String, kind: String = "DIRECTORY_TREE") =
        PlaybackItem(id, id.substringAfterLast('/'), path, source, sourceKind = kind, sourceRootUri = "root-$source")

    private val dirA = listOf(
        item("a1", "a1.jpg", "A"),
        item("a2", "1/a2.jpg", "A"),
        item("a3", "1/1-1/a3.jpg", "A"),
    )
    private val single = item("s1", "s1.jpg", "S", kind = "SINGLE_DOCUMENT")
    private val batch = listOf(
        item("m1", "m1.jpg", "M", kind = "MULTIPLE_DOCUMENTS"),
        item("m2", "m2.jpg", "M", kind = "MULTIPLE_DOCUMENTS"),
    )

    private fun rows(model: QueueModel, view: QueueViewState = QueueViewState()) = buildQueueRows(model, view)
    private fun List<QueueRow>.images() = filterIsInstance<QueueRow.Image>()
    private fun List<QueueRow>.headers() = filterIsInstance<QueueRow.GroupHeader>()
    private fun List<QueueRow>.levels() = filterIsInstance<QueueRow.Level>()
    private val groupA = PlaybackGroupKey("DIRECTORY_TREE", "A")

    @Test
    fun randomRoundShowsOneHeaderPerSourceInsteadOfOnePerImage() {
        // 随机轮次里来源交错出现：版本 39 逐张平铺，这里必须聚合成每个来源一组
        val ordered = listOf(dirA[2], batch[0], dirA[0], single, batch[1], dirA[1])
        val model = buildQueueModel(ordered, PlaybackOrder.RANDOM, currentId = "a1")

        val headers = rows(model).headers()
        assertEquals(3, headers.size)
        assertEquals(listOf(3, 2, 1), headers.map { it.itemCount })
        assertTrue(headers.none { it.continued })
        // 每行带自己的播放位置，即使列表按来源分组
        assertEquals(3, model.positions["a1"])
        assertEquals(6, model.positions["a2"])
    }

    @Test
    fun manualOrderKeepsContiguousSegmentsAndMarksContinuations() {
        val ordered = listOf(dirA[0], single, dirA[1], dirA[2])
        val model = buildQueueModel(ordered, PlaybackOrder.MANUAL, currentId = null)

        val headers = rows(model).headers()
        assertEquals(3, headers.size)
        assertEquals(listOf(1, 2, 1), headers.map { it.groupIndex })
        assertEquals(2, model.groupCount)
        assertEquals(listOf(false, false, true), headers.map { it.continued })
        // 续段仍报告整个来源的图片数
        assertEquals(3, headers[2].itemCount)
        assertEquals(1, model.blocks[0].items.size)
    }

    @Test
    fun everyLevelStartsCollapsedSoAHugeLibraryEmitsNoImageRows() {
        val model = buildQueueModel(dirA + batch, PlaybackOrder.NATURAL_ASC, currentId = null)
        val list = rows(model)

        assertTrue(list.images().isEmpty())
        assertEquals(listOf("来源目录：根目录/", "本组图片"), list.levels().map { it.label })
        assertTrue(list.levels().none { it.expanded })
    }

    @Test
    fun expandingALevelRevealsOnlyItsOwnNextLevel() {
        val model = buildQueueModel(dirA, PlaybackOrder.NATURAL_ASC, currentId = null)
        val root = levelKey(groupA, "")

        val opened = rows(model, QueueViewState(expanded = setOf(root)))
        assertEquals(listOf("a1"), opened.images().map { it.item.id })
        assertEquals(listOf("来源目录：根目录/", "子目录：1/"), opened.levels().map { it.label })
        assertEquals(2, opened.levels()[1].totalCount)

        val deeper = rows(model, QueueViewState(expanded = setOf(root, levelKey(groupA, "1"))))
        assertEquals(listOf("a1", "a2"), deeper.images().map { it.item.id })
        assertEquals("子目录：1-1/", deeper.levels().last().label)
        assertFalse(deeper.levels().last().expanded)
    }

    @Test
    fun directoryRootUsesTheFolderNameWhenKnown() {
        val model = buildQueueModel(dirA, PlaybackOrder.NATURAL_ASC, null, folderNameOf = { "相册" })
        assertEquals("来源目录：相册/", rows(model).levels().first().label)
    }

    @Test
    fun singleImageSourceShowsItsRowWithoutAnExtraLevel() {
        val model = buildQueueModel(listOf(single), PlaybackOrder.NATURAL_ASC, currentId = "s1")
        val list = rows(model)

        assertTrue(list.levels().isEmpty())
        assertEquals("s1", list.images().single().item.id)
        assertTrue(list.images().single().isCurrent)
        assertTrue(list.headers().single().containsCurrent)
    }

    @Test
    fun longLevelsArePagedFiftyAtATime() {
        val many = (1..120).map { item("p$it", "p$it.jpg", "A") }
        val model = buildQueueModel(many, PlaybackOrder.NATURAL_ASC, currentId = null)
        val view = QueueViewState(expanded = setOf(levelKey(groupA, "")))

        val first = rows(model, view)
        assertEquals(QueuePageSize, first.images().size)
        val more = first.filterIsInstance<QueueRow.More>().single()
        assertEquals(70, more.remaining)

        val second = rows(model, view.showMore(more.pageKey))
        assertEquals(100, second.images().size)
        assertEquals(20, second.filterIsInstance<QueueRow.More>().single().remaining)
    }

    @Test
    fun revealOpensEveryAncestorAndPagesToTheTarget() {
        val deep = (1..130).map { item("d$it", "1/1-1/d$it.jpg", "A") } + item("top", "top.jpg", "A")
        val model = buildQueueModel(deep, PlaybackOrder.RANDOM, currentId = "d120")

        val list = rows(model, QueueViewState().reveal(model, "d120"))
        val target = list.images().single { it.item.id == "d120" }
        assertTrue(target.isCurrent)
        assertEquals(2 + 1, target.depth)
        // 目标所在页显示完整，后面的几张也在
        assertTrue(list.images().any { it.item.id == "d130" })
        assertTrue(list.levels().all { it.expanded })
        assertTrue(list.levels().all { it.containsCurrent })
    }

    @Test
    fun revealIgnoresItemsOutsideTheList() {
        val model = buildQueueModel(dirA, PlaybackOrder.NATURAL_ASC, currentId = "gone")
        val view = QueueViewState()
        assertNull(model.currentId)
        assertEquals(view, view.reveal(model, "gone"))
    }

    @Test
    fun onlyManualOrderOffersMovesAndSelection() {
        val view = QueueViewState(expanded = setOf(levelKey(groupA, "")), selectedItemId = "a1")
        val random = rows(buildQueueModel(dirA, PlaybackOrder.RANDOM, null), view)
        assertTrue(random.images().none { it.canMoveUp || it.canMoveDown || it.selected })
        assertTrue(random.headers().none { it.canMoveUp || it.canMoveDown })

        val manual = rows(buildQueueModel(dirA + single, PlaybackOrder.MANUAL, null), view)
        val first = manual.images().single { it.item.id == "a1" }
        assertTrue(first.selected)
        assertFalse(first.canMoveUp)
        assertTrue(first.canMoveDown)
        assertEquals(listOf(false, true), manual.headers().map { it.canMoveUp })
        assertEquals(listOf(true, false), manual.headers().map { it.canMoveDown })
    }

    @Test
    fun movedRowAndMovedGroupAreHighlighted() {
        val model = buildQueueModel(dirA + single, PlaybackOrder.MANUAL, null)
        val view = QueueViewState(
            expanded = setOf(levelKey(groupA, "")),
            movedItemId = "a1",
            movedGroup = PlaybackGroupKey("SINGLE_DOCUMENT", "S"),
        )
        val list = rows(model, view)
        assertEquals(listOf("a1"), list.images().filter { it.moved }.map { it.item.id })
        assertEquals(listOf(false, true), list.headers().map { it.highlighted })
    }

    @Test
    fun adjacentMoveSwapsNeighboursAndStopsAtTheEnds() {
        val ids = listOf("a", "b", "c")
        assertEquals(QueueMove(listOf("b", "a", "c"), 1, 2), moveItemIds(ids, "a", 1))
        assertNull(moveItemIds(ids, "a", -1))
        assertNull(moveItemIds(ids, "c", 1))
        assertNull(moveItemIds(ids, "x", 1))
    }

    @Test
    fun moveToPositionKeepsTheOthersInRelativeOrder() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(QueueMove(listOf("b", "c", "a", "d"), 1, 3), moveItemToPosition(ids, "a", 3))
        assertEquals(QueueMove(listOf("d", "a", "b", "c"), 4, 1), moveItemToPosition(ids, "d", 1))
        assertNull(moveItemToPosition(ids, "b", 2))
        assertNull(moveItemToPosition(ids, "b", 5))
        assertNull(moveItemToPosition(ids, "b", 0))
    }

    @Test
    fun groupMoveGathersTheSourceAndPassesTheWholeNeighbour() {
        // A 被拆成两段：上移 S 时，S 要越过 A 的全部图片，A 内顺序保持
        val ordered = listOf(dirA[0], single, dirA[1], dirA[2]) + batch
        val down = moveGroupIds(ordered, groupA, 1)!!
        assertEquals(listOf("s1", "a1", "a2", "a3", "m1", "m2"), down.ids)
        assertEquals(1 to 2, down.from to down.to)

        val up = moveGroupIds(ordered, PlaybackGroupKey("MULTIPLE_DOCUMENTS", "M"), -1)!!
        assertEquals(listOf("a1", "m1", "m2", "s1", "a2", "a3"), up.ids)
        assertEquals(3 to 2, up.from to up.to)

        assertNull(moveGroupIds(ordered, groupA, -1))
        assertNull(moveGroupIds(ordered, PlaybackGroupKey("MULTIPLE_DOCUMENTS", "M"), 1))
        assertNull(moveGroupIds(ordered, groupA, 2))
    }

    @Test
    fun positionInputAcceptsOnlyListPositions() {
        assertEquals(3, parseQueuePosition(" 3 ", 5))
        assertNull(parseQueuePosition("0", 5))
        assertNull(parseQueuePosition("6", 5))
        assertNull(parseQueuePosition("二", 5))
        assertNull(parseQueuePosition("", 5))
    }

    @Test
    fun rowKeysAreUniqueAcrossSplitSegments() {
        val ordered = listOf(dirA[0], single, dirA[1], dirA[2])
        val model = buildQueueModel(ordered, PlaybackOrder.MANUAL, null)
        val all = setOf(levelKey(groupA, ""), levelKey(groupA, "1"), levelKey(groupA, "1/1-1"))
        val list = rows(model, QueueViewState(expanded = all))
        assertEquals(list.size, list.map { it.key }.toSet().size)
        assertEquals(4, list.images().size)
    }
}
