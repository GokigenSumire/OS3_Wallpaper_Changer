package dev.abdus.apps.shimmer.p3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultFitChoiceTest {
    private lateinit var preferences: WallpaperPreferences

    @Before
    fun setUp() {
        preferences = WallpaperPreferences.create(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun cyclingFromAnyChoiceVisitsAllFiveIncludingSmart() {
        var choice = DefaultFitChoice.SMART
        val seen = mutableListOf<DefaultFitChoice>()
        repeat(DefaultFitChoice.entries.size) {
            seen += choice
            choice = choice.next()
        }

        assertEquals(DefaultFitChoice.entries.toList(), seen)
        assertEquals(DefaultFitChoice.SMART, choice)
    }

    @Test
    fun lastFixedModeWrapsBackToSmart() {
        // 版本 31 前的 P2 按钮只在四种固定方式间循环，选过固定方式后再也回不到“智能”
        assertEquals(DefaultFitChoice.SMART, DefaultFitChoice.CONTAIN.next())
    }

    @Test
    fun everyChoiceRoundTripsThroughThePreferenceStore() {
        for (choice in DefaultFitChoice.entries) {
            choice.save(preferences)

            assertEquals(choice, DefaultFitChoice.of(preferences.getP3Defaults()))
        }
    }

    @Test
    fun savingSmartSetsTheSmartFlagAndAFixedModeClearsIt() {
        DefaultFitChoice.SMART.save(preferences)
        assertTrue(preferences.getP3Defaults().smartFit)

        DefaultFitChoice.FIT_WIDTH.save(preferences)
        val defaults = preferences.getP3Defaults()
        assertFalse(defaults.smartFit)
        assertEquals(FitMode.FIT_WIDTH, defaults.fitMode)
    }

    @Test
    fun clickingThroughTheP2ButtonFromAFixedModeReachesSmart() {
        DefaultFitChoice.FIT_HEIGHT.save(preferences)

        // 与 P2 页按钮相同的读取—切换—保存路径
        repeat(2) { DefaultFitChoice.of(preferences.getP3Defaults()).next().save(preferences) }

        assertTrue(preferences.getP3Defaults().smartFit)
        assertEquals("智能", DefaultFitChoice.of(preferences.getP3Defaults()).label)
    }
}
