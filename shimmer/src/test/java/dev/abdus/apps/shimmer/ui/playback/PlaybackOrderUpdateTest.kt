package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackGroupKey
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.forPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackOrderUpdateTest {
    private val catalog = (1..20).map { PlaybackItem("uri-$it", "$it.jpg", "dir/$it.jpg", "source-a") }
    private val ascending = catalog.map { it.id }

    /** 已在名称升序下播放到第 3 张：历史 1→2→3。 */
    private val playedAscending = PlaybackSnapshot(
        order = PlaybackOrder.NATURAL_ASC,
        queueIds = ascending,
        queueIndex = 2,
        historyIds = listOf("uri-1", "uri-2", "uri-3"),
        historyCursor = 2,
        currentId = "uri-3",
    )

    @Test
    fun descendingRebuildsTheQueueInsteadOfOnlyRenamingTheOrder() {
        val updated = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.NATURAL_DESC)

        assertEquals(PlaybackOrder.NATURAL_DESC, updated.order)
        assertEquals(ascending.reversed(), updated.queueIds)
        assertEquals(ascending.reversed().indexOf("uri-3"), updated.queueIndex)
    }

    @Test
    fun switchingOrderKeepsHistoryAndTheCurrentImage() {
        val updated = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.RANDOM)

        assertEquals(playedAscending.historyIds, updated.historyIds)
        assertEquals(playedAscending.historyCursor, updated.historyCursor)
        assertEquals("uri-3", updated.currentId)
    }

    @Test
    fun randomMaterialisesAFullShuffledRoundWithAFingerprint() {
        val updated = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.RANDOM)

        assertEquals(PlaybackOrder.RANDOM, updated.order)
        assertEquals(ascending.toSet(), updated.queueIds.toSet())
        assertEquals(ascending.size, updated.queueIds.size)
        assertNotEquals(ascending, updated.queueIds)
        assertNotEquals(0, updated.queueFingerprint)
    }

    @Test
    fun randomRoundMatchesWhatTheWallpaperRebuildsFromThePersistedSnapshot() {
        val saved = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.RANDOM).forPersistence()

        // 壁纸服务与 P2 页使用同一个种子，从持久化快照重建出的随机轮次必须与页面看到的一致
        val wallpaper = PlaybackCoordinator(randomSeed = PlaybackOrderUpdate.RANDOM_SEED)
        wallpaper.setItems(catalog)
        wallpaper.restore(saved)
        val expected = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.RANDOM)

        assertEquals(expected.queueIds, wallpaper.orderedItems().map { it.id })
    }

    @Test
    fun firstUseWithoutASnapshotStillProducesAQueue() {
        val updated = PlaybackOrderUpdate.apply(null, catalog, PlayOrder.NATURAL_DESC)

        assertEquals(ascending.reversed(), updated.queueIds)
        assertEquals(emptyList<String>(), updated.historyIds)
        assertNull(updated.currentId)
    }

    @Test
    fun manualQueueStillCoversTheWholeCatalog() {
        // 与 P2 页同一路径（PlaybackCoordinator.setOrder），这里只保证队列完整，不重新定义 P2 行为
        val manualIds = ascending.shuffled(java.util.Random(7))
        val manual = playedAscending.copy(order = PlaybackOrder.MANUAL, queueIds = manualIds)
        val viaRandom = PlaybackOrderUpdate.apply(manual, catalog, PlayOrder.RANDOM)

        val back = PlaybackOrderUpdate.apply(viaRandom, catalog, PlayOrder.MANUAL)

        assertEquals(PlaybackOrder.MANUAL, back.order)
        assertEquals(ascending.size, back.queueIds.size)
        assertEquals(ascending.toSet(), back.queueIds.toSet())
    }

    @Test
    fun tabOrderAndPlaybackOrderMapOneToOne() {
        for (order in PlayOrder.entries) {
            assertEquals(order, PlaybackOrderUpdate.fromPlaybackOrder(PlaybackOrderUpdate.toPlaybackOrder(order)))
        }
        assertEquals(PlaybackOrder.entries.toSet(), PlayOrder.entries.map(PlaybackOrderUpdate::toPlaybackOrder).toSet())
        assertEquals(PlayOrder.NATURAL_ASC, PlaybackOrderUpdate.fromPlaybackOrder(null))
    }

    /** 停用第 5 张、扫描新增第 21 张后的目录。 */
    private val changedCatalog = catalog.filterNot { it.id == "uri-5" } +
        PlaybackItem("uri-21", "21.jpg", "dir/21.jpg", "source-a")

    @Test
    fun reloadDropsStoppedImagesAndAddsNewOnesInTheCurrentOrder() {
        val reloaded = PlaybackOrderUpdate.reload(playedAscending, changedCatalog)

        assertEquals(PlaybackOrder.NATURAL_ASC, reloaded.order)
        assertEquals(ascending.filterNot { it == "uri-5" } + "uri-21", reloaded.queueIds)
        assertEquals(playedAscending.historyIds, reloaded.historyIds)
        assertEquals("uri-3", reloaded.currentId)
        assertEquals(2, reloaded.queueIndex)
    }

    @Test
    fun reloadingTheSameCatalogTwiceChangesNothing() {
        val once = PlaybackOrderUpdate.reload(playedAscending, changedCatalog)

        assertEquals(once, PlaybackOrderUpdate.reload(once, changedCatalog))
    }

    @Test
    fun reloadKeepsTheRandomRoundInsteadOfReshuffling() {
        val random = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.RANDOM)

        val reloaded = PlaybackOrderUpdate.reload(random, changedCatalog)

        assertEquals(PlaybackOrder.RANDOM, reloaded.order)
        // 已抽出的位置不变，停用的移出，新增的排在本轮末尾
        assertEquals(random.queueIds.filterNot { it == "uri-5" } + "uri-21", reloaded.queueIds)
        assertEquals(random.historyIds, reloaded.historyIds)
        assertEquals(random.currentId, reloaded.currentId)
    }

    @Test
    fun reloadWithoutASnapshotStartsANaturalQueue() {
        val reloaded = PlaybackOrderUpdate.reload(null, changedCatalog)

        assertEquals(PlaybackOrder.NATURAL_ASC, reloaded.order)
        assertEquals(changedCatalog.map { it.id }, reloaded.queueIds)
        assertNull(reloaded.currentId)
    }

    @Test
    fun displayedItemFallsBackToTheHistoryCursor() {
        assertEquals("uri-3", playedAscending.displayedItemId())
        assertEquals("uri-2", playedAscending.copy(currentId = null, historyCursor = 1).displayedItemId())
        assertNull(PlaybackSnapshot().displayedItemId())
        assertNull(playedAscending.copy(currentId = null, historyCursor = 9).displayedItemId())
    }

    private val manual = playedAscending.copy(order = PlaybackOrder.MANUAL)

    @Test
    fun moveSwapsNeighboursOnTheLatestManualOrder() {
        val (moved, result) = PlaybackOrderUpdate.move(manual, catalog) { ordered ->
            moveItemIds(ordered.map { it.id }, "uri-3", 1)
        }

        assertEquals(QueueMove(moved.queueIds, 3, 4), result)
        assertEquals(PlaybackOrder.MANUAL, moved.order)
        assertEquals(listOf("uri-1", "uri-2", "uri-4", "uri-3"), moved.queueIds.take(4))
        // 移动只改次序，历史与当前图片保留
        assertEquals(manual.historyIds, moved.historyIds)
        assertEquals("uri-3", moved.currentId)
    }

    @Test
    fun moveOutsideManualOrderReturnsTheStoredSnapshotUntouched() {
        var called = false
        val (snapshot, result) = PlaybackOrderUpdate.move(playedAscending, changedCatalog) {
            called = true
            null
        }

        assertNull(result)
        assertEquals(false, called)
        // 目录已变化也不写对齐结果：什么都没移动就不该保存
        assertEquals(playedAscending, snapshot)
    }

    @Test
    fun moveThatCannotHappenKeepsTheStoredSnapshot() {
        val (snapshot, result) = PlaybackOrderUpdate.move(manual, changedCatalog) { ordered ->
            moveItemIds(ordered.map { it.id }, "uri-1", -1)
        }

        assertNull(result)
        assertEquals(manual, snapshot)
    }

    @Test
    fun groupMoveThroughTheCoordinatorKeepsEachSourceTogether() {
        val twoSources = catalog.map { if (it.id.removePrefix("uri-").toInt() > 10) it.copy(sourceId = "source-b") else it }

        val (moved, result) = PlaybackOrderUpdate.move(manual, twoSources) { ordered ->
            moveGroupIds(ordered, PlaybackGroupKey("", "source-b"), -1)
        }

        assertEquals(2, result!!.from)
        assertEquals(1, result.to)
        assertEquals((11..20).map { "uri-$it" } + (1..10).map { "uri-$it" }, moved.queueIds)
    }

    @Test
    fun aSharedCoordinatorGivesTheSameResultsAsFreshOnes() {
        // 播放页复用一个协调器以保留排序缓存；结果必须与每次新建一致
        val shared = PlaybackOrderUpdate.newCoordinator()
        val random = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.RANDOM, shared)
        val reloaded = PlaybackOrderUpdate.reload(random, changedCatalog, shared)
        val backToManual = PlaybackOrderUpdate.apply(reloaded, changedCatalog, PlayOrder.MANUAL, shared)
        val (moved, _) = PlaybackOrderUpdate.move(backToManual, changedCatalog, shared) { ordered ->
            moveItemToPosition(ordered.map { it.id }, ordered.last().id, 1)
        }

        val freshRandom = PlaybackOrderUpdate.apply(playedAscending, catalog, PlayOrder.RANDOM)
        val freshReloaded = PlaybackOrderUpdate.reload(freshRandom, changedCatalog)
        val freshManual = PlaybackOrderUpdate.apply(freshReloaded, changedCatalog, PlayOrder.MANUAL)
        val (freshMoved, _) = PlaybackOrderUpdate.move(freshManual, changedCatalog) { ordered ->
            moveItemToPosition(ordered.map { it.id }, ordered.last().id, 1)
        }

        assertEquals(freshMoved, moved)
    }
}
