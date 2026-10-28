package dev.abdus.apps.shimmer.ui.effects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p3.P3Defaults
import dev.abdus.apps.shimmer.p5.TransitionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.abdus.apps.shimmer.p3.FitMode as P3FitMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EffectsMappingTest {
    private lateinit var preferences: WallpaperPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = WallpaperPreferences.create(context)
    }

    @Test
    fun everyTabFitModeRoundTripsThroughTheP3Store() {
        for (mode in FitMode.entries) {
            EffectsMapping.saveFitMode(preferences, mode)

            assertEquals(mode, EffectsMapping.fitModeOf(preferences.getP3Defaults()))
        }
    }

    @Test
    fun leavingSmartFitClearsItInTheStore() {
        EffectsMapping.saveFitMode(preferences, FitMode.SMART)
        assertTrue(preferences.getP3Defaults().smartFit)

        EffectsMapping.saveFitMode(preferences, FitMode.FIT_WIDTH)

        val defaults = preferences.getP3Defaults()
        assertFalse(defaults.smartFit)
        assertEquals(P3FitMode.FIT_WIDTH, defaults.fitMode)
    }

    @Test
    fun smartFitWinsOverTheStoredFallbackMode() {
        assertEquals(FitMode.SMART, EffectsMapping.fitModeOf(P3Defaults(fitMode = P3FitMode.COVER, smartFit = true)))
        assertEquals(FitMode.FILL, EffectsMapping.fitModeOf(P3Defaults(fitMode = P3FitMode.COVER, smartFit = false)))
        assertEquals(FitMode.COMPLETE, EffectsMapping.fitModeOf(P3Defaults(fitMode = P3FitMode.CONTAIN)))
        assertEquals(FitMode.FIT_HEIGHT, EffectsMapping.fitModeOf(P3Defaults(fitMode = P3FitMode.FIT_HEIGHT)))
    }

    /**
     * P9-EFFECTS-02：滑块改为 1 ms 步进的连续取值。
     *
     * 本用例替换了版本 33 及以前的 `durationSliderOnlyLandsOnP5Presets`——那条断言滑块只能
     * 落在 7 个预设上，与用户要求的无极滑块直接冲突，已按新契约重写。
     */
    @Test
    fun durationSliderMovesInOneMillisecondSteps() {
        // 范围端点就是受支持范围，不再是预设的首尾
        assertEquals(TransitionSettings.MIN_DURATION_MILLIS, EffectsMapping.minDurationMillis)
        assertEquals(TransitionSettings.MAX_DURATION_MILLIS, EffectsMapping.maxDurationMillis)

        // 任意非预设值原样保留，不再被吸附
        assertEquals(1234, EffectsMapping.durationAt(1234f))
        assertEquals(101, EffectsMapping.durationAt(101f))
        assertEquals(4999, EffectsMapping.durationAt(4999f))

        // 小数落点取整到 1 ms
        assertEquals(1234, EffectsMapping.durationAt(1234.4f))
        assertEquals(1235, EffectsMapping.durationAt(1234.6f))

        // 越界夹到边界
        assertEquals(EffectsMapping.minDurationMillis, EffectsMapping.durationAt(-100f))
        assertEquals(EffectsMapping.maxDurationMillis, EffectsMapping.durationAt(99999f))

        // 旧预设全部仍是合法值，升级后用户原设置不丢
        TransitionSettings.DURATION_PRESETS.forEach { preset ->
            assertEquals(preset, EffectsMapping.durationAt(preset.toFloat()))
        }
    }

    /** P9-EFFECTS-03：数值输入拒绝越界与非数字，不静默截断。 */
    @Test
    fun durationInputRejectsOutOfRangeInsteadOfClamping() {
        assertEquals(1234, EffectsMapping.parseDurationInput("1234"))
        assertEquals(100, EffectsMapping.parseDurationInput("100"))
        assertEquals(5000, EffectsMapping.parseDurationInput("5000"))
        // 允许前后空白
        assertEquals(800, EffectsMapping.parseDurationInput("  800 "))

        // 越界返回 null，由界面提示；不得夹到边界后当作成功
        assertNull(EffectsMapping.parseDurationInput("99"))
        assertNull(EffectsMapping.parseDurationInput("5001"))
        assertNull(EffectsMapping.parseDurationInput("0"))
        assertNull(EffectsMapping.parseDurationInput("-500"))

        // 非数字
        assertNull(EffectsMapping.parseDurationInput(""))
        assertNull(EffectsMapping.parseDurationInput("abc"))
        assertNull(EffectsMapping.parseDurationInput("1.5"))
    }

    /** 存储层契约：normalized 只夹取范围，不再吸附预设。 */
    @Test
    fun normalizedKeepsAnyInRangeDuration() {
        assertEquals(1234, TransitionSettings(durationMillis = 1234).normalized().durationMillis)
        assertEquals(
            TransitionSettings.MIN_DURATION_MILLIS,
            TransitionSettings(durationMillis = 1).normalized().durationMillis
        )
        assertEquals(
            TransitionSettings.MAX_DURATION_MILLIS,
            TransitionSettings(durationMillis = 90_000).normalized().durationMillis
        )
    }
}
