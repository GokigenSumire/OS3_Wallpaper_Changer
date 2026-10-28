package dev.abdus.apps.shimmer.ui.playback

/** 多选只描述一次临时移动，不建立永久图片组，也不按勾选先后改变块内顺序。 */
object PlaybackMultiReorderPolicy {
    data class Block(
        val ids: List<String>,
        val firstPosition: Int,
        val maximumStart: Int,
        val contiguous: Boolean,
    )

    /** 任何选中项已失活都拒绝整块；不得静默把剩余项当作用户原来的选择。 */
    fun block(ordered: List<String>, selected: Set<String>): Block? {
        if (selected.isEmpty() || selected.any(String::isBlank)) return null
        val positions = ordered.withIndex().filter { it.value in selected }
        if (positions.size != selected.size || positions.map { it.value }.toSet().size != selected.size) return null
        val first = positions.first().index
        return Block(
            ids = positions.map { it.value },
            firstPosition = first + 1,
            maximumStart = ordered.size - positions.size + 1,
            contiguous = positions.last().index - first + 1 == positions.size,
        )
    }

    /** 以第一张选中图片原位置为块起点，整体上／下移动一位；只有 ±1 是合法方向。 */
    fun move(ordered: List<String>, selected: Set<String>, delta: Int): QueueMove? {
        if (delta != -1 && delta != 1) return null
        val block = block(ordered, selected) ?: return null
        return moveTo(ordered, selected, block.firstPosition + delta)
    }

    /** 目标是移动后块首图的播放序号；同起点的非连续选项仍可发生真实聚合。 */
    fun moveTo(ordered: List<String>, selected: Set<String>, position: Int): QueueMove? {
        val block = block(ordered, selected) ?: return null
        if (position !in 1..block.maximumStart) return null
        val remaining = ordered.filterNot { it in selected }.toMutableList()
        remaining.addAll(position - 1, block.ids)
        if (remaining == ordered) return null
        return QueueMove(remaining, block.firstPosition, position)
    }

    /** 只计算展示；缺失 ID 仍保留在原会话选择中，实际移动必须重新检查真实目录。 */
    fun state(enabled: Boolean, selected: Set<String>, positions: Map<String, Int>, size: Int): MultiReorderUiState {
        if (!enabled) return MultiReorderUiState()
        val visible = selected.filterTo(linkedSetOf()) { it in positions }
        val unavailable = selected.size - visible.size
        val first = visible.minOfOrNull { positions.getValue(it) }
        val maximum = size - visible.size + 1
        return MultiReorderUiState(
            enabled = true,
            selectedIds = visible,
            unavailableCount = unavailable,
            firstPosition = first,
            maximumStart = if (visible.isEmpty()) 0 else maximum,
            canMoveUp = unavailable == 0 && first != null && first > 1,
            canMoveDown = unavailable == 0 && first != null && first < maximum,
        )
    }
}

/** 仅属于页面；不会序列化到播放快照。失活选择通过数量明确提示，不把它重新加入队列。 */
data class MultiReorderUiState(
    val enabled: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val unavailableCount: Int = 0,
    val firstPosition: Int? = null,
    val maximumStart: Int = 0,
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
) {
    val canMoveTo: Boolean get() = selectedIds.isNotEmpty() && unavailableCount == 0
}

internal class UnavailableMultiReorderSelection : IllegalStateException("所选图片已失活")
