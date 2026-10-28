package dev.abdus.apps.shimmer.database

import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.CertifiedPlaybackItems
import java.util.Collections

/**
 * 两槽纯映射复用，不替代数据库／规则／偏好的实时读取。
 * 仅受控不可变投影身份和本次逐图停用集合的精确内容同时相同才能命中。
 * 普通／可变列表每次完整映射，不因同引用／相同数量／hash 被当成未变化。
 */
internal class P1PlaybackItemMappingMemo {
    private val lock = Any()
    private val entries = ArrayList<Entry>(2)

    fun map(images: List<P1PlaybackImage>, disabledIds: Set<String>): List<PlaybackItem> = synchronized(lock) {
        // 调用方的 Set 可能可变；保存前冻结本次精确集合，不保外部引用。
        val disabled = Collections.unmodifiableSet(HashSet(disabledIds))
        if (images !is P1ImmutablePlaybackImages) return@synchronized mapFresh(images, disabled)
        val index = entries.indexOfFirst { it.images === images && it.disabledIds == disabled }
        if (index >= 0) {
            val entry = entries.removeAt(index)
            entries.add(entry)
            return@synchronized entry.items
        }
        val items = mapFresh(images, disabled)
        entries.add(Entry(images, disabled, items))
        while (entries.size > 2) entries.removeAt(0)
        items
    }

    private fun mapFresh(images: List<P1PlaybackImage>, disabledIds: Set<String>): List<PlaybackItem> {
        val result = ArrayList<PlaybackItem>(images.size)
        for (image in images) {
            if (image.uri !in disabledIds) {
                result.add(
                    PlaybackItem(
                        id = image.uri,
                        displayName = image.displayName,
                        relativePath = image.relativePath,
                        sourceId = image.sourceId,
                        sourceOrder = image.sourceOrder,
                        sourceKind = image.sourceKind,
                        sourceRootUri = image.sourceRootUri,
                    ),
                )
            }
        }
        if (images is P1ImmutablePlaybackImages) return CertifiedPlaybackItems(result)
        return Collections.unmodifiableList(result)
    }

    private data class Entry(
        val images: P1ImmutablePlaybackImages,
        val disabledIds: Set<String>,
        val items: List<PlaybackItem>,
    )
}
