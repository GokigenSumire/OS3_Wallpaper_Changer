package dev.abdus.apps.shimmer.p5

import kotlinx.serialization.Serializable

/** The effect the user picks (REQ-FX-01). [RANDOM] resolves to one [TransitionEffect] per switch. */
@Serializable
enum class TransitionChoice { NONE, CROSSFADE, FADE_THROUGH, SLIDE, ZOOM, RANDOM }

/** What the renderer actually draws for one switch. */
enum class TransitionEffect {
    NONE, CROSSFADE, FADE_THROUGH, SLIDE, ZOOM;

    companion object {
        /** The pool [TransitionChoice.RANDOM] draws from; [NONE] is never picked at random. */
        val ANIMATED = listOf(CROSSFADE, FADE_THROUGH, SLIDE, ZOOM)
    }
}

/** Slide setting. [FOLLOW] uses the playback direction: next enters from the right. */
@Serializable
enum class SlideDirectionChoice { FOLLOW, LEFT, RIGHT, UP, DOWN, RANDOM }

/**
 * Direction the content moves, as a unit step in GL window coordinates (y up). [LEFT] moves the
 * old image out to the left while the new one enters from the right.
 */
enum class SlideDirection(val dx: Int, val dy: Int) {
    LEFT(-1, 0), RIGHT(1, 0), UP(0, 1), DOWN(0, -1)
}

/** One resolved switch: effect, linear duration and (for [TransitionEffect.SLIDE]) direction. */
data class TransitionSpec(
    val effect: TransitionEffect,
    val durationMillis: Int,
    val direction: SlideDirection = SlideDirection.LEFT,
) {
    /** Whether a frame is ever drawn with both images; false means an instant swap. */
    val animated: Boolean get() = effect != TransitionEffect.NONE && durationMillis > 0

    companion object {
        val NONE = TransitionSpec(TransitionEffect.NONE, 0)
        fun crossfade(durationMillis: Int) = TransitionSpec(TransitionEffect.CROSSFADE, durationMillis)
    }
}

@Serializable
data class TransitionSettings(
    val choice: TransitionChoice = TransitionChoice.CROSSFADE,
    val durationMillis: Int = DEFAULT_DURATION_MILLIS,
    val direction: SlideDirectionChoice = SlideDirectionChoice.FOLLOW,
) {
    /**
     * 把存储或编辑中的时长收敛到受支持范围。
     *
     * P9-EFFECTS-02 之前这里吸附到最近的预设值，新 UI 的 1 ms 无极滑块因此无法保存任意时长。
     * 现在只做区间夹取：[MIN_DURATION_MILLIS]～[MAX_DURATION_MILLIS] 内的任何整数都原样保留，
     * 越界值夹到边界而不是跳到某个预设。预设仍保留给旧 P5 页的快捷单选用。
     */
    fun normalized(): TransitionSettings = copy(durationMillis = clampDuration(durationMillis))

    companion object {
        const val DEFAULT_DURATION_MILLIS = 1500

        /** P9 计划冻结的范围：下限以下过渡不可辨，上限留余量。 */
        const val MIN_DURATION_MILLIS = 100
        const val MAX_DURATION_MILLIS = 5000

        /** 旧 P5 页的快捷选项；全部落在受支持范围内，升级后用户原设置原样保留。 */
        val DURATION_PRESETS = listOf(300, 500, 800, 1000, 1500, 2000, 3000)

        fun clampDuration(millis: Int): Int = millis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)

        /** 是否为可接受的输入值；数值输入框据此拒绝越界输入而不是静默截断。 */
        fun isAcceptableDuration(millis: Int): Boolean =
            millis in MIN_DURATION_MILLIS..MAX_DURATION_MILLIS

        fun nearestPreset(millis: Int): Int = DURATION_PRESETS.minBy { kotlin.math.abs(it - millis) }
    }
}
