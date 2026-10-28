package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p1.DIRECTORY_TREE_ROOT_NAME
import dev.abdus.apps.shimmer.p1.DirectoryTreeNode
import dev.abdus.apps.shimmer.p1.buildDirectoryTree
import dev.abdus.apps.shimmer.p1.directorySegments
import dev.abdus.apps.shimmer.p1.isWithinDirectory
import dev.abdus.apps.shimmer.p2.PlaybackGroupKey
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.buildPlaybackGroups
import dev.abdus.apps.shimmer.p2.groupsBySegment

/**
 * P9-ORDER-GROUP-01：播放页队列的分组模型。
 *
 * 用户在版本 39 反馈"直接按图片播放顺序单张展开，没有目录/组别分级, 组别调序, 调序也没有原来的视觉提示"。
 * 这里把旧 P2 完整播放控制页的 `renderBlocks`／`renderDirectoryLevel`／`moveGroup` 逐项搬成纯函数：
 *
 * - [buildQueueModel] 较重（分组、逐层目录树、位置表），上万张时在后台线程每次状态变化算一次；
 * - [buildQueueRows] 只遍历已展开的层级、每层最多显示已分页的条数，展开／折叠时在主线程重算，
 *   代价与可见行数成正比，不随图库总量增长。
 *
 * 不含 Android 依赖，单元测试直接覆盖。
 */

/** 一个分组段：非手动顺序下是整个来源；手动顺序下是播放次序里连续属于同一来源的一段。 */
data class QueueBlock(
    val group: PlaybackGroupKey,
    /** 该来源在列表里的组序号（从 1 开始），被拆开的来源各段共用。 */
    val groupIndex: Int,
    /** 同一来源的第几段（从 0 开始），用于行键和分页键。 */
    val segment: Int,
    val items: List<PlaybackItem>,
    /** 整个来源的图片数；来源被拆成多段时大于 [items] 的长度。 */
    val groupSize: Int,
    /** 同一来源的第二段及以后，标为续段。 */
    val continued: Boolean,
    val previewItem: PlaybackItem,
    /** 目录来源为逐层目录树；其他来源为只有一层、不含子目录的节点。 */
    val tree: DirectoryTreeNode<PlaybackItem>,
    val isDirectory: Boolean,
    /** 头部显示的来源位置，已格式化。 */
    val sourcePath: String,
    /** 目录来源最外层的名字。 */
    val rootName: String,
)

data class QueueModel(
    val order: PlaybackOrder,
    /** 完整播放次序，[moveItemIds] 等移动都基于它。 */
    val ordered: List<PlaybackItem>,
    val blocks: List<QueueBlock>,
    val groupCount: Int,
    /** 每张图片的播放位置（从 1 开始）。随机顺序下列表按来源分组，与播放先后不同，所以每行自带位置。 */
    val positions: Map<String, Int>,
    /** 每张图片所在的分组段下标。 */
    val blockOf: Map<String, Int>,
    val currentId: String?,
) {
    val manual: Boolean get() = order == PlaybackOrder.MANUAL
    val size: Int get() = ordered.size

    companion object {
        val EMPTY = QueueModel(PlaybackOrder.NATURAL_ASC, emptyList(), emptyList(), 0, emptyMap(), emptyMap(), null)
    }
}

