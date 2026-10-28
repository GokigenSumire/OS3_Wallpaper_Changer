package dev.abdus.apps.shimmer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p4.ScheduleStore
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettings
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettingsStore
import dev.abdus.apps.shimmer.ui.theme.ThemeMode
import dev.abdus.apps.shimmer.ui.theme.ThemeModeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * P8.6 设置页面 ViewModel
 * 连接 P4 调度设置和应用配置；间隔数值与单位只在 P4 调度页修改
 */
class P8SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = WallpaperPreferences.create(application)
    private val scheduleStore = ScheduleStore(application)
    private val themeStore = ThemeModeStore(application)
    private val appearanceStore = AppearanceSettingsStore(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Re-reads the stores; the screen calls this on every resume (P2, P3 and P4 pages write them too). */
    fun refresh() {
        viewModelScope.launch {
            // 加载 P4 调度设置
            val scheduleSettings = scheduleStore.loadSettings()

            _uiState.value = _uiState.value.copy(
                intervalEnabled = scheduleSettings.interval.enabled,
                intervalLabel = ScheduleSummary.intervalLabel(scheduleSettings.interval),
                unlockEnabled = scheduleSettings.onUnlock,
                staticAcceptanceEnabled = preferences.isP2StaticTestMode(),
                appearance = appearanceStore.load(),
                themeMode = themeStore.load(),
                isLoading = false
            )
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        if (mode == _uiState.value.themeMode) return
        _uiState.value = _uiState.value.copy(themeMode = mode)
        // 主界面监听这个存储立即重组；Android 12+ 旧页面经系统的应用级夜间模式切换
        themeStore.save(mode)
        ThemeModeStore.applyToLegacyPages(getApplication(), mode)
    }

    fun toggleInterval(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(intervalEnabled = enabled)

            val settings = scheduleStore.loadSettings()
            val updated = settings.copy(
                interval = settings.interval.copy(enabled = enabled)
            )
            scheduleStore.saveSettings(updated)
        }
    }

    fun toggleUnlock(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unlockEnabled = enabled)

            val settings = scheduleStore.loadSettings()
            val updated = settings.copy(onUnlock = enabled)
            scheduleStore.saveSettings(updated)
        }
    }

    // --- P9-GLASS-01～03 外观设置 -----------------------------------------------------------
    // 四个入口都走同一份存储；MainActivity 监听该存储并立即重绘，所以不需要在这里通知界面。

    fun setScrimAlpha(value: Float) = updateAppearance { it.copy(scrimAlpha = value) }

    fun setCardAlpha(value: Float) = updateAppearance { it.copy(cardAlpha = value) }

    fun setBottomBarAlpha(value: Float) = updateAppearance { it.copy(bottomBarAlpha = value) }

    fun toggleFloatingBottomBar(enabled: Boolean) =
        updateAppearance { it.copy(floatingBottomBarEnabled = enabled) }

    /** 读改写一份设置；越界值由存储层的 normalized 夹取。 */
    private fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch {
            val updated = transform(appearanceStore.load()).normalized()
            appearanceStore.save(updated)
            _uiState.value = _uiState.value.copy(appearance = updated)
        }
    }

    fun toggleStaticAcceptance(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(staticAcceptanceEnabled = enabled)
            // P9-STATIC-SHIMMER-01：版本 39 这里只翻转标志，首次安装设备关闭后上游特效全部显露。
            // 关闭时与旧 P2 页走同一个函数，先清零特效再翻转标志。
            if (enabled) preferences.setP2StaticTestMode(true) else preferences.exitStaticTestMode()
        }
    }

    fun messageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

/**
 * 设置页面 UI 状态
 */
data class SettingsUiState(
    // 调度三项随 P9-SETTINGS-02 迁到特效页，这里保留是因为 toggleInterval/toggleUnlock 仍可被
    // 旧入口调用；设置页本身不再渲染它们。
    val intervalEnabled: Boolean = false,
    val intervalLabel: String = "",
    val unlockEnabled: Boolean = false,
    val staticAcceptanceEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 三项透明度与悬浮底栏开关。 */
    val appearance: AppearanceSettings = AppearanceSettings(),
    val message: String? = null,
    val isLoading: Boolean = true
)
