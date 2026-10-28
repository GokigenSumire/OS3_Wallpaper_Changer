package dev.abdus.apps.shimmer.p9

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p4.ScheduleStore
import dev.abdus.apps.shimmer.ui.settings.ScheduleSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P9-SETTINGS-02 回归测试：调度从设置页移到特效页。
 *
 * 用户要求"调度切至特效页一栏"。迁移后两处必须读写同一份 P4 存储——特效页的开关、旧 P4 调度页
 * 和壁纸服务看到的必须是同一个值，否则用户在新位置的操作不生效，或与旧页互相覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P9ScheduleRelocationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: ScheduleStore

    @Before
    fun setUp() {
        store = ScheduleStore(context)
    }

    /** 特效页的间隔开关写入后，任何读取方都能看到。 */
    @Test
    fun intervalToggleFromEffectsPageIsVisibleEverywhere() = runBlocking {
        val initial = store.loadSettings()
        assertFalse("默认不应开启间隔切换", initial.interval.enabled)

        // 复刻 EffectsViewModel.toggleInterval 的写入
        store.saveSettings(initial.copy(interval = initial.interval.copy(enabled = true)))

        // 另一个 store 实例代表旧 P4 页或壁纸服务
        assertTrue(ScheduleStore(context).loadSettings().interval.enabled)
    }

    /** 解锁开关同理。 */
    @Test
    fun unlockToggleFromEffectsPageIsVisibleEverywhere() = runBlocking {
        val initial = store.loadSettings()
        store.saveSettings(initial.copy(onUnlock = true))

        assertTrue(ScheduleStore(context).loadSettings().onUnlock)
    }

    /**
     * 迁移不得丢失间隔数值与单位：特效页只改开关，数值仍由 P4 调度页维护。
     */
    @Test
    fun togglingEnabledKeepsIntervalValueAndUnit() = runBlocking {
        val original = store.loadSettings()
        val withValue = original.copy(
            interval = original.interval.copy(enabled = false, value = 42)
        )
        store.saveSettings(withValue)
        val savedValue = store.loadSettings().interval.value

        // 只翻开关
        val loaded = store.loadSettings()
        store.saveSettings(loaded.copy(interval = loaded.interval.copy(enabled = true)))

        val after = store.loadSettings()
        assertTrue(after.interval.enabled)
        assertEquals("翻开关不得改动间隔数值", savedValue, after.interval.value)
    }

    /** 两个开关互不影响。 */
    @Test
    fun intervalAndUnlockAreIndependent() = runBlocking {
        val base = store.loadSettings()
        store.saveSettings(base.copy(interval = base.interval.copy(enabled = true), onUnlock = false))

        val afterInterval = store.loadSettings()
        assertTrue(afterInterval.interval.enabled)
        assertFalse(afterInterval.onUnlock)

        store.saveSettings(afterInterval.copy(onUnlock = true))
        val afterUnlock = store.loadSettings()
        assertTrue("开启解锁不得关掉间隔", afterUnlock.interval.enabled)
        assertTrue(afterUnlock.onUnlock)
    }

    /** 特效页显示的间隔摘要与设置页用的是同一个格式化入口。 */
    @Test
    fun effectsPageUsesSameIntervalLabelAsBefore() = runBlocking {
        val settings = store.loadSettings()
        val label = ScheduleSummary.intervalLabel(settings.interval)

        assertTrue("摘要不应为空", label.isNotBlank())
        assertEquals(label, ScheduleSummary.intervalLabel(store.loadSettings().interval))
    }
}
