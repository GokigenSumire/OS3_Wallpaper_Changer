package dev.abdus.apps.shimmer.p3

import android.content.Intent
import android.content.DialogInterface
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import java.time.Duration
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
class P3EditorPanelActivityTest {
    private val targetId = "p3-editor-panel-test"
    private var controller: ActivityController<P3EditActivity>? = null

    private class Session : LiveEditBridge.EditSession {
        override var image = LiveEditBridge.EditImage("content://test/editor", 4000, 3000, 1220, 2656, ImageTransform(), P3Defaults())
        var open = true
        override val active: Boolean get() = open
        val previews = mutableListOf<ImageTransform>()
        val saves = mutableListOf<ImageTransform?>()
        var cancels = 0
        var steps = 0
        val savedBeforeSteps = mutableListOf<Pair<Boolean, ImageTransform?>>()
        var deferStep = false
        var pendingStep: ((LiveEditBridge.StepResult?) -> Unit)? = null
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) { previews += transform }
        override fun apply(transform: ImageTransform?) = Unit
        override fun save(transform: ImageTransform?, saveUptimeMillis: Long) { saves += transform; open = false }
        override fun cancel() { cancels++; open = false }
        override fun statusLine(): String = ""
        override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
            steps++
            savedBeforeSteps += saveFirst to transform
            if (deferStep) pendingStep = onDone else onDone(LiveEditBridge.StepResult.NoCandidate)
        }
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        LiveEditBridge.unregister(targetId)
    }

    @Test fun gestureEntryStartsWithToolsAndStatisticsHiddenButExitActionsReachable() {
        open(gesture = true)
        assertEquals(View.GONE, tagged("p3-editor-tools").visibility)
        assertEquals(View.GONE, tagged("p3-editor-stats").visibility)
        assertTrue(visible(button("展开工具")))
        assertTrue(visible(button("重置")))
        assertFalse(views(activity().window.decorView).filterIsInstance<Button>().any { it.text == "保存" || it.text == "取消" })
    }

    @Test fun applicationBackupEntryUsesTheSameCollapsedDefault() {
        open()
        assertEquals(View.GONE, tagged("p3-editor-tools").visibility)
        assertTrue(visible(button("展开工具")))
    }

    @Test fun expandingAndCollapsingToolsNeitherPreviewsNorSavesTheDraft() {
        val session = open()
        click("展开工具")
        assertEquals(View.VISIBLE, tagged("p3-editor-tools").visibility)
        click("收起工具")
        assertEquals(View.GONE, tagged("p3-editor-tools").visibility)
        assertTrue(session.previews.isEmpty())
        assertTrue(session.saves.isEmpty())
        assertEquals(0, session.cancels)
    }

    @Test fun everyTabShowsOnlyItsOwnGroupAndDoesNotMutateTheDraft() {
        val session = open()
        click("展开工具")
        EditorToolGroup.entries.forEach { group ->
            click(group.label)
            EditorToolGroup.entries.forEach { other ->
                assertEquals(if (other == group) View.VISIBLE else View.GONE, tagged("p3-editor-group-${other.name}").visibility)
            }
            assertTrue(tagged("p3-editor-tab-${group.name}").isSelected)
        }
        assertTrue(session.previews.isEmpty())
        assertTrue(session.saves.isEmpty())
    }

    @Test fun hiddenGroupControlsAreNotUserReachable() {
        open()
        assertFalse(visible(button("左右镜像")))
        click("展开工具")
        assertTrue(visible(button("左右镜像")))
        click("旋转镜像")
        assertTrue(visible(button("左右镜像")))
        click("调试")
        assertFalse(visible(button("左右镜像")))
        assertTrue(visible(button("显示统计")))
    }

    @Test fun accessiblePositionActionsUseTheSamePreviewPipelineAfterButtonsWereRemoved() {
        val session = open()
        position("放大")
        assertEquals(1.1f, session.previews.last().userScale, 1e-5f)
        position("图片左移")
        assertEquals(-0.05f, session.previews.last().offsetX, 1e-5f)
        position("图片上移")
        assertEquals(-0.05f, session.previews.last().offsetY, 1e-5f)
        position("图片右移")
        position("图片下移")
        assertEquals(0f, session.previews.last().offsetX, 1e-5f)
        assertEquals(0f, session.previews.last().offsetY, 1e-5f)
    }

    @Test fun toolChangesCanBeSavedFromTheCollapsedFooter() {
        val session = open()
        click("展开工具")
        click("旋转镜像")
        click("左右镜像")
        click("收起工具")
        saveFromBack()
        assertTrue(session.saves.single()!!.mirrorHorizontal)
        assertEquals(0, session.cancels)
    }

    @Test fun collapsedCancelStillAsksBeforeDiscardingAChangedDraft() {
        val session = open()
        click("展开工具")
        click("旋转镜像")
        click("右转 90°")
        click("收起工具")
        back()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("保存修改？", shadowOf(dialog).title.toString())
        assertEquals(0, session.cancels)
        clickDialog(dialog, DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, session.cancels)
        assertTrue(session.saves.isEmpty())
    }

    @Test fun aSecondEntryKeepsTheCurrentPageExpansionAndDraft() {
        val session = open()
        click("展开工具")
        click("旋转镜像")
        click("上下镜像")
        P3EditActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(activity(), Intent())
        assertEquals(View.VISIBLE, tagged("p3-editor-tools").visibility)
        assertEquals(View.VISIBLE, tagged("p3-editor-group-ROTATE_MIRROR").visibility)
        saveFromBack()
        assertTrue(session.saves.single()!!.mirrorVertical)
    }

    @Test fun statisticsRemainIndependentOfPagingAndToolCollapse() {
        val session = open()
        click("展开工具")
        click("调试")
        click("显示统计")
        assertEquals(View.VISIBLE, tagged("p3-editor-stats").visibility)
        click("收起工具")
        assertEquals(View.VISIBLE, tagged("p3-editor-stats").visibility)
        click("展开工具")
        click("隐藏统计")
        assertEquals(View.GONE, tagged("p3-editor-stats").visibility)
        assertTrue(session.previews.isEmpty())
    }

    @Test fun toolsAreLimitedToThirtyEightPercentWhileTheGestureSurfaceStaysFullSize() {
        open()
        click("展开工具")
        layout(1220, 2656)
        val panel = tagged("p3-editor-tools") as EditorToolScroll
        assertEquals(EditorPanelPolicy.maxToolHeight(2656, 0, 0), panel.maximumHeightPixels)
        assertTrue(panel.measuredHeight <= panel.maximumHeightPixels)
        assertEquals(1220, surface().width)
        assertEquals(2656, surface().height)
    }

    @Test fun shortWindowHasScrollableToolsAndAlwaysVisibleFooter() {
        open()
        click("展开工具")
        // The single 48dp action row and custom tab backgrounds legitimately fit at 300px.
        // A shorter viewport forces the existing bounded ScrollView overflow contract.
        layout(500, 240)
        val panel = tagged("p3-editor-tools") as EditorToolScroll
        assertEquals(91, panel.maximumHeightPixels)
        assertTrue("panel=${panel.measuredHeight} limit=${panel.maximumHeightPixels}", panel.measuredHeight <= panel.maximumHeightPixels)
        assertTrue(panel.getChildAt(0).measuredHeight > panel.measuredHeight)
        assertTrue(visible(button("重置")))
        assertTrue(visible(button("收起工具")))
        assertEquals(240, surface().height)
    }

    @Test fun systemInsetsReduceTheToolLimitAndProtectTheFooterWithoutMovingTheSurface() {
        open()
        layout(1220, 2656)
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(30, 100, 40, 150)).build()
        ViewCompat.dispatchApplyWindowInsets(root(), insets)
        layout(1220, 2656)
        assertEquals(EditorPanelPolicy.maxToolHeight(2656, 100, 150), (tagged("p3-editor-tools") as EditorToolScroll).maximumHeightPixels)
        val dock = tagged("p3-editor-dock")
        assertEquals(30, dock.paddingLeft)
        assertEquals(40, dock.paddingRight)
        assertEquals(150, dock.paddingBottom)
        assertEquals(0, surface().top)
        assertEquals(2656, surface().height)
    }

    @Test fun touchingTheToolPaddingNeverPansTheUnderlyingWallpaper() {
        val session = open()
        click("展开工具")
        layout(1220, 2656)
        val tools = tagged("p3-editor-tool-content")
        val offset = relativeToRoot(tools)
        touch(root(), MotionEvent.ACTION_DOWN, offset.first + 1f, offset.second + 1f)
        touch(root(), MotionEvent.ACTION_MOVE, offset.first + 2f, offset.second + 3f)
        touch(root(), MotionEvent.ACTION_UP, offset.first + 2f, offset.second + 3f)
        assertTrue(session.previews.isEmpty())
    }

    @Test fun footerPaddingConsumesTouchWithoutAccidentallyEditingTheWallpaper() {
        val session = open()
        layout(1220, 2656)
        val footer = tagged("p3-editor-footer")
        val offset = relativeToRoot(footer)
        touch(root(), MotionEvent.ACTION_DOWN, offset.first + 1f, offset.second + 1f)
        touch(root(), MotionEvent.ACTION_MOVE, offset.first + 2f, offset.second + 3f)
        touch(root(), MotionEvent.ACTION_UP, offset.first + 2f, offset.second + 3f)
        assertTrue(session.previews.isEmpty())
        assertTrue(session.saves.isEmpty())
        assertEquals(0, session.cancels)
    }

    @Test fun exposedRootStillDispatchesDragsToTheFullScreenGestureSurface() {
        val session = open()
        layout(1220, 2656)
        touch(root(), MotionEvent.ACTION_DOWN, 500f, 500f)
        touch(root(), MotionEvent.ACTION_MOVE, 600f, 500f)
        touch(root(), MotionEvent.ACTION_UP, 600f, 500f)
        assertEquals(100f / 1220, session.previews.last().offsetX, 1e-5f)
    }

    @Test fun navigationAndResetRemainAvailableInThePagedTools() {
        val session = open()
        click("展开工具")
        navigate(forward = true)
        assertEquals(1, session.steps)
        position("放大")
        click("重置")
        saveFromBack()
        assertEquals(listOf<ImageTransform?>(null), session.saves)
    }

    @Test fun footerActionsAndTabsHaveUsableMinimumTouchHeight() {
        open()
        click("展开工具")
        val minimum = (48 * activity().resources.displayMetrics.density).toInt()
        listOf("收起工具", "重置").forEach { assertTrue(button(it).minimumHeight >= minimum) }
        assertTrue((tagged("p3-editor-footer") as ViewGroup).childCount == 4)
        EditorToolGroup.entries.forEach { assertTrue(button(it.label).minimumHeight >= minimum) }
    }

    @Test fun resetBeforeNavigationOffersSaveAndSendsTheConfigRemovalOnlyAfterConfirmation() {
        val session = open()
        click("展开工具")
        click("重置")
        navigate(forward = true)
        assertEquals(0, session.steps)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("切换前保存修改？", shadowOf(dialog).title.toString())
        clickDialog(dialog, DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf(true to null), session.savedBeforeSteps)
    }

    @Test fun resetBeforeNavigationCanBeDiscardedWithoutSavingTheConfigRemoval() {
        val session = open()
        click("展开工具")
        click("重置")
        navigate(forward = false)
        clickDialog(ShadowAlertDialog.getLatestAlertDialog(), DialogInterface.BUTTON_NEGATIVE)
        assertEquals(listOf(false to null), session.savedBeforeSteps)
        assertTrue(session.saves.isEmpty())
    }

    @Test fun resetBeforeNavigationCanStayInTheEditorAndSaveLater() {
        val session = open()
        click("展开工具")
        click("重置")
        navigate(forward = true)
        clickDialog(ShadowAlertDialog.getLatestAlertDialog(), DialogInterface.BUTTON_NEUTRAL)
        assertEquals(0, session.steps)
        assertEquals(0, session.cancels)
        saveFromBack()
        assertEquals(listOf<ImageTransform?>(null), session.saves)
    }

    @Test fun busyNavigationDisablesDraftButtonsAndSaveButKeepsCancelAvailable() {
        val session = open()
        session.deferStep = true
        click("展开工具")
        navigate(forward = true)
        assertFalse(button("重置").isEnabled)
        assertFalse(button("左右镜像").isEnabled)
        position("图片左移")
        position("放大")
        button("左右镜像").performClick()
        val surface = surface()
        touch(surface, MotionEvent.ACTION_DOWN, 300f, 400f)
        touch(surface, MotionEvent.ACTION_MOVE, 400f, 400f)
        touch(surface, MotionEvent.ACTION_UP, 400f, 400f)
        assertTrue(session.previews.isEmpty())
        assertTrue(session.saves.isEmpty())
        back()
        assertEquals(1, session.cancels)
    }

    @Test fun navigationCompletionReenablesEditingAndSaveControls() {
        val session = open()
        session.deferStep = true
        click("展开工具")
        navigate(forward = true)
        session.pendingStep!!.invoke(LiveEditBridge.StepResult.NoCandidate)
        assertTrue(button("重置").isEnabled)
        position("放大")
        assertEquals(1.1f, session.previews.last().userScale, 1e-5f)
    }

    @Test fun cancelledBusyNavigationRejectsALateSuccessWithoutChangingTheDraft() {
        val session = open()
        session.deferStep = true
        click("展开工具")
        navigate(forward = true)
        back()
        val draftField = P3EditActivity::class.java.getDeclaredField("draft").apply { isAccessible = true }
        val before = draftField.get(activity())
        session.pendingStep!!.invoke(LiveEditBridge.StepResult.Success(session.image.copy(original = ImageTransform(mirrorHorizontal = true))))
        assertEquals(before, draftField.get(activity()))
        assertTrue(session.saves.isEmpty())
        assertEquals(1, session.cancels)
        assertTrue(activity().isFinishing)
    }

    @Test fun homeWhileNavigationIsBusyCancelsAndIgnoresItsLateCompletion() {
        val session = open()
        session.deferStep = true
        click("展开工具")
        navigate(forward = true)
        controller!!.pause().stop()
        assertEquals(1, session.cancels)
        session.pendingStep!!.invoke(LiveEditBridge.StepResult.NoCandidate)
        assertTrue(session.saves.isEmpty())
        assertFalse(button("重置").isEnabled)
    }

    @Test fun backWhileNavigationIsBusyCancelsWithoutSavingOrWaitingForCompletion() {
        val session = open()
        session.deferStep = true
        click("展开工具")
        navigate(forward = true)
        activity().onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, session.cancels)
        assertTrue(session.saves.isEmpty())
        assertTrue(activity().isFinishing)
    }

    @Test fun aTouchStartedWhileBusyMustStartAgainBeforeEditingTheLoadedImage() {
        val session = open()
        session.deferStep = true
        click("展开工具")
        navigate(forward = true)
        touch(surface(), MotionEvent.ACTION_DOWN, 100f, 200f)
        session.pendingStep!!.invoke(LiveEditBridge.StepResult.NoCandidate)
        touch(surface(), MotionEvent.ACTION_MOVE, 200f, 200f)
        touch(surface(), MotionEvent.ACTION_UP, 200f, 200f)
        assertTrue(session.previews.isEmpty())
        touch(surface(), MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(surface(), MotionEvent.ACTION_MOVE, 200f, 200f)
        touch(surface(), MotionEvent.ACTION_UP, 200f, 200f)
        assertEquals(100f / 1220, session.previews.last().offsetX, 1e-5f)
    }

    private fun open(gesture: Boolean = false): Session {
        val session = Session()
        LiveEditBridge.register(targetId, object : LiveEditBridge.Target { override fun beginEdit() = session })
        LiveEditBridge.markVisible(targetId)
        val intent = Intent().apply { if (gesture) putExtra("dev.abdus.apps.shimmer.p3.GESTURE_UPTIME", SystemClock.uptimeMillis()) }
        controller = Robolectric.buildActivity(P3EditActivity::class.java, intent).setup()
        idle()
        return session
    }

    private fun activity() = controller!!.get()
    private fun back() {
        activity().onBackPressedDispatcher.onBackPressed()
        idle()
    }
    private fun saveFromBack() {
        back()
        clickDialog(ShadowAlertDialog.getLatestAlertDialog(), DialogInterface.BUTTON_POSITIVE)
    }
    private fun position(label: String) {
        val info = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(surface().createAccessibilityNodeInfo())
        val action = info.actionList.first { it.label?.toString() == label }
        assertTrue(ViewCompat.performAccessibilityAction(surface(), action.id, null))
        idle()
    }
    private fun navigate(forward: Boolean) {
        // New editor entry is the same timed single-finger double/triple as the desktop.
        val count = if (forward) 2 else 3
        repeat(count) {
            touch(surface(), MotionEvent.ACTION_DOWN, 300f, 400f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            touch(surface(), MotionEvent.ACTION_UP, 300f, 400f)
            if (it < count - 1) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout().toLong() + 1))
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(view: View): List<View> = if (view is ViewGroup) listOf(view) + (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else listOf(view)
    private fun tagged(tag: String): View = views(activity().window.decorView).first { it.tag == tag }
    private fun button(label: String): Button = views(activity().window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }
    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE && ((view.parent as? View)?.let(::visible) ?: true)
    private fun click(label: String) {
        val target = button(label)
        assertTrue("not user reachable: $label", visible(target))
        assertTrue(target.performClick())
        idle()
    }
    private fun clickDialog(dialog: android.app.AlertDialog, which: Int) {
        val target = dialog.getButton(which)
        assertTrue(visible(target))
        assertTrue(target.performClick())
        idle()
    }
    private fun surface() = views(activity().window.decorView).first { it.contentDescription?.startsWith("壁纸编辑区") == true }
    private fun root(): View = tagged("p3-editor-dock").parent as View
    private fun relativeToRoot(view: View): Pair<Float, Float> {
        val child = IntArray(2).also { view.getLocationOnScreen(it) }
        val parent = IntArray(2).also { root().getLocationOnScreen(it) }
        return (child[0] - parent[0]).toFloat() to (child[1] - parent[1]).toFloat()
    }
    private fun layout(width: Int, height: Int) {
        // Drain the attached Robolectric ViewRoot's default-screen pass before the explicit
        // window dimensions. A later idle would overwrite the requested test viewport.
        idle()
        val root = root()
        repeat(2) {
            // A root height change updates the scroll limit from its onLayout listener.
            // Flush measure caches on this explicit host pass, otherwise identical parent
            // specs can retain the prior screen's child measurements after requestLayout.
            views(root).forEach { it.forceLayout() }
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { assertTrue(view.dispatchTouchEvent(it)); it.recycle() }
    }
}
