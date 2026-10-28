package dev.abdus.apps.shimmer.ui.theme

import android.app.UiModeManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeModeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ThemeModeStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun unknownOrMissingValuesFollowTheSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("night"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("DARK"))
    }

    @Test
    fun onlyTheSystemOptionDependsOnTheSystem() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun eachOptionMapsToTheMatchingApplicationNightMode() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeMode.SYSTEM.uiModeManagerValue())
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeMode.LIGHT.uiModeManagerValue())
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.DARK.uiModeManagerValue())
    }

    @Test
    fun theChoiceSurvivesANewStoreInstance() {
        assertEquals(ThemeMode.SYSTEM, ThemeModeStore(context).load())

        ThemeModeStore(context).save(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, ThemeModeStore(context).load())
    }

    @Test
    fun theMainScreenHearsTheChangeAndStopsAfterUnregistering() {
        val store = ThemeModeStore(context)
        val heard = mutableListOf<ThemeMode>()
        val listener = store.observe { heard += it }

        store.save(ThemeMode.LIGHT)
        store.stopObserving(listener)
        store.save(ThemeMode.DARK)

        assertEquals(listOf(ThemeMode.LIGHT), heard)
    }

    @Test
    fun legacyPagesReceiveTheApplicationNightMode() {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)

        ThemeModeStore.applyToLegacyPages(context, ThemeMode.DARK)
        assertEquals(UiModeManager.MODE_NIGHT_YES, shadowOf(uiModeManager).applicationNightMode)

        ThemeModeStore.applyToLegacyPages(context, ThemeMode.SYSTEM)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, shadowOf(uiModeManager).applicationNightMode)
    }

    @Test
    fun theUiChoiceIsStoredApartFromTheWallpaperPreferences() {
        // 壁纸服务监听壁纸偏好文件；主题写入那里会让壁纸无故刷新
        ThemeModeStore(context).save(ThemeMode.DARK)

        assertTrue(context.getSharedPreferences(ThemeModeStore.PREFS_NAME, Context.MODE_PRIVATE).contains(ThemeModeStore.KEY_THEME_MODE))
        // 与 WallpaperPreferences.PREFS_NAME（私有）一致
        assertFalse(
            context.getSharedPreferences("shimmer_wallpaper_prefs", Context.MODE_PRIVATE)
                .contains(ThemeModeStore.KEY_THEME_MODE),
        )
        assertTrue(ThemeModeStore.PREFS_NAME != "shimmer_wallpaper_prefs")
    }
}
