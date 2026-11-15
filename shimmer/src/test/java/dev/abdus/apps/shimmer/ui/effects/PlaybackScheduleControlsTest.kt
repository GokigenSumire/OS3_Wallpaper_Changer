package dev.abdus.apps.shimmer.ui.effects

import android.app.Application
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p4.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PlaybackScheduleControlsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun flush() = shadowOf(Looper.getMainLooper()).idle()
    @Test fun pauseKeepsAllConditionsAndTheOtherTargetUntouched() {
        val store = ScheduleStore(app)
        val original = ScheduleSettings(interval = IntervalSettings(enabled = true), onLock = true,
            onUnlock = true, rules = listOf(PlaybackRule("a")))
        store.saveSettings(original)
        val vm = EffectsViewModel(app)
        vm.toggleSchedulePause(true); flush()
        assertEquals(original.copy(pauseAll = true), store.loadSettings())
        assertTrue(vm.uiState.value.schedulePaused)
        assertFalse(ScheduleStore(app, WallpaperTarget.LOCK).loadSettings().pauseAll)
        vm.toggleSchedulePause(false); flush()
        assertEquals(original, store.loadSettings())
    }
    @Test fun editingDisabledIntervalDoesNotEnableOrUnpauseIt() {
        val store = ScheduleStore(app)
        store.saveSettings(ScheduleSettings(pauseAll = true))
        val vm = EffectsViewModel(app)
        vm.setInterval(42, IntervalUnit.SECONDS, IntervalClock.WALL); flush()
        val result = store.loadSettings()
        assertTrue(result.pauseAll)
        assertFalse(result.interval.enabled)
        assertEquals(42, result.interval.value)
        assertEquals(IntervalClock.WALL, result.interval.clock)
        assertNotNull(vm.uiState.value.message)
    }
    @Test fun invalidIntervalDoesNotOverwriteExistingSettings() {
        val store = ScheduleStore(app); val original = store.loadSettings()
        val vm = EffectsViewModel(app)
        vm.setInterval(1, IntervalUnit.SECONDS, IntervalClock.VISIBLE); flush()
        assertEquals(original, store.loadSettings())
        assertEquals("间隔须在 10 秒到 24 小时之间", vm.uiState.value.message)
    }
    @Test fun resumeReadsPauseAndRuleCountsFromActualStore() {
        val store = ScheduleStore(app, WallpaperTarget.LOCK)
        store.saveSettings(ScheduleSettings(pauseAll = true,
            dailyTimes = listOf(DailyTime("t", minuteOfDay = 30), DailyTime("off", minuteOfDay = 60, enabled = false)),
            rules = listOf(PlaybackRule("r"))))
        val vm = EffectsViewModel(app, WallpaperTarget.LOCK); vm.refresh(); flush()
        assertTrue(vm.uiState.value.schedulePaused)
        assertEquals("已开启 1 个定时 · 1 条规则", vm.uiState.value.scheduleRulesLabel)
    }
}
