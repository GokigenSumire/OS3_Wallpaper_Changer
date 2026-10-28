package dev.abdus.apps.shimmer.p3

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.abdus.apps.shimmer.WallpaperPreferences
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P3EditorFollowUpActivityTest {
    private val targetId = "p3-follow-up-test"
    private var controller: ActivityController<P3EditActivity>? = null
    private val timeout get() = ViewConfiguration.getDoubleTapTimeout().toLong() + 1
    private class Session(enabled: Boolean?) : LiveEditBridge.EditSession {
        override var image = LiveEditBridge.EditImage("content://test/follow-up", 4000, 3000, 1220, 2656, ImageTransform(), P3Defaults(), enabled)
        var open = true
        override val active get() = open
        val previews = mutableListOf<ImageTransform>()
        val saves = mutableListOf<ImageTransform?>()
        val directions = mutableListOf<Boolean>()
        var cancels = 0
        var deferStep = false
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) { previews += transform }
        override fun apply(transform: ImageTransform?) = Unit
        override fun save(transform: ImageTransform?, saveUptimeMillis: Long) { saves += transform; open = false }
        override fun cancel() { cancels++; open = false }
        override fun statusLine() = "当前测试编辑会话"
        override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
            directions += forward
            if (!deferStep) onDone(LiveEditBridge.StepResult.NoCandidate)
        }
    }
    @After fun close() {
        controller?.let {
            if (!it.get().isDestroyed) it.pause().stop().destroy()
            WallpaperPreferences.create(it.get()).setImageEnabled("content://test/follow-up", true)
        }
        idle(timeout)
        LiveEditBridge.unregister(targetId)
    }
    @Test fun shortEnableLabelsStillToggleRealImagePreferenceWithoutSavingDraft() {
        val s = open()
        assertTrue(button("启用：开").isEnabled)
        click("启用：开")
        assertTrue(button("启用：关").isEnabled)
        assertTrue(s.image.imageId in WallpaperPreferences.create(activity()).getDisabledImages())
        click("启用：关")
        assertFalse(s.image.imageId in WallpaperPreferences.create(activity()).getDisabledImages())
        assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty()); assertEquals(0, s.cancels)
    }
    @Test fun defaultImageKeepsTheNonApplicableDisabledFooterState() {
        val s = open(enabled = null)
        assertFalse(button("默认图片").isEnabled)
        button("默认图片").performClick()
        assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
    }
    @Test fun selectedAndUnselectedTabsUseWhiteLabelsAndDistinctOutlineBackgrounds() {
        open(); click("展开工具")
        val rotate = button("旋转镜像")
        val debug = button("调试")
        assertEquals(Color.WHITE, rotate.currentTextColor)
        assertEquals(Color.WHITE, debug.currentTextColor)
        assertTrue(rotate.isSelected); assertFalse(debug.isSelected)
        assertEquals(0xB0000000.toInt(), (rotate.background as GradientDrawable).color!!.defaultColor)
        assertEquals(0x30000000, (debug.background as GradientDrawable).color!!.defaultColor)
        click("调试")
        assertFalse(rotate.isSelected); assertTrue(debug.isSelected)
        assertEquals(0x30000000, (rotate.background as GradientDrawable).color!!.defaultColor)
        assertEquals(0xB0000000.toInt(), (debug.background as GradientDrawable).color!!.defaultColor)
        assertTrue(debug.contentDescription.toString().contains("已选中"))
    }
    @Test fun changingOutlineTabsNeverMutatesOrSavesTheDraft() {
        val s = open(); click("展开工具"); click("调试"); click("旋转镜像")
        assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty()); assertEquals(0, s.cancels)
    }
    @Test fun mirrorAndRotationControlsShareExactlyOneRowInRequestedOrder() {
        open(); click("展开工具")
        val group = tagged("p3-editor-group-ROTATE_MIRROR") as ViewGroup
        assertEquals(1, group.childCount)
        val row = group.getChildAt(0) as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, row.orientation)
        assertEquals(listOf("左右镜像", "上下镜像", "左转 90°", "右转 90°"),
            (0 until row.childCount).map { (row.getChildAt(it) as Button).text.toString() })
        val minimum = (48 * activity().resources.displayMetrics.density).toInt()
        (0 until row.childCount).forEach {
            assertTrue(row.getChildAt(it).minimumHeight >= minimum)
            assertEquals(1f, (row.getChildAt(it).layoutParams as LinearLayout.LayoutParams).weight, 0f)
        }
    }
    @Test fun fourActionsStillUseTheOriginalMirrorAndQuarterTurnMath() {
        val s = open(); click("展开工具")
        click("左右镜像"); assertTrue(s.previews.last().mirrorHorizontal)
        click("上下镜像"); assertTrue(s.previews.last().mirrorVertical)
        click("左转 90°"); assertEquals(-90f, s.previews.last().rotationDegrees, 1e-5f)
        click("右转 90°"); assertEquals(0f, s.previews.last().rotationDegrees, 1e-5f)
        assertTrue(s.previews.last().mirrorHorizontal && s.previews.last().mirrorVertical)
        assertTrue(s.saves.isEmpty())
    }
    @Test fun transformDescriptionAndStatsAreChildrenOfTheTopHeaderNotTheTools() {
        open()
        val header = tagged("p3-editor-stats-header") as ViewGroup
        assertSame(header, tagged("p3-editor-transform-state").parent)
        assertSame(header, tagged("p3-editor-stats").parent)
        assertEquals(2, header.childCount)
        assertSame(tagged("p3-editor-transform-state"), header.getChildAt(0))
        assertEquals(Gravity.TOP, (header.layoutParams as FrameLayout.LayoutParams).gravity)
        assertFalse(descendants(tagged("p3-editor-tool-content")).any { it.tag == "p3-editor-transform-state" })
    }
    @Test fun topHeaderAndBothDiagnosticChildrenStartHidden() {
        open()
        listOf("p3-editor-stats-header", "p3-editor-transform-state", "p3-editor-stats").forEach {
            assertEquals(View.GONE, tagged(it).visibility)
        }
    }
    @Test fun statisticsToggleShowsAndHidesAllThreeAsOneUnit() {
        open(); showStats()
        listOf("p3-editor-stats-header", "p3-editor-transform-state", "p3-editor-stats").forEach { assertEquals(View.VISIBLE, tagged(it).visibility) }
        click("隐藏统计")
        listOf("p3-editor-stats-header", "p3-editor-transform-state", "p3-editor-stats").forEach { assertEquals(View.GONE, tagged(it).visibility) }
    }
    @Test fun statisticsHeaderHasOneTransparentBlackLayerAndTransparentChildren() {
        open()
        assertEquals(0x70000000, (tagged("p3-editor-stats-header").background as ColorDrawable).color)
        listOf("p3-editor-transform-state", "p3-editor-stats").forEach {
            assertEquals(Color.TRANSPARENT, (tagged(it).background as ColorDrawable).color)
            assertEquals(Color.WHITE, (tagged(it) as TextView).currentTextColor)
        }
    }
    @Test fun hidingAndCollapsingToolsKeepsTopStatisticsVisibleWithoutSaving() {
        val s = open(); showStats(); click("收起工具")
        assertTrue(tagged("p3-editor-stats-header").isShown)
        assertTrue(tagged("p3-editor-transform-state").isShown)
        assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
    }
    @Test fun topDescriptionUpdatesTheLiveDraftAfterMirroringAndPolling() {
        open(); showStats(); click("旋转镜像"); click("左右镜像")
        assertTrue((tagged("p3-editor-transform-state") as TextView).text.toString().contains("镜像 左右"))
        assertTrue((tagged("p3-editor-transform-state") as TextView).text.toString().contains("未保存"))
    }
    @Test fun headerInsetsApplyOnceAtTheContainerWithoutShrinkingTheSurface() {
        open(); showStats(); layout()
        val header = tagged("p3-editor-stats-header")
        val stats = tagged("p3-editor-stats")
        val originalStatsPadding = listOf(stats.paddingLeft, stats.paddingTop, stats.paddingRight, stats.paddingBottom)
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(30, 100, 40, 150)).build()
        ViewCompat.dispatchApplyWindowInsets(root(), insets)
        layout()
        val density = activity().resources.displayMetrics.density
        assertEquals((8 * density).toInt() + 30, header.paddingLeft)
        assertEquals((6 * density).toInt() + 100, header.paddingTop)
        assertEquals((8 * density).toInt() + 40, header.paddingRight)
        assertEquals(originalStatsPadding, listOf(stats.paddingLeft, stats.paddingTop, stats.paddingRight, stats.paddingBottom))
        assertEquals(1220, surface().width); assertEquals(2656, surface().height)
    }
    @Test fun touchingTransformTextInterruptsPendingDoubleWithoutNavigationOrPreview() {
        val s = open(); showStats(); layout(); pendingDouble()
        touchOverlay(tagged("p3-editor-transform-state")); idle(timeout)
        assertTrue(s.directions.isEmpty()); assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
    }
    @Test fun touchingTopHeaderPaddingInterruptsPendingDoubleWithoutNavigationOrPreview() {
        val s = open(); showStats(); layout(); pendingDouble()
        touchOverlay(tagged("p3-editor-stats-header")); idle(timeout)
        assertTrue(s.directions.isEmpty()); assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
    }
    @Test fun touchingTopStatisticsInterruptsPendingDoubleWithoutNavigationOrPreview() {
        val s = open(); showStats(); layout(); pendingDouble()
        touchOverlay(tagged("p3-editor-stats")); idle(timeout)
        assertTrue(s.directions.isEmpty()); assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
    }
    @Test fun hiddenHeaderDoesNotBlockAnExposedDoubleFromItsPreviousArea() {
        val s = open(); showStats(); click("隐藏统计"); layout(); pendingDouble(); idle(timeout)
        assertEquals(listOf(true), s.directions)
    }
    @Test fun stepBusyStillDisablesAllFourTransformActionsAndEnable() {
        val s = open(); s.deferStep = true; click("展开工具"); pendingDouble(); idle(timeout)
        listOf("左右镜像", "上下镜像", "左转 90°", "右转 90°", "启用：开").forEach { assertFalse(button(it).isEnabled) }
        button("左右镜像").performClick()
        assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
        activity().onBackPressedDispatcher.onBackPressed()
        assertEquals(1, s.cancels)
    }
    @Test fun compactLayoutStillKeepsOnlyOneToolPageVisible() {
        open(); click("展开工具"); click("调试")
        assertEquals(View.GONE, tagged("p3-editor-group-ROTATE_MIRROR").visibility)
        assertEquals(View.VISIBLE, tagged("p3-editor-group-DEBUG").visibility)
        click("旋转镜像")
        assertEquals(View.VISIBLE, tagged("p3-editor-group-ROTATE_MIRROR").visibility)
        assertEquals(View.GONE, tagged("p3-editor-group-DEBUG").visibility)
    }
    @Test fun outlinedTabTapInterruptsPendingDoubleWithoutSaving() {
        val s = open(); click("展开工具"); pendingDouble(); click("调试"); idle(timeout)
        assertTrue(s.directions.isEmpty()); assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
    }
    private fun open(enabled: Boolean? = true): Session {
        val s = Session(enabled)
        LiveEditBridge.register(targetId, object : LiveEditBridge.Target { override fun beginEdit() = s })
        LiveEditBridge.markVisible(targetId)
        controller = Robolectric.buildActivity(P3EditActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return s
    }
    private fun showStats() { click("展开工具"); click("调试"); click("显示统计") }
    private fun activity() = controller!!.get()
    private fun descendants(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else listOf(v)
    private fun tagged(tag: String) = descendants(activity().window.decorView).first { it.tag == tag }
    private fun button(label: String) = descendants(activity().window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }
    private fun click(label: String) { assertTrue(button(label).performClick()); shadowOf(Looper.getMainLooper()).idle() }
    private fun surface() = descendants(activity().window.decorView).first { it.contentDescription?.startsWith("壁纸编辑区") == true }
    private fun root() = tagged("p3-editor-dock").parent as View
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun pendingDouble() {
        repeat(2) {
            touch(surface(), MotionEvent.ACTION_DOWN, 300f, 700f); idle(20)
            touch(surface(), MotionEvent.ACTION_UP, 300f, 700f)
            if (it == 0) idle(40)
        }
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { assertTrue(view.dispatchTouchEvent(it)); it.recycle() }
    }
    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity().window.decorView
        repeat(2) { decor.measure(View.MeasureSpec.makeMeasureSpec(1220, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2656, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, 1220, 2656) }
    }
    private fun touchOverlay(view: View) {
        val child = IntArray(2).also { view.getLocationOnScreen(it) }
        val parent = IntArray(2).also { activity().window.decorView.getLocationOnScreen(it) }
        val x = (child[0] - parent[0] + 1).toFloat(); val y = (child[1] - parent[1] + 1).toFloat()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, y, 0).also { assertTrue(activity().dispatchTouchEvent(it)); it.recycle() }
        }
    }
}
