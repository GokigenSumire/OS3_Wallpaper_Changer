package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackSnapshot

/** 壁纸当前显示的图片：已提交的 currentId，缺失时取历史游标所指项。播放项身份即图片 URI。 */
fun PlaybackSnapshot.displayedItemId(): String? = currentId ?: historyIds.getOrNull(historyCursor)
