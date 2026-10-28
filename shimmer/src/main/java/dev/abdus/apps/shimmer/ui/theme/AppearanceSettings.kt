package dev.abdus.apps.shimmer.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * P9-GLASS-01～03：界面外观设置。
 *
 * 透明度分三项独立可调，与 [dev.abdus.apps.shimmer.ui.effects.GlassEffectDefaults] 原有的三个
 * 硬编码常量一一对应。悬浮底栏默认关闭；已退役的旧字段由存储层忽略，不重置其余外观选择。
 */
@Serializable
data class AppearanceSettings(
    /** 壁纸背景上的遮罩不透明度；越低越透，文字对比度越差。 */
    val scrimAlpha: Float = DEFAULT_SCRIM_ALPHA,
    /** 卡片底色不透明度。 */
    val cardAlpha: Float = DEFAULT_CARD_ALPHA,
    /** 底栏底色不透明度。 */
    val bottomBarAlpha: Float = DEFAULT_BOTTOM_BAR_ALPHA,
    /** 悬浮底栏：底栏脱离屏幕底边，四周留白并带圆角。 */
    val floatingBottomBarEnabled: Boolean = false,
) {
    /** 把越界值夹回受支持范围；读取损坏数据时也走这里。 */
    fun normalized(): AppearanceSettings = copy(
        scrimAlpha = clampAlpha(scrimAlpha),
        cardAlpha = clampAlpha(cardAlpha),
        bottomBarAlpha = clampAlpha(bottomBarAlpha),
    )

    companion object {
        /**
         * 默认不透明度。
         *
         * 版本 35～37 为 0.75／0.8／0.9（等于 P8 的硬编码值）。用户 2026-10-06 要求改为
         * 40%／50%／40%，使界面默认更透、玻璃感更强。
         */
        const val DEFAULT_SCRIM_ALPHA = 0.40f
        const val DEFAULT_CARD_ALPHA = 0.50f
        const val DEFAULT_BOTTOM_BAR_ALPHA = 0.40f

        /**
         * 可调范围。
         *
         * 版本 35～37 的下限是 0.3，理由是低于此值文字对比度会跌破 WCAG AA 的 4.5:1（P8 刚修复
         * 的那一项）。用户 2026-10-06 明确要求范围改为 0～100%，据此放开下限。
         *
         * **代价：** 调到很低时卡片与底栏近乎全透，文字叠在壁纸上，对比度不再有保证——这是用户
         * 明确选择的取舍，不是实现缺陷。设置页已就此加提示。
         */
        const val MIN_ALPHA = 0.0f
        const val MAX_ALPHA = 1.0f

        /** 滑块步进；用户 2026-10-06 要求 1%，由 0.05 改为 0.01。 */
        const val ALPHA_STEP = 0.01f

        fun clampAlpha(value: Float): Float = value.coerceIn(MIN_ALPHA, MAX_ALPHA)

        /** 对齐到步进，避免浮点误差累积出 0.7500001 这类值。 */
        fun snapAlpha(value: Float): Float {
            val steps = Math.round(clampAlpha(value) / ALPHA_STEP)
            return clampAlpha(steps * ALPHA_STEP)
        }

        fun isAcceptableAlpha(value: Float): Boolean = value in MIN_ALPHA..MAX_ALPHA

        /** 百分比显示用；0.75 读作 75%。 */
        fun percentOf(value: Float): Int = Math.round(clampAlpha(value) * 100)
    }
}

/**
 * 外观设置存储。
 *
 * 独立偏好文件 `p9_appearance`，单个 JSON 键，与 P4 的 `p4_schedule`、P5 的 `p5_transition` 一致：
 * 不动数据库、不触发迁移。
 */
class AppearanceSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** 读取失败或内容损坏时回到默认值，不抛出。 */
    fun load(): AppearanceSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppearanceSettings()
        return try {
            json.decodeFromString<AppearanceSettings>(raw).normalized()
        } catch (_: Exception) {
            AppearanceSettings()
        }
    }

    fun save(settings: AppearanceSettings) {
        val normalized = settings.normalized()
        prefs.edit { putString(KEY_SETTINGS, json.encodeToString(normalized)) }
    }

    /** 设置页改动后立即重绘；与 ThemeModeStore 的观察方式一致。 */
    fun observe(onChange: (AppearanceSettings) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SETTINGS) onChange(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFS_NAME = "p9_appearance"
        const val KEY_SETTINGS = "settings"
    }
}
