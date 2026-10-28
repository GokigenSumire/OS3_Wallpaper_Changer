package dev.abdus.apps.shimmer.ui.gallery

/** 多选组的逐张控制以图片 URI 为键，与壁纸的 disabled_images 存储保持一致。 */
data class GalleryBatchImage(val uri: String, val name: String)

/** 只属于当前界面的折叠与页码，不改变关联顺序或播放快照。 */
data class GalleryBatchViewState(val expanded: Boolean = false, val pageIndex: Int = 0) {
    fun toggle(): GalleryBatchViewState = copy(expanded = !expanded)

    fun movePage(delta: Int, totalCount: Int): GalleryBatchViewState {
        val lastPage = GalleryBatchRows.pageCount(totalCount) - 1
        val next = (pageIndex.toLong() + delta).coerceIn(0L, lastPage.coerceAtLeast(0).toLong())
        return copy(pageIndex = next.toInt())
    }
}

data class GalleryBatchImageRow(
    val uri: String,
    val name: String,
    val position: Int,
    /** 自身记录，不把父来源停用写入每张图片的全局停用集合。 */
    val enabled: Boolean,
    val canToggle: Boolean,
    val stateLabel: String,
)

data class GalleryBatchPage(
    val expanded: Boolean = false,
    val totalCount: Int = 0,
    val stoppedCount: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val rows: List<GalleryBatchImageRow> = emptyList(),
)

/** 默认折叠；每页最多 50 行，即使全部图片很多也不把整组一次铺开。 */
object GalleryBatchRows {
    const val PAGE_SIZE = 50

    fun pageCount(totalCount: Int): Int =
        if (totalCount <= 0) 0 else ((totalCount.toLong() + PAGE_SIZE - 1) / PAGE_SIZE).toInt()

    fun page(
        images: List<GalleryBatchImage>,
        sourceEnabled: Boolean,
        disabled: Set<String>,
        view: GalleryBatchViewState,
    ): GalleryBatchPage {
        val count = pageCount(images.size)
        val index = view.pageIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
        val start = index * PAGE_SIZE
        return GalleryBatchPage(
            expanded = view.expanded,
            totalCount = images.size,
            stoppedCount = images.count { it.uri in disabled },
            pageIndex = index,
            pageCount = count,
            rows = if (!view.expanded) emptyList() else images.subList(start, start + (images.size - start).coerceAtMost(PAGE_SIZE)).mapIndexed { offset, image ->
                val stopped = image.uri in disabled
                GalleryBatchImageRow(
                    uri = image.uri,
                    name = image.name,
                    position = start + offset + 1,
                    enabled = !stopped,
                    canToggle = sourceEnabled,
                    stateLabel = when {
                        !sourceEnabled && stopped -> "受来源停用影响（本图也已停用）"
                        !sourceEnabled -> "受来源停用影响"
                        stopped -> "已停用"
                        else -> "播放中"
                    },
                )
            },
        )
    }
}
