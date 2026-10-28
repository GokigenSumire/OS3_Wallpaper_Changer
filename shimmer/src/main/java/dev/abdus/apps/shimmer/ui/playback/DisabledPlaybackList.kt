package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p3.DisabledImageEntry
import dev.abdus.apps.shimmer.p3.DisabledImageList

/** 只投影逐张停用的偏好，不把来源关闭、子目录排除或临时解码失败混为逐张停用。 */
data class DisabledPlaybackPage(
    val disabledIds: Set<String> = emptySet(),
    val entries: List<DisabledImageEntry> = emptyList(),
) {
    val totalCount: Int get() = disabledIds.size
    val remainingCount: Int get() = (totalCount - entries.size).coerceAtLeast(0)
}

/** 停用列表的分页、稳定标识与行适配。与有效队列分离，不写播放历史或手动位置。 */
object DisabledPlaybackList {
    const val PAGE_SIZE = 50
    const val TITLE_KEY = "disabled-images-title"
    const val MORE_KEY = "disabled-images-more"

    /** 先按不可变标识排序再分页；补读名称不会让旧页面跨页重新排序或出现重复。 */
    fun idsToLoad(disabledIds: Set<String>, visibleLimit: Int): List<String> =
        disabledIds.sorted().take(visibleLimit.coerceAtLeast(PAGE_SIZE))

    fun page(
        disabledIds: Set<String>,
        visibleLimit: Int,
        records: Map<String, Pair<String, String>>,
    ): DisabledPlaybackPage {
        val ids = idsToLoad(disabledIds, visibleLimit)
        val entries = DisabledImageList.entries(ids, records).associateBy { it.id }
        return DisabledPlaybackPage(disabledIds.toSet(), ids.map { entries.getValue(it) })
    }

    /** 独立键域，停用项不能与有效队列的 image|、目录／组键发生惰性列表碰撞。 */
    fun rowKey(id: String): String = "disabled|$id"

    fun row(entry: DisabledImageEntry, index: Int): QueueRow.Image = QueueRow.Image(
        key = rowKey(entry.id),
        item = PlaybackItem(id = entry.id, displayName = entry.name, relativePath = entry.detail),
        position = index + 1,
        depth = 0,
        isCurrent = false,
        moved = false,
        selected = false,
        canMoveUp = false,
        canMoveDown = false,
    )
}
