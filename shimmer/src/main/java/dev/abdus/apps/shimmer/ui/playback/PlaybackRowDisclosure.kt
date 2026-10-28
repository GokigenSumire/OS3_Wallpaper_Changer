package dev.abdus.apps.shimmer.ui.playback

/**
 * P9-ROW-DISCLOSURE-01：图片行的展开状态只属于界面，不代表播放或手动调序选择。
 *
 * 按稳定图片 ID 保留一个操作区，不随当前位置或模式变化自动展开、定位；失活图片立即失去操作区。
 * 播放请求和调序请求仍由两个独立按钮发送，本层没有任何播放副作用。
 */
object PlaybackRowDisclosure {
    data class Actions(
        val showPlay: Boolean,
        val showReorderSelection: Boolean,
        val showMoveToPosition: Boolean,
    )

    fun retain(expandedId: String?, availableIds: Set<String>): String? =
        expandedId?.takeIf { it.isNotBlank() && it in availableIds }

    fun toggle(expandedId: String?, tappedId: String, availableIds: Set<String>): String? {
        val retained = retain(expandedId, availableIds)
        if (tappedId.isBlank() || tappedId !in availableIds) return retained
        return if (retained == tappedId) null else tappedId
    }

    fun actions(expanded: Boolean, manual: Boolean, selectedForReorder: Boolean): Actions = Actions(
        showPlay = expanded,
        showReorderSelection = expanded && manual,
        showMoveToPosition = expanded && manual && selectedForReorder,
    )
}
