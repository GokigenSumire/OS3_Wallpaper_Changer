package dev.abdus.apps.shimmer.ui.effects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p4.IntervalClock
import dev.abdus.apps.shimmer.p4.IntervalUnit
import dev.abdus.apps.shimmer.p4.ScheduleStore
import dev.abdus.apps.shimmer.p5.SlideDirectionChoice
import dev.abdus.apps.shimmer.p5.TransitionChoice
import dev.abdus.apps.shimmer.p5.TransitionSettings
import dev.abdus.apps.shimmer.p5.TransitionSettingsStore
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.ui.settings.ScheduleSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * P8.5 特效页面 ViewModel
 * 连接 P3 全局设置、P4 调度（P9-SETTINGS-02 从设置页移入）和 P5 过渡效果
 */
class EffectsViewModel @JvmOverloads constructor(
    application: Application,
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME,
) : AndroidViewModel(application) {

    private val preferences = WallpaperPreferences.create(application, wallpaperTarget)
    private val transitionStore = TransitionSettingsStore(application, wallpaperTarget)
    private val scheduleStore = ScheduleStore(application, wallpaperTarget)

    private val _uiState = MutableStateFlow(EffectsUiState())
    val uiState: StateFlow<EffectsUiState> = _uiState.asStateFlow()

    /** Re-reads both stores; the screen calls this on every resume (P2, P3 and P5 pages write them too). */
    fun refresh() {
        viewModelScope.launch {
            // 加载 P3 全局设置（含智能适配）
            val p3Defaults = preferences.getP3Defaults()
            val fitMode = EffectsMapping.fitModeOf(p3Defaults)

            val blurFillEnabled = p3Defaults.blurFill

            // 加载 P5 过渡效果设置
            val transitionSettings = transitionStore.load()
            val transitionEffect = when (transitionSettings.choice) {
                TransitionChoice.NONE -> TransitionEffect.NONE
                TransitionChoice.CROSSFADE -> TransitionEffect.FADE_IN_OUT
                TransitionChoice.FADE_THROUGH -> TransitionEffect.FADE_TO_BLACK
                TransitionChoice.SLIDE -> TransitionEffect.SLIDE
                TransitionChoice.ZOOM -> TransitionEffect.ZOOM
                TransitionChoice.RANDOM -> TransitionEffect.RANDOM
            }
            val transitionDuration = transitionSettings.durationMillis

            // P9-SETTINGS-02：调度从设置页移到本页，读同一个 P4 存储
            val scheduleSettings = scheduleStore.loadSettings()

            _uiState.value = _uiState.value.copy(
                selectedFitMode = fitMode,
                blurFillEnabled = blurFillEnabled,
                inheritPreviousEnabled = p3Defaults.inheritPrevious,
                selectedTransition = transitionEffect,
                transitionDuration = transitionDuration,
                intervalEnabled = scheduleSettings.interval.enabled,
                intervalLabel = ScheduleSummary.intervalLabel(scheduleSettings.interval),
                unlockEnabled = scheduleSettings.onUnlock,
                lockEnabled = scheduleSettings.onLock,
                screenOffEnabled = scheduleSettings.onScreenOff,
                slideDirection = transitionSettings.direction,
                intervalValue = scheduleSettings.interval.value,
                intervalUnit = scheduleSettings.interval.unit,
                intervalClock = scheduleSettings.interval.clock,
                isLoading = false
            )
        }
    }

    /** P9-SETTINGS-02：间隔开关；间隔数值与单位仍只在 P4 调度页修改。 */
    fun toggleInterval(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(intervalEnabled = enabled)
            val settings = scheduleStore.loadSettings()
            scheduleStore.saveSettings(
                settings.copy(interval = settings.interval.copy(enabled = enabled))
            )
        }
    }

    /** P9-SETTINGS-02：解锁时切换开关。 */
    fun toggleUnlock(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unlockEnabled = enabled)
            val settings = scheduleStore.loadSettings()
            scheduleStore.saveSettings(settings.copy(onUnlock = enabled))
        }
    }

    fun toggleLock(enabled: Boolean) {
        viewModelScope.launch {
            val settings = scheduleStore.loadSettings()
            scheduleStore.saveSettings(settings.copy(onLock = enabled))
            _uiState.value = _uiState.value.copy(lockEnabled = enabled)
        }
    }

    fun toggleScreenOff(enabled: Boolean) {
        viewModelScope.launch {
            val settings = scheduleStore.loadSettings()
            scheduleStore.saveSettings(settings.copy(onScreenOff = enabled))
            _uiState.value = _uiState.value.copy(screenOffEnabled = enabled)
        }
    }

    fun setFitMode(fitMode: FitMode) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(selectedFitMode = fitMode)
            // 智能写入 SMART 标记，其余写入具体方式
            EffectsMapping.saveFitMode(preferences, fitMode)
        }
    }

    fun toggleBlurFill(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(blurFillEnabled = enabled)
            preferences.setP3DefaultBlurFill(enabled)
        }
    }

    /** P9-PLAYBACK-ENTRIES-01：与旧 P2 页 P3 区的继承按钮写同一个偏好。 */
    fun toggleInheritPrevious(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inheritPreviousEnabled = enabled)
            preferences.setP3InheritPrevious(enabled)
        }
    }

    fun setTransitionEffect(effect: TransitionEffect) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(selectedTransition = effect)

            val currentSettings = transitionStore.load()
            val transitionChoice = when (effect) {
                TransitionEffect.NONE -> TransitionChoice.NONE
                TransitionEffect.FADE_IN_OUT -> TransitionChoice.CROSSFADE
                TransitionEffect.FADE_TO_BLACK -> TransitionChoice.FADE_THROUGH
                TransitionEffect.SLIDE -> TransitionChoice.SLIDE
                TransitionEffect.ZOOM -> TransitionChoice.ZOOM
                TransitionEffect.RANDOM -> TransitionChoice.RANDOM
            }
            val updatedSettings = currentSettings.copy(choice = transitionChoice)
            transitionStore.save(updatedSettings)
        }
    }

    /**
     * 滑块松手或数值输入确认时调用一次。
     *
     * P9-EFFECTS-02：不再吸附到预设，只夹到受支持范围，1 ms 步进的任意值都原样保存。
     */
    fun setTransitionDuration(duration: Int) {
        val millis = TransitionSettings.clampDuration(duration)
        if (millis == _uiState.value.transitionDuration) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(transitionDuration = millis)

            val currentSettings = transitionStore.load()
            transitionStore.save(currentSettings.copy(durationMillis = millis))
        }
    }
    /**
     * P9-EFFECTS-01：滑动方向。
     *
     * 原先只在旧 P5 过渡页可改，新 UI 靠"过渡诊断与更多设置"按钮跳转过去。该跳转已消除，方向改在
     * 本页设置，写同一个 p5_transition 存储。
     */
    fun setSlideDirection(choice: SlideDirectionChoice) {
        if (choice == _uiState.value.slideDirection) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(slideDirection = choice)
            val current = transitionStore.load()
            transitionStore.save(current.copy(direction = choice))
        }
    }
    /**
     * P9-EFFECTS-01：间隔数值、单位与计时方式。
     *
     * 原先只在旧 P4 调度页可改，新 UI 靠"更多调度设置"跳转过去；该跳转已消除。
     * [IntervalSettings.valid] 的下限 10 秒、上限 24 小时由存储层把关，这里只拒绝越界输入而不静默夹取。
     */
    fun setInterval(value: Int, unit: IntervalUnit, clock: IntervalClock) {
        viewModelScope.launch {
            val settings = scheduleStore.loadSettings()
            val updated = settings.interval.copy(value = value, unit = unit, clock = clock)
            if (!updated.valid) {
                _uiState.value = _uiState.value.copy(
                    message = "间隔须在 10 秒到 24 小时之间"
                )
                return@launch
            }
            scheduleStore.saveSettings(settings.copy(interval = updated))
            _uiState.value = _uiState.value.copy(
                intervalLabel = ScheduleSummary.intervalLabel(updated),
                intervalValue = value,
                intervalUnit = unit,
                intervalClock = clock,
            )
        }
    }

    fun messageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

