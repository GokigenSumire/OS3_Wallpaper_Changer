package dev.abdus.apps.shimmer.p9

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettings
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P9-GLASS-01 透明度自定义的存储与取值契约。
 *
 * 用户要求"各部分的透明可在设置中自定义"。三项分别对应原来硬编码的 ScrimAlpha、CardAlpha、
 * BottomBarAlpha。下限 0.3 是 P9 计划冻结的值：再低文字对比度必然跌破 4.5:1，与 P8 已修复的
 * 对比度要求直接冲突。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P9AppearanceSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: AppearanceSettingsStore

    @Before
    fun setUp() {
        context.getSharedPreferences(AppearanceSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = AppearanceSettingsStore(context)
    }

    /**
     * 默认值。
     *
     * 版本 35～37 为 0.75／0.8／0.9（等于 P8 的硬编码值）；用户 2026-10-06 要求改为 40%／50%／40%。
     * 本用例替换了原先的 `defaultsMatchTheHardCodedValuesFromVersion34`——那条断言的是旧的三个值。
     */
    @Test
    fun defaultsMatchTheValuesTheUserAskedFor() {
        val settings = store.load()

        assertEquals(0.40f, settings.scrimAlpha, 0.0001f)
        assertEquals(0.50f, settings.cardAlpha, 0.0001f)
        assertEquals(0.40f, settings.bottomBarAlpha, 0.0001f)
        // 悬浮底栏继续独立可选。
        assertFalse(settings.floatingBottomBarEnabled)
    }

    /** 三项独立可调，改一项不影响另两项。 */
    @Test
    fun threeAlphasAreIndependent() {
        store.save(store.load().copy(cardAlpha = 0.65f))

        val afterCard = store.load()
        assertEquals(0.65f, afterCard.cardAlpha, 0.0001f)
        assertEquals(0.40f, afterCard.scrimAlpha, 0.0001f)
        assertEquals(0.40f, afterCard.bottomBarAlpha, 0.0001f)

        store.save(afterCard.copy(bottomBarAlpha = 0.15f))
        val afterBar = store.load()
        assertEquals(0.65f, afterBar.cardAlpha, 0.0001f)
        assertEquals(0.15f, afterBar.bottomBarAlpha, 0.0001f)
    }

    /** 越界值夹到范围内，不是丢弃整份设置。 */
    @Test
    fun outOfRangeAlphasAreClampedNotDiscarded() {
        store.save(
            AppearanceSettings(scrimAlpha = -1f, cardAlpha = 5f, bottomBarAlpha = 0.6f)
        )

        val loaded = store.load()
        assertEquals(AppearanceSettings.MIN_ALPHA, loaded.scrimAlpha, 0.0001f)
        assertEquals(AppearanceSettings.MAX_ALPHA, loaded.cardAlpha, 0.0001f)
        // 合法的那一项不受影响
        assertEquals(0.6f, loaded.bottomBarAlpha, 0.0001f)
    }

    /**
     * 可调范围为 0～100%。
     *
     * 版本 35～37 的下限是 0.3，理由是守住 P8 修复的 4.5:1 对比度。用户 2026-10-06 明确要求改为
     * 0～100%，据此放开。本用例替换了原先的 `lowerBoundProtectsContrast`——那条断言 0.29 不可接受。
     *
     * 代价（用户已明确选择）：调到很低时文字叠在壁纸上，对比度不再有保证。设置页已就此加提示。
     */
    @Test
    fun rangeIsFullyOpenAsRequested() {
        assertEquals(0f, AppearanceSettings.MIN_ALPHA, 0.0001f)
        assertEquals(1f, AppearanceSettings.MAX_ALPHA, 0.0001f)
        assertTrue(AppearanceSettings.isAcceptableAlpha(0f))
        assertTrue(AppearanceSettings.isAcceptableAlpha(0.01f))
        assertTrue(AppearanceSettings.isAcceptableAlpha(1.0f))
        // 仍拒绝区间外的值
        assertFalse(AppearanceSettings.isAcceptableAlpha(-0.01f))
        assertFalse(AppearanceSettings.isAcceptableAlpha(1.01f))
    }

    /**
     * 步进 1%。
     *
     * 用户 2026-10-06 要求三个滑块步进都改为 1，由 0.05 改为 0.01。本用例替换了原先的
     * `snapAlignsToStepWithoutDrift`——那条按 0.05 步进断言。
     */
    @Test
    fun snapAlignsToOnePercentStep() {
        assertEquals(0.40f, AppearanceSettings.snapAlpha(0.4000001f), 0.0001f)
        // 1% 步进下 0.37、0.43 这类值都应原样保留，不再被吸附到 0.35／0.45
        assertEquals(0.37f, AppearanceSettings.snapAlpha(0.37f), 0.0001f)
        assertEquals(0.43f, AppearanceSettings.snapAlpha(0.43f), 0.0001f)
        // 中间值仍按最近的 1% 对齐
        assertEquals(0.38f, AppearanceSettings.snapAlpha(0.376f), 0.0001f)
        // 越界先夹再对齐
        assertEquals(AppearanceSettings.MIN_ALPHA, AppearanceSettings.snapAlpha(-1f), 0.0001f)
        assertEquals(AppearanceSettings.MAX_ALPHA, AppearanceSettings.snapAlpha(2f), 0.0001f)
    }

    /** 百分比显示。 */
    @Test
    fun percentReadsAsWholeNumber() {
        assertEquals(75, AppearanceSettings.percentOf(0.75f))
        assertEquals(100, AppearanceSettings.percentOf(1.0f))
        assertEquals(30, AppearanceSettings.percentOf(0.3f))
    }

    /** 悬浮底栏能持久化，且不影响透明度。 */
    @Test
    fun floatingBarPersistsWithoutChangingAlphas() {
        store.save(AppearanceSettings(scrimAlpha = 0.27f, cardAlpha = 0.63f,
            bottomBarAlpha = 0.19f, floatingBottomBarEnabled = true))
        val enabled = store.load()
        assertTrue(enabled.floatingBottomBarEnabled)
        assertEquals(0.27f, enabled.scrimAlpha, 0.0001f)
        assertEquals(0.63f, enabled.cardAlpha, 0.0001f)
        assertEquals(0.19f, enabled.bottomBarAlpha, 0.0001f)

        store.save(enabled.copy(floatingBottomBarEnabled = false))
        assertEquals(enabled.copy(floatingBottomBarEnabled = false), store.load())
    }

    /** 升级前开启的旧字段不使合法外观设置回退成默认值。 */
    @Test
    fun retiredEnabledFieldIsIgnoredWithoutResettingAppearance() {
        putRaw("""{"scrimAlpha":0.12,"cardAlpha":0.34,"bottomBarAlpha":0.56,"liquidGlassEnabled":true,"floatingBottomBarEnabled":true}""")

        assertEquals(AppearanceSettings(scrimAlpha = 0.12f, cardAlpha = 0.34f,
            bottomBarAlpha = 0.56f, floatingBottomBarEnabled = true), store.load())
    }

    /** 旧字段关闭时也忽略，且所有合法值与悬浮选择保持不变。 */
    @Test
    fun retiredDisabledFieldIsIgnoredWithoutResettingAppearance() {
        putRaw("""{"scrimAlpha":0.98,"cardAlpha":0.76,"bottomBarAlpha":0.54,"liquidGlassEnabled":false,"floatingBottomBarEnabled":false}""")

        assertEquals(AppearanceSettings(scrimAlpha = 0.98f, cardAlpha = 0.76f,
            bottomBarAlpha = 0.54f, floatingBottomBarEnabled = false), store.load())
    }

    /** 下一次保存只写当前结构，旧字段不会被重新传播。 */
    @Test
    fun resavingLegacyAppearanceDropsRetiredFieldOnly() {
        putRaw("""{"scrimAlpha":0.21,"cardAlpha":0.43,"bottomBarAlpha":0.65,"liquidGlassEnabled":true,"floatingBottomBarEnabled":true}""")
        val loaded = store.load()
        store.save(loaded)

        val raw = context.getSharedPreferences(AppearanceSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(AppearanceSettingsStore.KEY_SETTINGS, null).orEmpty()
        assertFalse(raw.contains("liquidGlassEnabled"))
        assertEquals(loaded, AppearanceSettingsStore(context).load())
    }

    private fun putRaw(raw: String) {
        context.getSharedPreferences(AppearanceSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(AppearanceSettingsStore.KEY_SETTINGS, raw).commit()
    }

    /** 损坏的 JSON 回到默认值，不崩溃。 */
    @Test
    fun damagedJsonFallsBackToDefaults() {
        context.getSharedPreferences(AppearanceSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(AppearanceSettingsStore.KEY_SETTINGS, "{not json").commit()

        assertEquals(AppearanceSettings(), store.load())
    }

    /** 未知字段被忽略，便于以后加项而不打翻旧设置。 */
    @Test
    fun unknownFieldsAreIgnored() {
        context.getSharedPreferences(AppearanceSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                AppearanceSettingsStore.KEY_SETTINGS,
                """{"cardAlpha":0.55,"somethingNew":42}"""
            )
            .commit()

        assertEquals(0.55f, store.load().cardAlpha, 0.0001f)
    }

    /** 另一个实例立刻看到写入，设置页与各组件读的是同一份。 */
    @Test
    fun allReadersShareOneSourceOfTruth() {
        store.save(store.load().copy(scrimAlpha = 0.45f))

        assertEquals(0.45f, AppearanceSettingsStore(context).load().scrimAlpha, 0.0001f)
    }
}
