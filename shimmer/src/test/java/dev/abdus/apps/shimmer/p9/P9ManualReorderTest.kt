package dev.abdus.apps.shimmer.p9

import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.ui.playback.PlayOrder
import dev.abdus.apps.shimmer.ui.playback.PlaybackOrderUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P9-GALLERY-02 手动排序的契约。
 *
 * 用户要求"播放切至手动时手动排序，参考 p2 逻辑"，排序粒度按其 2026-10-06 的决定为单张图片。
 * 这里验证 [PlaybackOrderUpdate.reorder] 与旧 P2 完整播放控制页的 `moveItem` 行为一致：调用方算好
 * 整份新次序，协调器原样保留。
 */
class P9ManualReorderTest {

    private fun catalog(vararg ids: String): List<PlaybackItem> =
        ids.map { PlaybackItem(id = it, displayName = it) }

    private fun manualSnapshot(vararg ids: String) =
        PlaybackOrderUpdate.apply(null, catalog(*ids), PlayOrder.MANUAL)

    @Test
    fun manualOrderKeepsTheGivenSequence() {
        val items = catalog("a", "b", "c")
        val snapshot = PlaybackOrderUpdate.reorder(manualSnapshot("a", "b", "c"), items, listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), snapshot.queueIds)
        assertEquals(PlaybackOrder.MANUAL, snapshot.order)
    }

    /** 相邻交换是上移／下移的基本操作，与旧页 `moveItem(id, ±1)` 等价。 */
    @Test
    fun swappingAdjacentItemsMatchesLegacyMoveItem() {
        val items = catalog("a", "b", "c", "d")
        // 把 c 上移一位：a b c d -> a c b d
        val ids = listOf("a", "b", "c", "d").toMutableList()
        val from = ids.indexOf("c")
        val to = from - 1
        ids[from] = ids[to]
        ids[to] = "c"

        val snapshot = PlaybackOrderUpdate.reorder(manualSnapshot("a", "b", "c", "d"), items, ids)
        assertEquals(listOf("a", "c", "b", "d"), snapshot.queueIds)
    }

    /**
     * 目录新增图片后重排：ids 里缺的项按目录次序补在末尾，不丢图。
     *
     * 协调器的 `reorder` 要求 ids 与目录一一对应，否则抛 `IllegalArgumentException`；而调用方拿到的
     * 队列快照可能已过期（图片新增/删除）。[PlaybackOrderUpdate.reorder] 因此先对齐再交给协调器。
     */
    @Test
    fun itemsMissingFromTheGivenOrderAreAppended() {
        val items = catalog("a", "b", "c")
        // 只给出两项的次序，第三项 c 应被补回
        val snapshot = PlaybackOrderUpdate.reorder(manualSnapshot("a", "b", "c"), items, listOf("b", "a"))

        assertEquals(listOf("b", "a", "c"), snapshot.queueIds)
    }

    /** 已不在目录里的 ID 不应被带回队列，也不应让协调器的断言失败。 */
    @Test
    fun staleIdsAreDroppedWithoutThrowing() {
        val items = catalog("a", "b")
        val snapshot = PlaybackOrderUpdate.reorder(manualSnapshot("a", "b"), items, listOf("b", "gone", "a"))

        assertEquals(listOf("b", "a"), snapshot.queueIds)
    }

    /** 空次序不清空队列：目录项按自身次序全部补回。 */
    @Test
    fun emptyOrderFallsBackToCatalogOrder() {
        val items = catalog("a", "b")
        val snapshot = PlaybackOrderUpdate.reorder(manualSnapshot("a", "b"), items, emptyList())

        assertEquals(listOf("a", "b"), snapshot.queueIds)
    }

    /** 重复 ID 不应让一一对应的断言失败。 */
    @Test
    fun duplicateIdsAreDeduplicated() {
        val items = catalog("a", "b", "c")
        val snapshot = PlaybackOrderUpdate.reorder(
            manualSnapshot("a", "b", "c"),
            items,
            listOf("c", "c", "a", "b"),
        )

        assertEquals(listOf("c", "a", "b"), snapshot.queueIds)
    }
}