/**
 * 特效页面 UI 状态
 */
data class EffectsUiState(
    val selectedFitMode: FitMode = FitMode.SMART,
    val blurFillEnabled: Boolean = false,
    /** P9-PLAYBACK-ENTRIES-01：旧 P2 页的"新图继承上一张的调整"，与 P3 默认读写同一个键。 */
    val inheritPreviousEnabled: Boolean = false,
    val selectedTransition: TransitionEffect = TransitionEffect.FADE_IN_OUT,
    val transitionDuration: Int = TransitionSettings.DEFAULT_DURATION_MILLIS,
    /** P9-EFFECTS-01：滑动方向，原先只在旧 P5 页可改。 */
    val slideDirection: SlideDirectionChoice = SlideDirectionChoice.FOLLOW,
    /** P9-EFFECTS-01：间隔数值、单位与计时方式，原先只在旧 P4 页可改。 */
    val intervalValue: Int = 30,
    val intervalUnit: IntervalUnit = IntervalUnit.MINUTES,
    val intervalClock: IntervalClock = IntervalClock.VISIBLE,
    val message: String? = null,
    // P9-SETTINGS-02：调度从设置页移入本页
    val intervalEnabled: Boolean = false,
    val intervalLabel: String = "",
    val unlockEnabled: Boolean = false,
    val lockEnabled: Boolean = false,
    val screenOffEnabled: Boolean = false,
    val isLoading: Boolean = true
)
