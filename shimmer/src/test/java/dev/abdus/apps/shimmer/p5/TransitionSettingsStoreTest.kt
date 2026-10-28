package dev.abdus.apps.shimmer.p5

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `p5_transition`: defaults after an upgrade, presets only, and no crash on a damaged value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitionSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val raw = context.getSharedPreferences(TransitionSettingsStore.NAME, Context.MODE_PRIVATE)
    private val store = TransitionSettingsStore(context)

    @Before
    fun clear() {
        raw.edit().clear().commit()
    }

    @Test
    fun anUpgradeWithoutSettingsCrossfadesForFifteenHundredMillis() {
        assertEquals(TransitionSettings(TransitionChoice.CROSSFADE, 1_500, SlideDirectionChoice.FOLLOW), store.load())
    }

    @Test
    fun savedSettingsRoundTrip() {
        val settings = TransitionSettings(TransitionChoice.SLIDE, 300, SlideDirectionChoice.UP)
        store.save(settings)
        assertEquals(settings, TransitionSettingsStore(context).load())
    }

    /**
     * P9-EFFECTS-02 起时长不再吸附预设，只夹到 [TransitionSettings.MIN_DURATION_MILLIS]～
     * [TransitionSettings.MAX_DURATION_MILLIS]。
     *
     * 本用例替换了版本 33 及以前的 `durationsSnapToTheNearestPreset`——那条断言任意值都被改写成
     * 最近的预设，与无极滑块冲突。
     */
    @Test
    fun durationsKeepAnyInRangeValueAndClampOutOfRange() {
        // 范围内的任意值原样保存，包括过去会被吸附掉的 420、1200、1800、2600
        listOf(100, 420, 1_200, 1_234, 1_800, 2_600, 5_000).forEach { input ->
            store.save(TransitionSettings(durationMillis = input))
            assertEquals("$input", input, store.load().durationMillis)
        }
        // 越界夹到边界
        store.save(TransitionSettings(durationMillis = 0))
        assertEquals(TransitionSettings.MIN_DURATION_MILLIS, store.load().durationMillis)
        store.save(TransitionSettings(durationMillis = 99_999))
        assertEquals(TransitionSettings.MAX_DURATION_MILLIS, store.load().durationMillis)
        // 直接写入的损坏负值同样夹到下限
        raw.edit().putString(TransitionSettingsStore.KEY_SETTINGS, """{"durationMillis":-5}""").commit()
        assertEquals(TransitionSettings.MIN_DURATION_MILLIS, store.load().durationMillis)
    }

    @Test
    fun aDamagedOrUnknownValueFallsBackWithoutCrashing() {
        raw.edit().putString(TransitionSettingsStore.KEY_SETTINGS, "{not json").commit()
        assertEquals(TransitionSettings(), store.load())
        raw.edit().putString(TransitionSettingsStore.KEY_SETTINGS, """{"choice":"SPIRAL","direction":"LEFT","extra":1}""").commit()
        assertEquals(TransitionSettings(direction = SlideDirectionChoice.LEFT), store.load())
    }
}
