package dev.abdus.apps.shimmer.p2

/**
 * CHG-P9-HISTORY-KEEPORDER-20261009：清空过去／前进分支，不等同于删除播放配置。
 *
 * 当前画面作为唯一历史基准保留：这样上一张不能回到清空前的图片，当前继承变换也不会在
 * 引擎重载或进程恢复后丢失。默认保留顺序模式及完整手动次序（包含暂时停用图片的位置），
 * 只重置随机轮次；明确勾选重置排序时改回名称升序。两种选择都不会请求切换当前画面。
 *
 * 此转换不接收生效目录，不能因暂时停用、撤权或日期规则而过滤用户保存的手动位置。
 * 来源续播点、独立图片调整和图片来源不属于“播放历史清空”的删除范围。
 */
fun clearedPlaybackHistory(
    latest: PlaybackSnapshot?,
    resetManualOrder: Boolean = false,
): PlaybackSnapshot {
    val previous = latest ?: PlaybackSnapshot()
    val current = previous.currentId ?: previous.historyIds.getOrNull(previous.historyCursor)
    val currentTransform = previous.currentEntryTransform(current)
    val order = if (resetManualOrder) PlaybackOrder.NATURAL_ASC else previous.order
    val keptQueue = if (!resetManualOrder && order == PlaybackOrder.MANUAL) previous.queueIds else emptyList()
    return previous.copy(
        order = order,
        queueIds = keptQueue,
        queueIndex = keptQueue.indexOf(current),
        historyIds = current?.let(::listOf).orEmpty(),
        historyCursor = if (current == null) -1 else 0,
        currentId = current,
        historyTransforms = currentTransform?.let(::listOf).orEmpty(),
        historySourceIds = previous.historySourceIds.getOrNull(previous.historyCursor)
            ?.takeIf { current != null && previous.historyIds.getOrNull(previous.historyCursor) == current && it.isNotEmpty() }
            ?.let(::listOf).orEmpty(),
        sourceHistoryCursors = if (current == null) emptyMap() else
            previous.sourcePositions.filterValues { it == current }.mapValues { 0 },
        randomRound = 0,
        previousRoundLastId = null,
        queueFingerprint = 0,
        // 避免同值清空放行之前的解码；极端计数上限回到 0，仍不同于上一次的值。
        historyResetSerial = if (previous.historyResetSerial == Long.MAX_VALUE) 0 else previous.historyResetSerial + 1,
    )
}
