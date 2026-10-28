package dev.abdus.apps.shimmer.p3

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowToast
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P3EditActivityTest {
    private val targetId = "p3-edit-activity-test"
    private var controller: ActivityController<P3EditActivity>? = null

    private class FakeSession(override var image: LiveEditBridge.EditImage) : LiveEditBridge.EditSession {
        var open = true
        val previews = mutableListOf<ImageTransform>()
        val applies = mutableListOf<ImageTransform?>()
        val saves = mutableListOf<ImageTransform?>()
        var cancels = 0
        override val active: Boolean get() = open
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) {
            previews += transform
        }
        override fun apply(transform: ImageTransform?) {
            applies += transform
        }
        override fun save(transform: ImageTransform?, saveUptimeMillis: Long) {
            saves += transform
            open = false
        }
        override fun cancel() {
            cancels++
            open = false
        }
        override fun statusLine(): String = ""
        override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
            // No-op for testing
            onDone(LiveEditBridge.StepResult.NoCandidate)
        }
    }

    @After
    fun closeActivity() {
        controller?.let { active ->
            val state = active.get().lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) active.pause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) active.stop()
            if (state != Lifecycle.State.DESTROYED) active.destroy()
        }
        shadowOf(Looper.getMainLooper()).idle()
        LiveEditBridge.unregister(targetId)
    }

    @Test
    fun mirrorThenSaveCommitsTheDraftOnceAndCloses() {
        val session = open()
        click("左右镜像")
        assertTrue(session.previews.last().mirrorHorizontal)

        saveFromBack()
        assertEquals(1, session.saves.size)
        assertTrue(session.saves.single()!!.mirrorHorizontal)
        assertTrue(activity().isFinishing)
        close()
        // Save already ended the session; closing the page must not also cancel it.
        assertEquals(0, session.cancels)
    }

    @Test
    fun backWithoutChangesCancelsWithoutAsking() {
        val session = open()
        activity().onBackPressedDispatcher.onBackPressed()
        idle()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(1, session.cancels)
        assertTrue(session.saves.isEmpty())
        assertTrue(activity().isFinishing)
    }

    @Test
    fun backWithChangesAsksAndOnlyDiscardsAfterConfirming() {
        val session = open()
        click("右转 90°")
        assertEquals(90f, session.previews.last().rotationDegrees, 1e-4f)

        val dialog = backDialog()
        assertEquals(0, session.cancels)
        assertFalse(activity().isFinishing)

        clickDialog(dialog, DialogInterface.BUTTON_NEGATIVE, "放弃修改")
        assertEquals(1, session.cancels)
        assertTrue(session.saves.isEmpty())
        assertTrue(activity().isFinishing)
    }

    @Test
    fun backWithChangesCanSaveTheDraftOnce() {
        val session = open()
        click("左右镜像")

        clickDialog(backDialog(), DialogInterface.BUTTON_POSITIVE, "保存修改")
        assertEquals(1, session.saves.size)
        assertTrue(session.saves.single()!!.mirrorHorizontal)
        assertTrue(activity().isFinishing)
        close()
        // Saving from the dialog ends the session; closing must not cancel it as well.
        assertEquals(0, session.cancels)
    }

    @Test
    fun backDialogCanKeepEditing() {
        val session = open()
        click("上下镜像")

        clickDialog(backDialog(), DialogInterface.BUTTON_NEUTRAL, "继续编辑")
        assertFalse(activity().isFinishing)
        assertEquals(0, session.cancels)
        assertTrue(session.saves.isEmpty())

        // The draft is still there: a later save commits it.
        saveFromBack()
        assertTrue(session.saves.single()!!.mirrorVertical)
    }

    @Test
    fun footerHasNoCancelAndBackOffersSaveDiscardOrKeepEditing() {
        val session = open()
        click("右转 90°")
        assertFalse(views(activity().window.decorView).filterIsInstance<Button>().any { it.text == "取消" || it.text == "保存" })
        val dialog = backDialog()
        assertTrue(dialog.isShowing)
        assertEquals("保存修改？", shadowOf(dialog).title.toString())
        assertEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEUTRAL).visibility)

        clickDialog(dialog, DialogInterface.BUTTON_NEGATIVE, "放弃修改")
        assertEquals(1, session.cancels)
        assertTrue(session.saves.isEmpty())
    }

    @Test
    fun leavingThePageCancelsTheDraft() {
        val session = open()
        click("上下镜像")
        controller!!.pause().stop()
        assertEquals(1, session.cancels)
        assertTrue(session.saves.isEmpty())
        assertTrue(activity().isFinishing)
    }

    @Test
    fun resetThenSaveDropsTheImageOwnConfig() {
        val session = open(original = ImageTransform(userScale = 2f, rotationDegrees = 90f, mirrorVertical = true))
        click("重置")
        assertEquals(ImageTransform(), session.previews.last())

        saveFromBack()
        assertEquals(listOf<ImageTransform?>(null), session.saves)
    }

    @Test
    fun anEndedSessionClosesThePageWithoutWriting() {
        val session = open()
        click("左右镜像")
        // The Engine went away (surface lost, wallpaper replaced); the next poll notices.
        session.open = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(activity().isFinishing)
        assertTrue(session.saves.isEmpty())
        assertEquals(0, session.cancels)
        assertEquals("编辑已中断，本次修改未保存。", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun noEditableWallpaperExplainsAndCloses() {
        LiveEditBridge.register(targetId, object : LiveEditBridge.Target {
            override fun beginEdit(): LiveEditBridge.EditSession? = null
        })
        LiveEditBridge.markVisible(targetId)
        controller = Robolectric.buildActivity(P3EditActivity::class.java).setup()
        assertTrue(activity().isFinishing)
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("没有可编辑的壁纸"))
    }

    @Test
    fun oneFingerDragPansByTheFingerDistance() {
        val session = open()
        val surface = surface()
        touch(surface, MotionEvent.ACTION_DOWN, 600f to 1300f)
        touch(surface, MotionEvent.ACTION_MOVE, 700f to 1300f)
        touch(surface, MotionEvent.ACTION_UP, 700f to 1300f)
        val moved = session.previews.last()
        assertEquals(100f / IMAGE.viewWidth, moved.offsetX, 1e-4f)
        assertEquals(0f, moved.offsetY, 1e-4f)
        assertEquals(1f, moved.userScale, 1e-6f)
    }

    @Test
    fun twoFingerQuarterTwistSnapsToNinetyDegreesWithoutZooming() {
        val session = open()
        val surface = surface()
        val centreX = IMAGE.viewWidth / 2f
        val centreY = IMAGE.viewHeight / 2f
        touch(surface, MotionEvent.ACTION_DOWN, centreX - 200f to centreY)
        touch(surface, pointerDown(1), centreX - 200f to centreY, centreX + 200f to centreY)
        // Same span and centroid, turned a quarter clockwise (y points down).
        touch(surface, MotionEvent.ACTION_MOVE, centreX to centreY - 200f, centreX to centreY + 200f)
        val twisted = session.previews.last()
        assertEquals(90f, twisted.rotationDegrees, 1e-3f)
        assertEquals(1f, twisted.userScale, 1e-4f)
        assertEquals(0f, twisted.offsetX, 1e-3f)
        assertEquals(0f, twisted.offsetY, 1e-3f)
    }

    @Test
    fun screenReaderActionsZoomAndMoveWithoutGestures() {
        val session = open()
        val surface = surface()
        assertTrue(accessibilityAction(surface, "放大"))
        assertEquals(1.1f, session.previews.last().userScale, 1e-4f)
        assertTrue(accessibilityAction(surface, "图片上移"))
        assertEquals(-0.05f, session.previews.last().offsetY, 1e-4f)
    }

    private fun open(original: ImageTransform = ImageTransform()): FakeSession {
        val session = FakeSession(IMAGE.copy(original = original))
        LiveEditBridge.register(targetId, object : LiveEditBridge.Target {
            override fun beginEdit(): LiveEditBridge.EditSession = session
        })
        LiveEditBridge.markVisible(targetId)
        controller = Robolectric.buildActivity(P3EditActivity::class.java).setup()
        assertFalse(activity().isFinishing)
        return session
    }

    private fun close() {
        controller!!.pause().stop().destroy()
        idle()
    }

    private fun activity(): P3EditActivity = controller!!.get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun views(view: View): List<View> =
        if (view is ViewGroup) listOf(view) + (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else listOf(view)

    private fun click(label: String) {
        if (label == "左右镜像" || label == "上下镜像" || label.contains("转 90")) {
            if (views(activity().window.decorView).first { it.tag == "p3-editor-tools" }.visibility != View.VISIBLE) click("展开工具")
            click("旋转镜像")
        }
        val button = views(activity().window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }
        assertTrue(button.performClick())
        idle()
    }

    private fun saveFromBack() = clickDialog(backDialog(), DialogInterface.BUTTON_POSITIVE, "保存修改")

    /** Presses back with a changed draft and returns the three-option dialog it shows. */
    private fun backDialog(): AlertDialog {
        activity().onBackPressedDispatcher.onBackPressed()
        idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals("保存修改？", shadowOf(dialog).title.toString())
        return dialog
    }

    private fun clickDialog(dialog: AlertDialog, which: Int, label: String) {
        val button = dialog.getButton(which)
        assertEquals(label, button.text.toString())
        assertTrue(button.performClick())
        idle()
    }

    private fun surface(): View =
        views(activity().window.decorView).first { it.contentDescription?.startsWith("壁纸编辑区") == true }

    private fun accessibilityAction(view: View, label: String): Boolean {
        val info = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo())
        val action = info.actionList.firstOrNull { it.label?.toString() == label }
        assertNotNull("no accessibility action $label", action)
        return ViewCompat.performAccessibilityAction(view, action!!.id, null)
    }

    private fun pointerDown(index: Int) =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun touch(view: View, action: Int, vararg points: Pair<Float, Float>) {
        val properties = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private companion object {
        val IMAGE = LiveEditBridge.EditImage(
            imageId = "content://test/p3/4000x3000",
            sourceWidth = 4000,
            sourceHeight = 3000,
            viewWidth = 1220,
            viewHeight = 2656,
            original = ImageTransform(),
            defaults = P3Defaults(),
        )
    }
}
