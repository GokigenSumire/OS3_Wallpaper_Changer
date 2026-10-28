package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot

/**
 * P8 播放页的顺序切换，与 P2 完整播放控制页走同一条协调器路径。
 *
 * 只改快照的 order 字段是不够的：随机顺序会沿用旧的有序队列、且没有生成本轮，
 * 自然顺序也不会按新方向重排。这里先把当前目录交给协调器，再在最新快照上切换顺序，
 * 历史、当前图片和各来源的继续位置都保留。
 */
object PlaybackOrderUpdate {
    /** 与 P2 页、壁纸服务相同的随机种子；种子不同会让同一轮随机顺序在两处不一致。 */
    const val RANDOM_SEED = 0x504C41594241434BL

    fun toPlaybackOrder(order: PlayOrder): PlaybackOrder = when (order) {
        PlayOrder.NATURAL_ASC -> PlaybackOrder.NATURAL_ASC
        PlayOrder.NATURAL_DESC -> PlaybackOrder.NATURAL_DESC
        PlayOrder.RANDOM -> PlaybackOrder.RANDOM
        PlayOrder.MANUAL -> PlaybackOrder.MANUAL
    }

    fun fromPlaybackOrder(order: PlaybackOrder?): PlayOrder = when (order) {
        null, PlaybackOrder.NATURAL_ASC -> PlayOrder.NATURAL_ASC
        PlaybackOrder.NATURAL_DESC -> PlayOrder.NATURAL_DESC
        PlaybackOrder.RANDOM -> PlayOrder.RANDOM
        PlaybackOrder.MANUAL -> PlayOrder.MANUAL
    }

    /** 先保存选择，目录／排序展示另行重建；不提前改变真实当前图片、历史或各根保存点。 */
    fun select(latest: PlaybackSnapshot?, order: PlayOrder, manualSeed: List<String> = emptyList()): PlaybackSnapshot {
        val saved = latest ?: PlaybackSnapshot()
        val requested = toPlaybackOrder(order)
        if (saved.order == requested) return saved
        // 新进入手动使用页面实际已显示的顺序，作为用户配置而非来源授权缓存。
        // 已为手动的最新快照上面直接返回，不能被过期页面种子覆盖。
        val queue = if (requested == PlaybackOrder.MANUAL) manualSeed.filter { it.isNotBlank() }.distinct() else emptyList()
        return saved.copy(
            order = requested,
            // 自然／随机的队列是派生数据；手动同模式重选上面已直接返回，不丢隐藏位置。
            queueIds = queue,
            queueIndex = saved.currentId?.let(queue::indexOf) ?: -1,
            queueFingerprint = if (requested == PlaybackOrder.RANDOM) 0 else saved.queueFingerprint,
            forwardHistoryInvalidated = true,
        )
    }

    /** 在 [latest]（可能为空，即首次使用）上切换到 [order]，返回应保存的快照。 */
    fun apply(
        latest: PlaybackSnapshot?,
        catalog: List<PlaybackItem>,
        order: PlayOrder,
        coordinator: PlaybackCoordinator = newCoordinator(),
    ): PlaybackSnapshot =
        restored(latest, catalog, coordinator).apply { setOrder(toPlaybackOrder(order)) }.snapshot()

    /**
     * 重新载入来源：把最新目录交给协调器，顺序不变，与 P2 页“重新载入”相同。
     * 已删除或停用的图片移出队列，新增的图片按当前顺序补入；历史与当前图片保留。
     */
    fun reload(
        latest: PlaybackSnapshot?,
        catalog: List<PlaybackItem>,
        coordinator: PlaybackCoordinator = newCoordinator(),
    ): PlaybackSnapshot =
        restored(latest, catalog, coordinator).snapshot()

    /**
     * P9-GALLERY-02：手动顺序下按给定的完整 ID 次序重排。
     *
     * 与旧 P2 完整播放控制页的 `moveItem` 走同一条路径——调用方算好整份新次序，这里只交给协调器。
     *
     * **协调器的 `reorder` 要求 ids 与当前目录一一对应**（`require(ids.size == itemsById.size &&
     * ids.toSet() == itemsById.keys)`），不满足就抛 `IllegalArgumentException`。队列与目录在两次
     * 读取之间可能已经不同（图片被删、来源被停用、目录新增图片），所以这里先对齐再交给协调器：
     * 丢掉目录里已没有的 ID、补上 ids 里缺失的目录项（按目录自身次序附在末尾）。
     *
     * 这样做不是为了绕过断言，而是因为"调用方拿到的队列快照已过期"是正常情形，不该让用户看到崩溃。
     */
    fun reorder(
        latest: PlaybackSnapshot?,
        catalog: List<PlaybackItem>,
        ids: List<String>,
        coordinator: PlaybackCoordinator = newCoordinator(),
    ): PlaybackSnapshot {
        val catalogIds = catalog.map { it.id }
        val known = catalogIds.toSet()
        val requested = ids.filter { it in known }.distinct()
        val missing = catalogIds.filter { it !in requested }
        val aligned = requested + missing
        return restored(latest, catalog, coordinator).apply { reorder(aligned) }.snapshot()
    }

    /**
     * P9-ORDER-GROUP-01：在最新快照上计算一次手动移动并保存。
     *
     * 与旧页 `moveItem`／`moveGroup` 一样，移动基于存储锁内读到的最新次序，而不是界面手里的旧副本：
     * 壁纸可能在页面打开期间提交过新图片或别处改过顺序。[move] 返回 null 表示无可移动（已在首尾、
     * 目标不存在），非手动顺序同样不移动；这两种情况返回原快照，[PlaybackSnapshotStore.update]
     * 比较后不会写入——"什么都没移动"不应顺带把目录对齐结果写进去。
     */
    fun move(
        latest: PlaybackSnapshot?,
        catalog: List<PlaybackItem>,
        coordinator: PlaybackCoordinator = newCoordinator(),
        move: (List<PlaybackItem>) -> QueueMove?,
    ): Pair<PlaybackSnapshot, QueueMove?> {
        val restored = restored(latest, catalog, coordinator)
        val unchanged = latest ?: restored.snapshot()
        if (restored.snapshot().order != PlaybackOrder.MANUAL) return unchanged to null
        val result = move(restored.orderedItems()) ?: return unchanged to null
        return reorder(latest, catalog, result.ids, coordinator) to result
    }

    /** 与 P2 页、壁纸服务相同种子的协调器；需要复用排序缓存的调用方自己持有一个并传入。 */
    fun newCoordinator(): PlaybackCoordinator = PlaybackCoordinator(randomSeed = RANDOM_SEED)

    /**
     * 当前目录与快照在协调器中的结果，只读不写。
     * 播放页用它取完整播放次序；随机轮次尚未生成时为空，与旧 P2 页一致。
     */
    fun restored(
        latest: PlaybackSnapshot?,
        catalog: List<PlaybackItem>,
        coordinator: PlaybackCoordinator = newCoordinator(),
    ): PlaybackCoordinator = coordinator.apply {
        setItems(catalog)
        restore(latest ?: PlaybackSnapshot())
    }
}