/** 与旧页相同的分组规则；较重，放在后台线程调用。 */
fun buildQueueModel(
    ordered: List<PlaybackItem>,
    order: PlaybackOrder,
    currentId: String?,
    sourcePathOf: (PlaybackItem) -> String = { it.sourceRootUri.ifBlank { it.sourceId } },
    folderNameOf: (String) -> String? = { null },
): QueueModel {
    val segments = segmentsOf(ordered, groupsBySegment(order))
    val groupPositions = segments.map { it.first }.distinct()
        .withIndex().associate { (index, key) -> key to index + 1 }
    val groupSizes = ordered.groupingBy { it.groupKey() }.eachCount()
    val groupModels = buildPlaybackGroups(ordered).associateBy { it.key }
    val segmentCounts = HashMap<PlaybackGroupKey, Int>()
    val blocks = segments.map { (group, items) ->
        val segment = segmentCounts[group] ?: 0
        segmentCounts[group] = segment + 1
        val model = groupModels[group]
        val groupSize = groupSizes[group] ?: items.size
        val isDirectory = group.kind == DIRECTORY_KIND
        val first = items.first()
        QueueBlock(
            group = group,
            groupIndex = groupPositions.getValue(group),
            segment = segment,
            items = items,
            groupSize = groupSize,
            continued = segment > 0,
            previewItem = model?.previewItem ?: first,
            tree = when {
                !isDirectory -> flatNode(items)
                // 整个来源都在这一段，分组模型里的树可以直接用
                items.size == groupSize && model?.tree != null -> model.tree
                else -> buildDirectoryTree(items) { it.relativePath }
            },
            isDirectory = isDirectory,
            sourcePath = sourcePathOf(first),
            rootName = folderNameOf(first.sourceRootUri) ?: DIRECTORY_TREE_ROOT_NAME,
        )
    }
    val blockOf = HashMap<String, Int>(ordered.size * 2)
    blocks.forEachIndexed { index, block -> block.items.forEach { blockOf[it.id] = index } }
    return QueueModel(
        order = order,
        ordered = ordered,
        blocks = blocks,
        groupCount = groupPositions.size,
        positions = ordered.withIndex().associate { (index, item) -> item.id to index + 1 },
        blockOf = blockOf,
        currentId = currentId?.takeIf { it in blockOf },
    )
}

/** 只有手动顺序按连续段分组；随机轮次几乎每个位置都换来源，按段分组会让每张图各占一个头部。 */
private fun segmentsOf(ordered: List<PlaybackItem>, bySegment: Boolean): List<Pair<PlaybackGroupKey, List<PlaybackItem>>> {
    if (!bySegment) {
        val grouped = LinkedHashMap<PlaybackGroupKey, MutableList<PlaybackItem>>()
        ordered.forEach { grouped.getOrPut(it.groupKey()) { mutableListOf() }.add(it) }
        return grouped.map { (group, items) -> group to items }
    }
    val segments = mutableListOf<Pair<PlaybackGroupKey, List<PlaybackItem>>>()
    var index = 0
    while (index < ordered.size) {
        val group = ordered[index].groupKey()
        val start = index
        while (index < ordered.size && ordered[index].groupKey() == group) index++
        segments += group to ordered.subList(start, index)
    }
    return segments
}

private fun flatNode(items: List<PlaybackItem>) = DirectoryTreeNode(
    path = "",
    name = DIRECTORY_TREE_ROOT_NAME,
    depth = 0,
    directItems = items,
    children = emptyList(),
    totalCount = items.size,
)

fun PlaybackItem.groupKey(): PlaybackGroupKey = PlaybackGroupKey(sourceKind, sourceId)

/** 图片在来源内所在的目录层，`/` 分隔；位于来源根目录时为空串。 */
fun PlaybackItem.directoryPath(): String = directorySegments(relativePath).joinToString("/")

/** 列表里的一行。 */
sealed interface QueueRow {
    /** LazyColumn 的稳定键；同一张图片、同一层级在重绘之间保持不变。 */
    val key: String

    data class GroupHeader(
        override val key: String,
        val blockIndex: Int,
        val group: PlaybackGroupKey,
        val groupIndex: Int,
        val groupCount: Int,
        val itemCount: Int,
        val sourcePath: String,
        val continued: Boolean,
        val previewItem: PlaybackItem,
        val containsCurrent: Boolean,
        val highlighted: Boolean,
        val canMoveUp: Boolean,
        val canMoveDown: Boolean,
    ) : QueueRow

    /** 一个可折叠层级：目录来源的每一层，或非目录来源的"本组图片"。 */
    data class Level(
        override val key: String,
        val levelKey: String,
        val label: String,
        val depth: Int,
        val totalCount: Int,
        val expanded: Boolean,
        val containsCurrent: Boolean,
    ) : QueueRow

