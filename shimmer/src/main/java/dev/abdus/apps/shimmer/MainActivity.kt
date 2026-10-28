package dev.abdus.apps.shimmer

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.abdus.apps.shimmer.ui.ShimmerTheme
import dev.abdus.apps.shimmer.ui.effects.LocalAppearanceSettings
import dev.abdus.apps.shimmer.ui.navigation.MainNavigation
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettings
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettingsStore
import dev.abdus.apps.shimmer.ui.theme.ThemeMode
import dev.abdus.apps.shimmer.ui.theme.ThemeModeStore

/**
 * P8 主入口 Activity
 * 使用底栏导航框架；主题按设置页的“跟随系统 / 浅色 / 深色”选择
 */
class MainActivity : ComponentActivity() {
    private lateinit var themeStore: ThemeModeStore
    private var themeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)

    // P9-GLASS-01～03：外观设置与主题同样在顶层读取并监听，改动后立即重绘
    private lateinit var appearanceStore: AppearanceSettingsStore
    private var appearanceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var appearance by mutableStateOf(AppearanceSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeStore = ThemeModeStore(this)
        themeMode = themeStore.load()
        // 设置页保存后立即重绘；Android 12+ 另由系统按应用夜间模式重建本页，这里只读不写，避免重建循环
        themeListener = themeStore.observe { themeMode = it }
        appearanceStore = AppearanceSettingsStore(this)
        appearance = appearanceStore.load()
        appearanceListener = appearanceStore.observe { appearance = it }
        enableEdgeToEdge()

        setContent {
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            // 状态栏、导航栏图标颜色跟随应用内主题，而不是只看系统配置
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            ShimmerTheme(darkTheme = darkTheme) {
                // 外观设置一次性下发给所有组件，参考 KernelSU 的 CompositionLocal 开关做法
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    // Surface 保持默认底色：MainNavigation 自己铺 WallpaperBackdrop 并用透明
                    // Scaffold，这层底色被完全覆盖；改动它会引入未验证的视觉变化。
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MainNavigation()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        themeListener?.let { themeStore.stopObserving(it) }
        themeListener = null
        appearanceListener?.let { appearanceStore.stopObserving(it) }
        appearanceListener = null
        super.onDestroy()
    }

    private companion object {
        // 与 androidx.activity 默认的三键导航栏遮罩相同
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
