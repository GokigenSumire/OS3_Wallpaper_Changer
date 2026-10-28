package dev.abdus.apps.shimmer.p9

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P9-STATIC-01 回归测试。
 *
 * 用户在版本 33 反馈："静态验收模式开关在进出旧 ui 时状态不唯一，进出时会打开冲掉新 ui 下的
 * 开关状态设置"。根因是 `P2PlaybackActivity.onCreate` 每次都无条件 `setP2StaticTestMode(true)`。
 *
 * 这里不启动 Activity（Robolectric 不跑真实 GLES 与壁纸服务），而是测 Activity 现在依赖的
 * 判定契约 [WallpaperPreferences.hasP2StaticTestModeChoice]：它必须能区分"从未设置过"与
 * "用户已经选过（含显式关闭）"，否则修复无从成立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P9StaticModeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preferences: WallpaperPreferences

    @Before
    fun setUp() {
        preferences = WallpaperPreferences.create(context)
    }

    /** 全新安装：键不存在，P2 页据此落一次默认值。 */
    @Test
    fun freshInstallHasNoStoredChoice() {
        assertFalse(preferences.hasP2StaticTestModeChoice())
        assertFalse(preferences.isP2StaticTestMode())
    }

    /** 关键用例：用户显式关闭后，P2 页不得再把它改回 true。 */
    @Test
    fun explicitDisableIsRememberedAndNotTreatedAsUnset() {
        preferences.setP2StaticTestMode(false)

        // false 必须算"已选择"。若这里返回 false，P2 页会误判为未设置并写回 true，
        // 正是用户报告的覆盖现象。
        assertTrue(preferences.hasP2StaticTestModeChoice())
        assertFalse(preferences.isP2StaticTestMode())
    }

    /** 显式开启同样算已选择。 */
    @Test
    fun explicitEnableIsRemembered() {
        preferences.setP2StaticTestMode(true)

        assertTrue(preferences.hasP2StaticTestModeChoice())
        assertTrue(preferences.isP2StaticTestMode())
    }

    /**
     * 模拟用户报告的往返场景：新 UI 关闭 → 进出旧 P2 页多次 → 设置必须仍为关闭。
     *
     * 循环体复刻修复后的 onCreate 逻辑：只在没有存过选择时落默认值。
     */
    @Test
    fun newUiChoiceSurvivesRepeatedLegacyPageEntry() {
        // 用户在新 UI 设置页关闭静态验收模式
        preferences.setP2StaticTestMode(false)

        repeat(10) {
            // 修复后的 P2PlaybackActivity.onCreate 行为
            if (!preferences.hasP2StaticTestModeChoice()) {
                preferences.setP2StaticTestMode(true)
            }
        }

        assertFalse("进出旧 UI 10 次后用户的关闭选择被覆盖", preferences.isP2StaticTestMode())
    }

    /** 全新安装后首次进入旧页：落默认值 true，此后不再变动。 */
    @Test
    fun firstLegacyEntryOnFreshInstallSetsDefaultOnceThenStaysPut() {
        if (!preferences.hasP2StaticTestModeChoice()) {
            preferences.setP2StaticTestMode(true)
        }
        assertTrue(preferences.isP2StaticTestMode())

        // 用户随后在任一侧关闭
        preferences.setP2StaticTestMode(false)

        // 再次进入旧页不得翻转
        repeat(3) {
            if (!preferences.hasP2StaticTestModeChoice()) {
                preferences.setP2StaticTestMode(true)
            }
        }
        assertFalse(preferences.isP2StaticTestMode())
    }

    /** 新旧两侧共读同一个键，任一侧的写入对另一侧立即可见。 */
    @Test
    fun bothUisShareOneSourceOfTruth() {
        val fromNewUi = WallpaperPreferences.create(context)
        val fromLegacyPage = WallpaperPreferences.create(context)

        fromNewUi.setP2StaticTestMode(true)
        assertTrue(fromLegacyPage.isP2StaticTestMode())

        fromLegacyPage.setP2StaticTestMode(false)
        assertFalse(fromNewUi.isP2StaticTestMode())
    }
}