    data class Image(
        override val key: String,
        val item: PlaybackItem,
        val position: Int,
        /** 缩进层级，与所在目录头部对齐后再进一级。 */
        val depth: Int,
        val isCurrent: Boolean,
        /** 旧页的"刚移动"高亮。 */
        val moved: Boolean,
        val selected: Boolean,
        val canMoveUp: Boolean,
        val canMoveDown: Boolean,
    ) : QueueRow

    data class More(
        override val key: String,
        val pageKey: String,
        val remaining: Int,
        val depth: Int,
    ) : QueueRow
}

/** 只属于界面的列表状态：展开了哪些层、每层显示了几页、哪张刚移动、哪张被选定。 */
data class QueueViewState(
    val expanded: Set<String> = emptySet(),
    val visibleRows: Map<String, Int> = emptyMap(),
    val movedItemId: String? = null,
    val selectedItemId: String? = null,
    /** 单选对象独立保留；结束多选仅清下列会话字段，不清单选。 */
    val multiReorderEnabled: Boolean = false,
    val multiSelectedIds: Set<String> = emptySet(),
    val movedItemIds: Set<String> = emptySet(),
    /** 刚整组移动的来源，头部高亮。 */
    val movedGroup: PlaybackGroupKey? = null,
) {
    fun visible(pageKey: String): Int = visibleRows[pageKey] ?: QueuePageSize

    fun toggle(levelKey: String): QueueViewState =
        copy(expanded = if (levelKey in expanded) expanded - levelKey else expanded + levelKey)

    fun showMore(pageKey: String): QueueViewState =
        copy(visibleRows = visibleRows + (pageKey to visible(pageKey) + QueuePageSize))
}

/** 层级键：同一来源各段共用，所以一段展开后另一段同一目录也展开，与旧页一致。 */
fun levelKey(group: PlaybackGroupKey, path: String): String = "${group.kind}|${group.sourceId}|$path"

/** 分页键：按段区分，否则拆开的两段会共用一个"已显示条数"。 */
fun pageKey(group: PlaybackGroupKey, segment: Int, path: String): String =
    "${levelKey(group, path)}#$segment"

/** 生成可见行。只遍历已展开的层，折叠的层不产生任何图片行。 */
fun buildQueueRows(model: QueueModel, view: QueueViewState): List<QueueRow> {
    if (model.blocks.isEmpty()) return emptyList()
    val rows = ArrayList<QueueRow>()
    val current = model.currentId
    val currentBlock = current?.let { model.blockOf[it] }
    val currentPath = currentBlock?.let { index ->
        model.blocks[index].items.firstOrNull { it.id == current }?.directoryPath()
    }
    model.blocks.forEachIndexed { blockIndex, block ->
        val holdsCurrent = currentBlock == blockIndex
        rows += QueueRow.GroupHeader(
            key = groupRowKey(block),
            blockIndex = blockIndex,
            group = block.group,
            groupIndex = block.groupIndex,
            groupCount = model.groupCount,
            itemCount = block.groupSize,
            sourcePath = block.sourcePath,
            continued = block.continued,
            previewItem = block.previewItem,
            containsCurrent = holdsCurrent,
            highlighted = view.movedGroup == block.group,
            canMoveUp = model.manual && block.groupIndex > 1,
            canMoveDown = model.manual && block.groupIndex < model.groupCount,
        )
        // 单张图片的来源没有可折叠的内容，直接显示那一行
        if (!block.isDirectory && block.items.size == 1) {
            addImages(rows, model, view, block, block.items, path = "", depth = 0)
        } else {
            addLevel(rows, model, view, block, block.tree, holdsCurrent, currentPath)
        }
    }
    return rows
}

