package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot

/** 页面结构只依赖排序配置；当前、历史及来源点通知不能每次触发整库读取与分组。 */
internal fun samePlaybackQueueConfiguration(left: PlaybackSnapshot?, right: PlaybackSnapshot?): Boolean {
    val leftOrder = left?.order ?: PlaybackOrder.NATURAL_ASC
    val rightOrder = right?.order ?: PlaybackOrder.NATURAL_ASC
    if (leftOrder != rightOrder) return false
    return when (leftOrder) {
        PlaybackOrder.NATURAL_ASC, PlaybackOrder.NATURAL_DESC -> true
        PlaybackOrder.MANUAL -> left?.queueIds.orEmpty() == right?.queueIds.orEmpty()
        PlaybackOrder.RANDOM -> left?.randomRound == right?.randomRound &&
            left?.previousRoundLastId == right?.previousRoundLastId &&
            left?.queueFingerprint == right?.queueFingerprint
    }
}

/** 失败提示与成功记录都属于发起时的完整快照；通知尚未执行也不能让旧读覆盖新状态。 */
internal fun currentReadMayPublish(serial: Long, currentSerial: Long, requested: PlaybackSnapshot?, latest: PlaybackSnapshot?): Boolean =
    serial == currentSerial && requested == latest
