package dev.abdus.apps.shimmer.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/** 设置页的深色模式选项。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    /** 主界面用：跟随系统时取系统当前值。 */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    /** Android 12+ 的应用级夜间模式，旧页面（DayNight 主题）随之切换。 */
    fun uiModeManagerValue(): Int = when (this) {
        SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        LIGHT -> UiModeManager.MODE_NIGHT_NO
        DARK -> UiModeManager.MODE_NIGHT_YES
    }

    companion object {
        /** 未保存或无法识别的值按“跟随系统”处理。 */
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * 深色模式选择的存储。单独的偏好文件：壁纸服务监听壁纸偏好文件，界面主题不应触发壁纸刷新。
 */
class ThemeModeStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun load(): ThemeMode = ThemeMode.fromStored(prefs.getString(KEY_THEME_MODE, null))

    fun save(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    /** 主界面监听，选择后立即重组；返回值用于注销。 */
    fun observe(onChange: (ThemeMode) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_THEME_MODE) onChange(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFS_NAME = "p8_ui"
        const val KEY_THEME_MODE = "theme_mode"

        /**
         * 让旧页面（P0～P5，DayNight 主题）跟随所选模式。Android 12 以下没有应用级夜间模式，
         * 旧页面继续跟随系统；主界面不受影响（由 [ThemeMode.isDark] 决定）。
         */
        fun applyToLegacyPages(context: Context, mode: ThemeMode) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode.uiModeManagerValue())
        }
    }
}