private fun addLevel(
    rows: MutableList<QueueRow>,
    model: QueueModel,
    view: QueueViewState,
    block: QueueBlock,
    node: DirectoryTreeNode<PlaybackItem>,
    holdsCurrent: Boolean,
    currentPath: String?,
) {
    val key = levelKey(block.group, node.path)
    val expanded = key in view.expanded
    rows += QueueRow.Level(
        key = "level|$key#${block.segment}",
        levelKey = key,
        label = when {
            !block.isDirectory -> "本组图片"
            node.depth == 0 -> "来源目录：${block.rootName}/"
            else -> "子目录：${node.name}/"
        },
        depth = node.depth,
        totalCount = node.totalCount,
        expanded = expanded,
        containsCurrent = holdsCurrent && currentPath != null && isWithinDirectory(currentPath, node.path),
    )
    if (!expanded) return
    addImages(rows, model, view, block, node.directItems, node.path, node.depth + 1)
    node.children.forEach { child -> addLevel(rows, model, view, block, child, holdsCurrent, currentPath) }
}

private fun addImages(
    rows: MutableList<QueueRow>,
    model: QueueModel,
    view: QueueViewState,
    block: QueueBlock,
    items: List<PlaybackItem>,
    path: String,
    depth: Int,
) {
    if (items.isEmpty()) return
    val page = pageKey(block.group, block.segment, path)
    val shown = minOf(items.size, view.visible(page))
    for (offset in 0 until shown) {
        val item = items[offset]
        val position = model.positions[item.id] ?: (offset + 1)
        rows += QueueRow.Image(
            key = imageRowKey(item.id),
            item = item,
            position = position,
            depth = depth,
            isCurrent = item.id == model.currentId,
            moved = item.id == view.movedItemId || item.id in view.movedItemIds,
            selected = model.manual && if (view.multiReorderEnabled) item.id in view.multiSelectedIds else item.id == view.selectedItemId,
            canMoveUp = model.manual && position > 1,
            canMoveDown = model.manual && position < model.size,
        )
    }
    if (shown < items.size) {
        rows += QueueRow.More(key = "more|$page", pageKey = page, remaining = items.size - shown, depth = depth)
    }
}

fun imageRowKey(itemId: String): String = "image|$itemId"

fun groupRowKey(block: QueueBlock): String = "group|${block.group.kind}|${block.group.sourceId}#${block.segment}"

/**
 * 展开 [itemId] 以上的每一层，并把它所在层分页到能显示它为止；与旧页 `expandToItem` 等价。
 * 图片不在列表里时原样返回。
 */
fun QueueViewState.reveal(model: QueueModel, itemId: String): QueueViewState {
    val blockIndex = model.blockOf[itemId] ?: return this
    val block = model.blocks[blockIndex]
    if (!block.isDirectory && block.items.size == 1) return this
    val item = block.items.first { it.id == itemId }
    val opened = expanded.toMutableSet()
    opened += levelKey(block.group, "")
    var node = block.tree
    var path = ""
    if (block.isDirectory) {
        for (segment in directorySegments(item.relativePath)) {
            path = if (path.isEmpty()) segment else "$path/$segment"
            opened += levelKey(block.group, path)
            node = node.children.firstOrNull { it.path == path } ?: break
        }
    }
    val offset = node.directItems.indexOfFirst { it.id == itemId }
    if (offset < 0) return copy(expanded = opened)
    val page = pageKey(block.group, block.segment, node.path)
    // 显示到目标所在那一页的末尾，目标之后的几张也能看到
    val needed = (offset / QueuePageSize + 1) * QueuePageSize
    return copy(expanded = opened, visibleRows = visibleRows + (page to maxOf(visible(page), needed)))
}

/** 载入完成后要定位的目标。 */
sealed interface FocusTarget {
    /** 当前播放的图片：首次进入播放页、切换顺序、点"定位当前"时。 */
    data object Current : FocusTarget
    data class Item(val itemId: String) : FocusTarget
    data class Group(val group: PlaybackGroupKey) : FocusTarget
}

