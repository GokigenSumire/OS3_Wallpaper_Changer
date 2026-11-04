package dev.abdus.apps.shimmer.media

import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Visible state only. No URI, file name, or private video content is retained here. */
object MediaPlaybackStatus {
    data class Entry(val kind: MediaKind, val frame: MediaFrameState?) {
        fun label(): String = when (frame) {
            null -> "图片"
            MediaFrameState.PREPARING -> "正在准备动态画面"
            MediaFrameState.VIDEO -> if (kind == MediaKind.MOTION_PHOTO) "已显示动态照片的视频帧" else "已显示视频帧"
            MediaFrameState.STILL -> "显示原始照片"
            MediaFrameState.ENDED -> "播放完毕，显示原始照片"
            MediaFrameState.FALLBACK_HDR -> "HDR动态输出暂不支持，显示封面"
            MediaFrameState.FALLBACK_BUSY -> "播放资源繁忙，显示封面"
            MediaFrameState.FALLBACK_TIMEOUT -> "准备超时，显示封面"
            MediaFrameState.FALLBACK_ERROR -> "动态部分无法播放，显示封面"
        }
    }
    private val mutable = MutableStateFlow<Map<WallpaperTarget, Entry>>(emptyMap())
    val states: StateFlow<Map<WallpaperTarget, Entry>> = mutable
    @Synchronized fun record(target: WallpaperTarget, entry: Entry) {
        if (mutable.value[target] != entry) mutable.value = mutable.value + (target to entry)
    }
}
