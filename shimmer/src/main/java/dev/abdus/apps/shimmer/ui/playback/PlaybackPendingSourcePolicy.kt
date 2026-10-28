package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackCoordinator

/**
 * 页面专用协调器已按本轮真实目录与快照还原。只查看 Engine 将恢复的唯一根候选，
 * 不提交、不执行 NEXT、不改正式 current 或历史；此处 provisional 请求仅属于页面实例。
 *
 * 首次根无保存点时沿当前真实队列首项；混合根、空集、当前仍有效、未知来源身份及尚无
 * 真实当前图时均保持无候选。可读性仍由返回桌面后的 Engine 真实解码判定。
 */
internal fun pendingSourceResumeId(coordinator: PlaybackCoordinator): String? =
    if (coordinator.sourceRestoreNeeded()) coordinator.requestSourceRestore()?.item?.id else null
