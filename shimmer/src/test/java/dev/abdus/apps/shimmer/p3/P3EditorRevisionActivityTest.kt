package dev.abdus.apps.shimmer.p3

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P3EditorRevisionActivityTest {
    private val targetId = "p3-revision-activity-test"
    private var controller: ActivityController<P3EditActivity>? = null
    private val timeout get() = ViewConfiguration.getDoubleTapTimeout().toLong() + 1
    private class Session : LiveEditBridge.EditSession {
        override var image = LiveEditBridge.EditImage("content://test/revision", 4000, 3000, 1220, 2656, ImageTransform(), P3Defaults())
        var open = true
        override val active get() = open
        val steps = mutableListOf<Triple<Boolean, Boolean, ImageTransform?>>()
        val previews = mutableListOf<ImageTransform>()
        val saves = mutableListOf<ImageTransform?>()
        var cancels = 0
        var deferred = false
        var pending: ((LiveEditBridge.StepResult?) -> Unit)? = null
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) { previews += transform }
        override fun apply(transform: ImageTransform?) = Unit
        override fun save(transform: ImageTransform?, saveUptimeMillis: Long) { saves += transform; open = false }
        override fun cancel() { cancels++; open = false }
        override fun statusLine() = ""
        override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
            steps += Triple(forward, saveFirst, transform)
            if (deferred) pending = onDone else onDone(LiveEditBridge.StepResult.NoCandidate)
        }
    }
    @After fun close() {
        controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        idle(timeout)
        LiveEditBridge.unregister(targetId)
    }
    @Test fun footerKeepsExactlyFourRequestedActionsInOrderWithoutSaveOrCancel() {
        open()
        val footer = tagged("p3-editor-footer") as ViewGroup
        assertEquals(4, footer.childCount)
        assertEquals(listOf("适配：铺满", "重置", "启用：开", "展开工具"),
            (0 until footer.childCount).map { (footer.getChildAt(it) as Button).text.toString() })
        assertFalse(buttons().any { it.text == "保存" || it.text == "取消" || it.text == "上一张" || it.text == "下一张" })
    }
    @Test fun toolsHaveOnlyRotationMirrorAndDebugPagesWithFourActionsInOneRow() {
        open(); click("展开工具")
        assertEquals(listOf("旋转镜像", "调试"), buttons().filter { it.tag?.toString()?.startsWith("p3-editor-tab-") == true }.map { it.text.toString() })
        assertFalse(buttons().any { it.text == "适配" || it.text == "位置" || it.text == "背景" })
        val group = tagged("p3-editor-group-ROTATE_MIRROR") as ViewGroup
        val first = group.getChildAt(0) as ViewGroup
        assertEquals(1, group.childCount)
        assertEquals(listOf("左右镜像", "上下镜像", "左转 90°", "右转 90°"), (0 until first.childCount).map { (first.getChildAt(it) as Button).text.toString() })
    }
    @Test fun transformDebugStartsHiddenAndUsesTheStatisticsToggle() {
        open(); click("展开工具"); click("调试")
        assertEquals(View.GONE, tagged("p3-editor-transform-state").visibility)
        assertEquals(View.GONE, tagged("p3-editor-stats").visibility)
        click("显示统计")
        assertEquals(View.VISIBLE, tagged("p3-editor-transform-state").visibility)
        assertEquals(View.VISIBLE, tagged("p3-editor-stats").visibility)
        click("旋转镜像"); click("收起工具")
        assertEquals(View.VISIBLE, tagged("p3-editor-stats").visibility)
        click("展开工具"); click("调试"); click("隐藏统计")
        assertEquals(View.GONE, tagged("p3-editor-transform-state").visibility)
        assertEquals(View.GONE, tagged("p3-editor-stats").visibility)
    }
    @Test fun toolFooterAndStatisticsUseMoreTransparentBlackWithOpaqueWhiteText() {
        open()
        listOf("p3-editor-tool-content", "p3-editor-footer", "p3-editor-stats-header").forEach {
            assertEquals(0x70000000, (tagged(it).background as ColorDrawable).color)
        }
        assertEquals(android.graphics.Color.WHITE, (tagged("p3-editor-stats") as android.widget.TextView).currentTextColor)
    }
    @Test fun exposedDoubleDispatchesNextOnlyAfterTheOriginalThirdWindow() {
        val s = open(); taps(2)
        assertTrue(s.steps.isEmpty()); idle(timeout)
        assertEquals(listOf(Triple(true, false, null)), s.steps)
        assertTrue(s.previews.isEmpty())
    }
    @Test fun exposedTripleDispatchesPreviousOnceAndNeverFirstNext() {
        val s = open(); taps(3); idle(timeout)
        assertEquals(listOf(Triple(false, false, null)), s.steps)
        assertTrue(s.previews.isEmpty())
    }
    @Test fun tinyMovesInADoubleDoNotDirtyTheDraftOrOpenASaveDialog() {
        val s = open(); taps(2, slightMove = true); idle(timeout)
        assertTrue(s.previews.isEmpty()); assertEquals(1, s.steps.size)
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
    }
    @Test fun dirtyDoubleOffersSaveDiscardAndContinueRatherThanAutosaving() {
        val s = open(); click("展开工具"); click("左右镜像"); taps(2); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertTrue(s.saves.isEmpty())
        val dialog = dialog()
        assertEquals("切换前保存修改？", shadowOf(dialog).title.toString())
        assertEquals("继续编辑", dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString())
        clickDialog(dialog, DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf(Triple(true, true, ImageTransform(mirrorHorizontal = true))), s.steps)
    }
    @Test fun dirtyTripleCanDiscardBeforeMovingPreviousWithoutSaving() {
        val s = open(); click("展开工具"); click("上下镜像"); taps(3)
        clickDialog(dialog(), DialogInterface.BUTTON_NEGATIVE)
        assertEquals(listOf(Triple(false, false, null)), s.steps); assertTrue(s.saves.isEmpty())
    }
    @Test fun resetDoubleStillRequiresConfigRemovalConfirmation() {
        val s = open(); click("重置"); taps(2); idle(timeout)
        assertTrue(s.steps.isEmpty()); clickDialog(dialog(), DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf(Triple(true, true, null)), s.steps)
    }
    @Test fun continuingAfterNavigationDialogPreservesDraftAndSession() {
        val s = open(); click("展开工具"); click("上下镜像"); taps(3)
        clickDialog(dialog(), DialogInterface.BUTTON_NEUTRAL)
        assertTrue(s.steps.isEmpty()); assertTrue(s.saves.isEmpty()); assertEquals(0, s.cancels)
        activity().onBackPressedDispatcher.onBackPressed()
        clickDialog(dialog(), DialogInterface.BUTTON_POSITIVE)
        assertTrue(s.saves.single()!!.mirrorVertical)
    }
    @Test fun modalDialogRejectsSurfaceTouchesAndPendingDeadlines() {
        val s = open(); click("重置"); taps(3)
        val dialog = dialog(); taps(3); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertTrue(s.previews.isEmpty())
        assertTrue(dialog.isShowing)
    }
    @Test fun modalDialogWindowConsumesTouchWithoutEditingTheUnderlyingWallpaper() {
        val s = open(); click("重置"); taps(3)
        val dialog = dialog()
        val decor = dialog.window!!.decorView
        shadowOf(Looper.getMainLooper()).idle()
        decor.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 800, 600)
        // Empty DecorView padding is not contractually clickable. Route actual events through
        // the dialog window to its visible neutral control and verify it receives the click.
        val neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        assertTrue(neutral.isShown && neutral.width > 0 && neutral.height > 0)
        val child = IntArray(2).also { neutral.getLocationOnScreen(it) }
        val parent = IntArray(2).also { decor.getLocationOnScreen(it) }
        val x = child[0] - parent[0] + neutral.width / 2f
        val y = child[1] - parent[1] + neutral.height / 2f
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEach { action ->
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, y, 0).also {
                assertTrue(dialog.dispatchTouchEvent(it))
                it.recycle()
            }
        }
        idle(timeout)
        assertTrue(s.steps.isEmpty()); assertTrue(s.previews.isEmpty()); assertTrue(s.saves.isEmpty())
        assertFalse(dialog.isShowing)
        assertEquals(0, s.cancels)
    }
    @Test fun aControlTapCancelsAnOutstandingDouble() {
        val s = open(); taps(2); click("展开工具"); idle(timeout)
        assertTrue(s.steps.isEmpty())
    }
    @Test fun toolPaddingTouchCancelsAnOutstandingDoubleWithoutEditing() {
        val s = open(); click("展开工具"); layout(); taps(2)
        touchOverlay(tagged("p3-editor-tool-content")); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertTrue(s.previews.isEmpty())
    }
    @Test fun footerPaddingTouchCancelsAnOutstandingDoubleWithoutEditing() {
        val s = open(); layout(); taps(2); touchOverlay(tagged("p3-editor-footer")); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertTrue(s.previews.isEmpty())
    }
    @Test fun statisticsPaddingTouchCancelsAnOutstandingDoubleWithoutEditing() {
        val s = open(); click("展开工具"); click("调试"); click("显示统计"); layout()
        taps(2); touchOverlay(tagged("p3-editor-stats")); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertTrue(s.previews.isEmpty())
    }
    @Test fun focusLossCancelsPendingDoubleWithoutDiscardingDraft() {
        val s = open(); taps(2); activity().onWindowFocusChanged(false); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertEquals(0, s.cancels)
    }
    @Test fun pauseCancelsPendingDoubleButDoesNotPrematurelyEndTheSession() {
        val s = open(); taps(2); controller!!.pause(); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertEquals(0, s.cancels)
    }
    @Test fun stopCancelsPendingDoubleAndOriginalUnsavedSession() {
        val s = open(); taps(2); controller!!.pause().stop(); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertEquals(1, s.cancels); assertTrue(s.saves.isEmpty())
    }
    @Test fun destroyClosesPendingTimerAndNeverCommitsNavigation() {
        val s = open(); taps(2); controller!!.pause().stop().destroy(); idle(timeout)
        assertTrue(s.steps.isEmpty()); assertEquals(1, s.cancels)
    }
    @Test fun newIntentCancelsPendingDoubleWithoutRebuildingOrClearingDraft() {
        val s = open(); taps(2)
        P3EditActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(activity(), Intent())
        idle(timeout); assertTrue(s.steps.isEmpty()); assertEquals(0, s.cancels)
    }
    @Test fun busyStepRejectsFurtherGestureNavigationAndBackStillCancels() {
        val s = open(); s.deferred = true; taps(2); idle(timeout); taps(3); idle(timeout)
        assertEquals(1, s.steps.size)
        activity().onBackPressedDispatcher.onBackPressed()
        assertEquals(1, s.cancels); assertTrue(s.saves.isEmpty())
    }
    @Test fun singleDragKeepsItsFullDistanceAfterThresholdRatherThanLosingEarlyMotion() {
        val s = open(); val slop = ViewConfiguration.get(activity()).scaledTouchSlop.toFloat()
        touch(surface(), MotionEvent.ACTION_DOWN, 300f, 400f)
        touch(surface(), MotionEvent.ACTION_MOVE, 300f + slop / 2, 400f)
        assertTrue(s.previews.isEmpty())
        touch(surface(), MotionEvent.ACTION_MOVE, 400f, 400f)
        assertEquals(100f / 1220, s.previews.last().offsetX, 1e-5f)
        touch(surface(), MotionEvent.ACTION_UP, 400f, 400f); idle(timeout)
        assertTrue(s.steps.isEmpty())
    }
    @Test fun screenReaderNavigationUsesTheSameDirtyConfirmationPath() {
        val s = open(); click("重置")
        val info = AccessibilityNodeInfoCompat.wrap(surface().createAccessibilityNodeInfo())
        val action = info.actionList.first { it.label?.toString() == "下一张图片" }
        assertTrue(ViewCompat.performAccessibilityAction(surface(), action.id, null))
        assertTrue(s.steps.isEmpty()); clickDialog(dialog(), DialogInterface.BUTTON_NEGATIVE)
        assertEquals(listOf(Triple(true, false, null)), s.steps)
    }
    private fun open(): Session {
        val s = Session()
        LiveEditBridge.register(targetId, object : LiveEditBridge.Target { override fun beginEdit() = s })
        LiveEditBridge.markVisible(targetId)
        controller = Robolectric.buildActivity(P3EditActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return s
    }
    private fun activity() = controller!!.get()
    private fun views(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else listOf(v)
    private fun tagged(tag: String) = views(activity().window.decorView).first { it.tag == tag }
    private fun buttons() = views(activity().window.decorView).filterIsInstance<Button>()
    private fun click(label: String) { assertTrue(buttons().first { it.text.toString() == label }.performClick()); shadowOf(Looper.getMainLooper()).idle() }
    private fun surface() = views(activity().window.decorView).first { it.contentDescription?.startsWith("壁纸编辑区") == true }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun taps(count: Int, slightMove: Boolean = false) {
        repeat(count) {
            touch(surface(), MotionEvent.ACTION_DOWN, 300f, 400f); idle(20)
            if (slightMove) touch(surface(), MotionEvent.ACTION_MOVE, 301f, 400f)
            touch(surface(), MotionEvent.ACTION_UP, if (slightMove) 301f else 300f, 400f)
            if (it < count - 1) idle(40)
        }
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { assertTrue(view.dispatchTouchEvent(it)); it.recycle() }
    }
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun clickDialog(dialog: AlertDialog, which: Int) { assertTrue(dialog.getButton(which).performClick()); shadowOf(Looper.getMainLooper()).idle() }
    private fun root() = tagged("p3-editor-dock").parent as View
    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        // Use the complete window/decor rather than a root larger than its attached ViewRoot.
        val decor = activity().window.decorView
        repeat(2) { decor.measure(View.MeasureSpec.makeMeasureSpec(1220, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2656, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, 1220, 2656) }
    }
    private fun touchOverlay(view: View) {
        val child = IntArray(2).also { view.getLocationOnScreen(it) }
        val parent = IntArray(2).also { activity().window.decorView.getLocationOnScreen(it) }
        val x = (child[0] - parent[0] + 1).toFloat()
        val y = (child[1] - parent[1] + 1).toFloat()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, y, 0).also {
                assertTrue(activity().dispatchTouchEvent(it))
                it.recycle()
            }
        }
    }
}