/**
 * 进入播放页时的定位目标：已有待定位的操作优先；只在本次会话首次进入时默认定位当前。
 *
 * 用户 2026-10-09 改为保存浏览位置，替代版本 41 的每次进入定位契约。再次进入无待处理操作
 * 时返回 null，调用方不得展开／发出滚动请求；列表位置由导航的保存容器负责。
 */
fun entryFocus(pending: FocusTarget?, hasEntered: Boolean): FocusTarget? =
    pending ?: if (hasEntered) null else FocusTarget.Current

/** 一次定位：展开后的列表状态，以及要滚到的行；目标不在列表里时 [rowKey] 为 null。 */
data class QueueFocus(val view: QueueViewState, val rowKey: String?)

/** 展开到 [target] 所在层并给出要滚到的行。当前图片已停用或还未开始播放时不滚动。 */
fun QueueViewState.focus(model: QueueModel, target: FocusTarget): QueueFocus = when (target) {
    FocusTarget.Current -> model.currentId?.let { focusItem(model, it) } ?: QueueFocus(this, null)
    is FocusTarget.Item -> focusItem(model, target.itemId)
    is FocusTarget.Group -> QueueFocus(this, model.blocks.firstOrNull { it.group == target.group }?.let(::groupRowKey))
}

private fun QueueViewState.focusItem(model: QueueModel, itemId: String): QueueFocus =
    if (itemId in model.blockOf) QueueFocus(reveal(model, itemId), imageRowKey(itemId)) else QueueFocus(this, null)

/** 一次移动的结果：新的完整次序，以及移动前后的位置（从 1 开始）。 */
data class QueueMove(val ids: List<String>, val from: Int, val to: Int)

/** 与旧页 `moveItem` 相同：与相邻一张交换。越界时返回 null。 */
fun moveItemIds(ordered: List<String>, itemId: String, delta: Int): QueueMove? {
    val from = ordered.indexOf(itemId)
    val to = from + delta
    if (from < 0 || to !in ordered.indices || delta == 0) return null
    val ids = ordered.toMutableList()
    ids[from] = ids[to]
    ids[to] = itemId
    return QueueMove(ids, from + 1, to + 1)
}

/** 选定一张后“调整播放位置”：取出再插入，其他图片相对顺序不变。 */
fun moveItemToPosition(ordered: List<String>, itemId: String, position: Int): QueueMove? {
    val from = ordered.indexOf(itemId)
    if (from < 0 || position !in 1..ordered.size || position == from + 1) return null
    val ids = ordered.toMutableList()
    ids.removeAt(from)
    ids.add(position - 1, itemId)
    return QueueMove(ids, from + 1, position)
}

/**
 * 与旧页 `moveGroup` 相同：把该来源的全部图片聚合，移到相邻来源全部图片之前或之后。
 * 组内顺序及其他图片的相对顺序保持。返回的位置是组序号。
 */
fun moveGroupIds(ordered: List<PlaybackItem>, group: PlaybackGroupKey, delta: Int): QueueMove? {
    if (delta != -1 && delta != 1) return null
    val groupOrder = ordered.map { it.groupKey() }.distinct()
    val from = groupOrder.indexOf(group)
    val target = from + delta
    if (from < 0 || target !in groupOrder.indices) return null
    val other = groupOrder[target]
    val block = ordered.filter { it.groupKey() == group }
    val remaining = ordered.filterNot { it.groupKey() == group }.toMutableList()
    val insertAt = if (delta < 0) {
        remaining.indexOfFirst { it.groupKey() == other }
    } else {
        remaining.indexOfLast { it.groupKey() == other } + 1
    }
    remaining.addAll(insertAt, block)
    val movedTo = remaining.map { it.groupKey() }.distinct().indexOf(group) + 1
    return QueueMove(remaining.map { it.id }, from + 1, movedTo)
}

/** “目标播放序号”输入框：只接受 1..[size] 的整数，其余返回 null 并由界面提示，不静默截断。 */
fun parseQueuePosition(input: String, size: Int): Int? =
    input.trim().toIntOrNull()?.takeIf { it in 1..size }

private const val DIRECTORY_KIND = "DIRECTORY_TREE"
