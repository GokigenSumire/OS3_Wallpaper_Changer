package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p1.DirectoryTreeNode
import dev.abdus.apps.shimmer.p1.buildDirectoryTree

/** UI-only grouping model. It deliberately does not change the persisted playback order. */
data class PlaybackGroupKey(
    val kind: String,
    val sourceId: String,
)

data class PlaybackGroupUi(
    val key: PlaybackGroupKey,
    val items: List<PlaybackItem>,
    val previewItem: PlaybackItem,
    /** Directory sources carry a per-level tree; other kinds have no directory structure. */
    val tree: DirectoryTreeNode<PlaybackItem>?,
)

/**
 * Builds source groups in first-seen order. A directory source is turned into one node per
 * directory level, so a folder collapses as a whole and expanding it reveals only its own next
 * level. Grouping by level (instead of by contiguous runs of the play order) is also what keeps a
 * random round from showing every image as its own single-image directory.
 */
fun buildPlaybackGroups(items: List<PlaybackItem>): List<PlaybackGroupUi> {
    val grouped = LinkedHashMap<PlaybackGroupKey, MutableList<PlaybackItem>>()
    items.forEach { item ->
        grouped.getOrPut(PlaybackGroupKey(item.sourceKind, item.sourceId)) { mutableListOf() }.add(item)
    }
    return grouped.map { (key, groupItems) ->
        val sourceItems = groupItems.toList()
        val isDirectory = key.kind == "DIRECTORY_TREE"
        PlaybackGroupUi(
            key = key,
            items = sourceItems,
            previewItem = if (isDirectory) sourceItems.last() else sourceItems.first(),
            tree = if (isDirectory) buildDirectoryTree(sourceItems) { it.relativePath } else null,
        )
    }
}

/**
 * Whether the list groups a source by contiguous play segments or aggregates it as one block.
 *
 * Manual order is the only order in which the user deliberately splits a source, and the split is
 * what the 续段 marker reports. A random round interleaves sources on every position, so keeping
 * contiguous segments there would emit one header per image.
 */
fun groupsBySegment(order: PlaybackOrder): Boolean = order == PlaybackOrder.MANUAL

fun relativeDirectory(relativePath: String): String {
    val normalized = relativePath.replace('\\', '/').trim('/')
    val parent = normalized.substringBeforeLast('/', "")
    return parent.ifBlank { "根目录" }
}

fun shouldHighlightMovedRow(itemId: String, movedItemId: String?): Boolean =
    movedItemId != null && itemId == movedItemId
