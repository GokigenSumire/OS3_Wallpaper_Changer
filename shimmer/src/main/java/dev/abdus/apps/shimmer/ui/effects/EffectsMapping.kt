package dev.abdus.apps.shimmer.ui.effects

import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p3.P3Defaults
import dev.abdus.apps.shimmer.p5.TransitionSettings
import kotlin.math.roundToInt
import dev.abdus.apps.shimmer.p3.FitMode as P3FitMode

/** Maps the effects tab choices to the P3 and P5 stores without changing what those stores accept. */
object EffectsMapping {
    /** Smart fit wins over the stored mode: P3 reads an unknown or "SMART" value as COVER plus smartFit. */
    fun fitModeOf(defaults: P3Defaults): FitMode = when {
        defaults.smartFit -> FitMode.SMART
        else -> when (defaults.fitMode) {
            P3FitMode.FIT_WIDTH -> FitMode.FIT_WIDTH
            P3FitMode.FIT_HEIGHT -> FitMode.FIT_HEIGHT
            P3FitMode.CONTAIN -> FitMode.COMPLETE
            P3FitMode.COVER -> FitMode.FILL
        }
    }

    fun saveFitMode(preferences: WallpaperPreferences, mode: FitMode) {
        when (mode) {
            FitMode.SMART -> preferences.setP3SmartFit()
            FitMode.FILL -> preferences.setP3DefaultFitMode(P3FitMode.COVER)
            FitMode.FIT_WIDTH -> preferences.setP3DefaultFitMode(P3FitMode.FIT_WIDTH)
            FitMode.FIT_HEIGHT -> preferences.setP3DefaultFitMode(P3FitMode.FIT_HEIGHT)
            FitMode.COMPLETE -> preferences.setP3DefaultFitMode(P3FitMode.CONTAIN)
        }
    }

    /**
     * P9-EFFECTS-02：时长改为 1 ms 步进的连续取值，不再限于预设。
     *
     * 旧 P5 页仍用 [TransitionSettings.DURATION_PRESETS] 做快捷单选，两边写同一个存储键。
     */
    val minDurationMillis: Int = TransitionSettings.MIN_DURATION_MILLIS
    val maxDurationMillis: Int = TransitionSettings.MAX_DURATION_MILLIS

    /** 滑块落点取整到 1 ms，并夹到受支持范围。 */
    fun durationAt(value: Float): Int =
        TransitionSettings.clampDuration(value.roundToInt())

    /**
     * 解析数值输入框。返回 null 表示不是合法时长，调用方应提示而不是静默截断——
     * 静默截断会让用户以为设置成功。
     */
    fun parseDurationInput(text: String): Int? {
        val millis = text.trim().toIntOrNull() ?: return null
        return if (TransitionSettings.isAcceptableDuration(millis)) millis else null
    }

    /** 保留给旧 P5 页与升级兼容。 */
    val durationPresets: List<Int> = TransitionSettings.DURATION_PRESETS
}
