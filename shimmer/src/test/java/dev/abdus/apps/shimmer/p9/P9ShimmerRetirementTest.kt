package dev.abdus.apps.shimmer.p9

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p0.P0Application
import dev.abdus.apps.shimmer.p5.TransitionChoice
import dev.abdus.apps.shimmer.p5.TransitionSettings
import dev.abdus.apps.shimmer.p5.TransitionSettingsStore
import dev.abdus.apps.shimmer.ui.settings.P8SettingsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * P9-STATIC-SHIMMER-01 回归测试。
 *
 * 用户在版本 39 首次安装设备上报告"shimmer原特效在关闭静态验收仍存在，此为重大事故"。
 * 根因：新 UI 关闭开关只翻转标志；全新安装时偏好里是上游默认值（模糊 0.5、暗化 0.1、色差开、
 * 特效过渡 1500 ms），标志一关就全部显露。旧 P2 页关闭时也没有清零暗化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P9ShimmerRetirementTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        ShimmerDatabase.getInstance(application).close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    /** 清空偏好文件，回到 Application 尚未清零之前的全新安装状态。 */
    private fun freshInstallPreferences(): WallpaperPreferences {
        application.getSharedPreferences("shimmer_wallpaper_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        return WallpaperPreferences.create(application)
    }

    private fun assertNoShimmerEffect(preferences: WallpaperPreferences) {
        assertEquals(0f, preferences.getBlurAmount())
        assertEquals("暗化必须清零（旧 P2 页曾漏掉）", 0f, preferences.getDimAmount())
        assertFalse(preferences.getDuotoneSettings().enabled)
        assertFalse(preferences.getDuotoneSettings().alwaysOn)
        assertFalse(preferences.getGrainSettings().enabled)
        assertFalse(preferences.getChromaticAberrationSettings().enabled)
        assertEquals(0L, preferences.getEffectTransitionDurationMillis())
        assertFalse(preferences.isBlurOnScreenLockEnabled())
        assertFalse(preferences.isBlurTimeoutEnabled())
        assertFalse(preferences.isUnblurOnUnlockEnabled())
        assertFalse(preferences.hasActiveShimmerEffects())
    }

    /** 复现前提：全新安装的偏好就是上游特效全开的状态，这正是事故画面的来源。 */
    @Test
    fun freshInstallDefaultsAreTheUpstreamShimmerEffects() {
        val preferences = freshInstallPreferences()

        assertFalse("全新安装时静态验收为关闭", preferences.isP2StaticTestMode())
        assertTrue(preferences.hasActiveShimmerEffects())
        assertTrue(preferences.getBlurAmount() > 0f)
        assertTrue(preferences.getDimAmount() > 0f)
        assertTrue(preferences.getChromaticAberrationSettings().enabled)
    }

    /** 应用进程启动即清零：测试进程的 Application 就是清单里的 P0Application。 */
    @Test
    fun applicationStartRetiresEffectsBeforeAnyScreenOrEngine() {
        assertTrue(application is P0Application)

        assertNoShimmerEffect(WallpaperPreferences.create(application))
    }

    @Test
    fun retirementClearsEveryEffectOnFreshInstall() {
        val preferences = freshInstallPreferences()

        assertTrue(preferences.retireShimmerEffectsOnce())

        assertNoShimmerEffect(preferences)
    }

    /** 只清零一次：用户之后在特效页的调整不得在下次启动时被抹掉。 */
    @Test
    fun retirementRunsOnceAndKeepsLaterChoices() {
        val preferences = freshInstallPreferences()
        preferences.retireShimmerEffectsOnce()

        preferences.setBlurAmount(0.3f)
        preferences.setDimAmount(0.2f)

        assertFalse(preferences.retireShimmerEffectsOnce())
        assertEquals(0.3f, preferences.getBlurAmount())
        assertEquals(0.2f, preferences.getDimAmount())
    }

    /** 两个开关共用的关闭路径：特效全部清零、标志为关闭。 */
    @Test
    fun exitStaticTestModeClearsEffectsIncludingDim() {
        val preferences = freshInstallPreferences()
        preferences.setP2StaticTestMode(true)

        preferences.exitStaticTestMode()

        assertFalse(preferences.isP2StaticTestMode())
        assertTrue(preferences.hasP2StaticTestModeChoice())
        assertNoShimmerEffect(preferences)
    }

    /** 新 UI 设置页的开关：版本 39 只翻转标志，这里必须走同一个清零路径。 */
    @Test
    fun newUiToggleOffClearsEffectsOnFreshInstall() {
        val preferences = freshInstallPreferences()
        preferences.setP2StaticTestMode(true)
        // 全局 getInstance 会保留第一次 Application；每个 Robolectric 宿主必须使用自己的沙箱。
        val viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[
            P8SettingsViewModel::class.java,
        ]
        assertSame("ViewModel 与断言必须访问同一 Application 的真实偏好", application,
            viewModel.getApplication<Application>())

        toggleAndAwaitIssuedTask(viewModel, false)

        assertFalse(preferences.isP2StaticTestMode())
        assertNoShimmerEffect(preferences)

        toggleAndAwaitIssuedTask(viewModel, true)
        assertTrue(preferences.isP2StaticTestMode())
    }

    /** 等待这次真实切换任务，不把一次 Main idle 当成所有宿主调度器都已完成。 */
    private fun toggleAndAwaitIssuedTask(viewModel: P8SettingsViewModel, enabled: Boolean) {
        val scopeJob = checkNotNull(viewModel.viewModelScope.coroutineContext[Job])
        val existing = scopeJob.children.toSet()
        viewModel.toggleStaticAcceptance(enabled)
        val issued = scopeJob.children.filterNot { it in existing }.toList()
        shadowOf(Looper.getMainLooper()).idle()
        // 原切换任务没有挂起点或 IO→Main 续体；只等刚发起的子任务，不等待其它刷新。
        // 立即执行并已结束时 issued 为空是正常完成；未执行或取消仍按真实结果失败。
        runBlocking { withTimeout(5_000) { issued.joinAll() } }
        assertTrue("本次切换任务应正常完成而非取消", issued.all { it.isCompleted && !it.isCancelled })
    }

    /** 清零只动 Shimmer 特效，P5 图片过渡是另一份偏好，关闭静态验收后应照常生效。 */
    @Test
    fun clearingShimmerEffectsLeavesP5TransitionUntouched() {
        val transitions = TransitionSettingsStore(application)
        val chosen = TransitionSettings(choice = TransitionChoice.SLIDE, durationMillis = 900)
        transitions.save(chosen)
        val preferences = freshInstallPreferences()

        preferences.retireShimmerEffectsOnce()
        preferences.exitStaticTestMode()

        assertEquals(chosen.normalized(), transitions.load())
    }
}
