package dev.abdus.apps.shimmer.p5

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/** P5 page: choices are saved at once and the diagnostics are a fresh, copyable reading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P5TransitionActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = TransitionSettingsStore(context)
    private val preferences = WallpaperPreferences.create(context)
    private var controller: ActivityController<P5TransitionActivity>? = null

    @Before
    fun clearState() {
        context.getSharedPreferences(TransitionSettingsStore.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        preferences.setP2StaticTestMode(false)
        TransitionStats.shared.reset()
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        idle()
        preferences.setP2StaticTestMode(false)
        TransitionStats.shared.reset()
    }

    @Test
    fun anUpgradeShowsTheDefaultsChecked() {
        val activity = open()
        listOf("淡入淡出", "1500 ms", "跟随播放方向").forEach { assertTrue(it, radio(activity, it).isChecked) }
        assertTrue(texts(activity).any { it.startsWith("当前设置：淡入淡出，时长 1500 ms\n") })
        assertFalse(texts(activity).any { it.startsWith("注意：P2 静态验收模式已开启") })
    }

    @Test
    fun everyChoiceIsSavedAtOnce() {
        val activity = open()
        choose(activity, "方向滑动")
        assertEquals(TransitionChoice.SLIDE, store.load().choice)
        choose(activity, "300 ms")
        choose(activity, "向上（新图从下方进入）")
        assertEquals(TransitionSettings(TransitionChoice.SLIDE, 300, SlideDirectionChoice.UP), store.load())
        assertTrue(radio(activity, "方向滑动").isChecked && radio(activity, "300 ms").isChecked)
        assertTrue(texts(activity).any { it.startsWith("当前设置：方向滑动，时长 300 ms，滑动方向 向上（新图从下方进入）") })

        choose(activity, "无动画")
        assertEquals(TransitionSettings(TransitionChoice.NONE, 300, SlideDirectionChoice.UP), store.load())
    }

    @Test
    fun staticModeIsExplainedOnThePage() {
        preferences.setP2StaticTestMode(true)
        val activity = open()
        assertTrue(texts(activity).any { it.startsWith("注意：P2 静态验收模式已开启") })
    }

    @Test
    fun copyTakesAFreshReadingAndClearingKeepsSettings() {
        store.save(TransitionSettings(TransitionChoice.ZOOM, 800))
        val activity = open()
        TransitionStats.shared.open(TransitionEffect.ZOOM, 800).apply { frame(2_000_000L, 0L) }
            .finish(false, 810L, 0, 2, 1, 1L, "a")
        click(activity, "复制诊断")
        assertEquals("已复制诊断", ShadowToast.getTextOfLatestToast())
        val lines = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString().lines()
        assertEquals("P5 过渡诊断", lines[0])
        assertEquals("当前设置：缩放，时长 800 ms", lines[1])
        assertTrue(lines.any { it.startsWith("X1 过渡帧 CPU 绘制：1 帧") && it.endsWith("INSUFFICIENT（样本不足）") })
        assertTrue(lines.contains("#1 ZOOM 800ms frames=1 810ms residual=0"))

        click(activity, "清空诊断记录（开始新一轮测量）")
        requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(0, TransitionStats.shared.snapshot().transitions)
        assertEquals(TransitionSettings(TransitionChoice.ZOOM, 800), store.load())
        assertFalse(texts(activity).any { it.contains("#1 ZOOM") })
    }

    private fun open(): P5TransitionActivity {
        controller = Robolectric.buildActivity(P5TransitionActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun radio(activity: P5TransitionActivity, label: String): RadioButton =
        views(activity.window.decorView).filterIsInstance<RadioButton>().single { it.text.toString() == label }

    private fun choose(activity: P5TransitionActivity, label: String) {
        radio(activity, label).performClick()
        idle()
    }

    private fun click(activity: P5TransitionActivity, label: String) {
        views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }.performClick()
        idle()
    }

    private fun texts(activity: P5TransitionActivity): List<String> =
        views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
