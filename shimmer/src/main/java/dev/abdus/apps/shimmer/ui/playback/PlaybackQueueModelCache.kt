package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder

/**
 * P9-QUEUE-CACHE-01：只缓存播放页的分组、目录树和位置表，不缓存来源授权或启停决定。
 *
 * 调用方每次仍须读取生效目录和已提交快照，并传入完整、最新的 [PlaybackItem] 列表。
 * 列表完整元数据及次序相等、排序类型不变时复用结构；只有当前图片变更时只复制当前标识。
 * 元数据变化不能仅凭 ID 集合或哈希判断，以免显示过期来源路径或随机轮次位置。
 *
 * 两个显示名称解析器在本缓存实例的生命周期内应保持同一规则；规则改变应新建缓存。
 * 缓存中只保存只读模型，不保存列表展开状态、历史、授权、时钟或 Android 对象。
 */
class PlaybackQueueModelCache(
    private val sourcePathOf: (PlaybackItem) -> String = { it.sourceRootUri.ifBlank { it.sourceId } },
    private val folderNameOf: (String) -> String? = { null },
) {
    private var cached: QueueModel? = null

    /** 较重的首次构建／失效重算仍应由调用方放在后台线程，缓存状态访问保持串行。 */
    @Synchronized
    fun resolve(ordered: List<PlaybackItem>, order: PlaybackOrder, currentId: String?): QueueModel {
        val previous = cached
        if (previous != null && previous.order == order && previous.ordered == ordered) {
            val validCurrent = currentId?.takeIf { it in previous.blockOf }
            return if (previous.currentId == validCurrent) {
                previous
            } else {
                previous.copy(currentId = validCurrent).also { cached = it }
            }
        }

        // 不持有调用方可能原地增删／换序的列表；PlaybackItem 本身只有不可变的值字段。
        val snapshot = ordered.toList()
        return buildQueueModel(snapshot, order, currentId, sourcePathOf, folderNameOf)
            .also { cached = it }
    }
}
