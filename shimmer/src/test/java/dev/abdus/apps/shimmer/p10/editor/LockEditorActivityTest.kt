package dev.abdus.apps.shimmer.p10.editor

import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p3.P3Defaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Actual shared editor UI and bridge handoff; the render-only adapter never executes GLES. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LockEditorActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engineId = "lock-editor-activity"
    private var controller: ActivityController<PreviewActivity>? = null

    class Preview(context: Context) : LockEditorPreview {
        override val view = FrameLayout(context)
        val transforms = mutableListOf<ImageTransform>()
        val uris = mutableListOf<Uri>()
        var pauses = 0
        var closes = 0
        override fun show(nextImage: LiveEditBridge.EditImage, nextUri: Uri, transform: ImageTransform) { uris += nextUri }
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) { transforms += transform }
        override fun resumePreview() = Unit
        override fun pausePreview() { pauses++ }
        override fun closePreview() { closes++ }
    }

    class PreviewActivity : LockEditActivity() {
        val privatePreview by lazy { Preview(this) }
        override fun createLockPreview(): LockEditorPreview = privatePreview
    }

    class Session : LockEditorBridge.Session {
        override var image = LiveEditBridge.EditImage("content://private/LOCK-original", 4000, 3000, 1200, 2400,
            ImageTransform(), P3Defaults())
        override val previewUri get() = Uri.parse(image.imageId)
        var open = true
        var previews = 0
        var cancels = 0
        val saves = mutableListOf<ImageTransform?>()
        val steps = mutableListOf<Pair<Boolean, Boolean>>()
        var nextImage: LiveEditBridge.EditImage? = null
        override val active get() = open
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) { previews++ }
        override fun apply(transform: ImageTransform?) = Unit
        override fun save(transform: ImageTransform?, saveUptimeMillis: Long) { saves += transform; open = false }
        override fun cancel() { cancels++; open = false }
        override fun statusLine() = "LOCK"
        override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
            steps += forward to saveFirst
            val next = nextImage
            if (next == null) onDone(LiveEditBridge.StepResult.NoCandidate)
            else {
                image = next
                onDone(LiveEditBridge.StepResult.Success(next))
            }
        }
    }

    private fun ready(session: Session): String {
        val pending = object : LockEditorBridge.Pending {
            var valid = true
            override val active get() = valid
            override val deadlineElapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime() + 60_000L
            override fun authenticate(): LockEditorBridge.Session { valid = false; return session }
            override fun cancel() { valid = false; session.cancel() }
        }
        assertTrue(LockEditorBridge.launchPrepared(context, pending, engineId))
        val token = shadowOf(context as android.app.Application).nextStartedActivity
            .getStringExtra(LockEditorBridge.EXTRA_TOKEN)!!
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertTrue(LockEditorBridge.systemSucceeded(token, false))
        return token
    }

    private fun open(session: Session = Session(), token: String = ready(session)): Session {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        controller = Robolectric.buildActivity(PreviewActivity::class.java,
            Intent(context, PreviewActivity::class.java).putExtra(LockEditorBridge.EXTRA_TOKEN, token))
            .create().start().resume().visible()
        idle()
        return session
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        LockEditorBridge.unregister(engineId)
        idle()
    }

    @Test fun privateRendererUsesCapturedLockUriAndOpaqueSecureWindow() {
        val session = open()
        val activity = controller!!.get()
        assertEquals(listOf(session.previewUri), activity.privatePreview.uris)
        assertEquals(0, session.previews)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun privateDraftNeverCallsFormalWallpaperPreviewAndBackCanSaveOnce() {
        val session = open()
        click("展开工具")
        click("左右镜像")
        assertTrue(controller!!.get().privatePreview.transforms.last().mirrorHorizontal)
        assertEquals(0, session.previews)
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, session.saves.size)
        assertTrue(session.saves.single()!!.mirrorHorizontal)
        assertEquals(0, session.cancels)
    }

    @Test fun discardDraftDoesNotSaveOrChangeHomeWallpaper() {
        val session = open()
        click("展开工具")
        click("右转 90°")
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(session.saves.isEmpty())
        assertEquals(1, session.cancels)
        assertEquals(0, session.previews)
    }

    @Test fun diagnosticExplicitDiscardIsNotConfusedWithLifecycleInterruption() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val request=ledger.begin("owner","request")!!; val session=Session()
        val token=LockEditDiagnostics.withRequest(request) { ready(session) }
        open(session,token); click("展开工具"); click("右转 90°")
        controller!!.get().onBackPressedDispatcher.onBackPressed(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); idle()
        val causes=ledger.read("owner",0)!!.rows.map { it.cause }
        assertTrue(causes.contains(LockEditDiagnosticCause.EDITOR_OPENED))
        assertTrue(causes.contains(LockEditDiagnosticCause.EDITOR_CANCEL_REQUESTED))
        assertFalse(causes.contains(LockEditDiagnosticCause.EDITOR_SAVED))
    }

    @Test fun diagnosticBackgroundCloseDoesNotClaimExplicitDiscardOrSave() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val request=ledger.begin("owner","request")!!; val session=Session()
        val token=LockEditDiagnostics.withRequest(request) { ready(session) }
        open(session,token); controller!!.pause().stop().destroy(); controller=null
        val causes=ledger.read("owner",0)!!.rows.map { it.cause }
        assertTrue(causes.contains(LockEditDiagnosticCause.RESULT_UNKNOWN))
        assertTrue(causes.contains(LockEditDiagnosticCause.EDITOR_CLOSED))
        assertFalse(causes.contains(LockEditDiagnosticCause.EDITOR_CANCEL_REQUESTED))
        assertFalse(causes.contains(LockEditDiagnosticCause.EDITOR_SAVED))
    }

    @Test fun perImageDisableWritesOnlyLockDomainAndCanReenableSameImage() {
        val session = open()
        val home = WallpaperPreferences.create(context, WallpaperTarget.HOME)
        val lock = WallpaperPreferences.create(context, WallpaperTarget.LOCK)
        home.setImageEnabled(session.image.imageId, true)
        lock.setImageEnabled(session.image.imageId, true)
        click("启用：开")
        assertTrue(session.image.imageId in lock.getDisabledImages())
        assertFalse(session.image.imageId in home.getDisabledImages())
        click("启用：关")
        assertFalse(session.image.imageId in lock.getDisabledImages())
        assertFalse(session.image.imageId in home.getDisabledImages())
    }

    @Test fun sharedPanelsAndStatisticsRemainCollapsedWithFourFooterActions() {
        open()
        val all = descendants(controller!!.get().window.decorView)
        assertEquals(View.GONE, all.first { it.tag == "p3-editor-tools" }.visibility)
        assertEquals(View.GONE, all.first { it.tag == "p3-editor-stats" }.visibility)
        assertTrue(all.filterIsInstance<Button>().any { it.text == "展开工具" })
        assertFalse(all.filterIsInstance<Button>().any { it.text == "保存" || it.text == "取消" })
    }

    @Test fun currentSessionDeathClosesWithoutSavingPrivateDraft() {
        val session = open()
        session.open = false
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(501))
        assertTrue(controller!!.get().isFinishing)
        assertTrue(session.saves.isEmpty())
    }

    @Test fun directLaunchWithoutReadyTicketHasNoPrivateRendererOrSession() {
        open(token = "not-an-authenticated-ticket")
        val activity = controller!!.get()
        assertTrue(activity.isFinishing)
        assertTrue(activity.privatePreview.uris.isEmpty())
    }

    @Test fun stopCancelsAndDestroyReleasesOwnedPreview() {
        val session = open()
        val preview = controller!!.get().privatePreview
        controller!!.pause().stop().destroy()
        controller = null
        assertEquals(1, session.cancels)
        assertTrue(session.saves.isEmpty())
        assertEquals(1, preview.pauses)
        assertEquals(1, preview.closes)
    }

    private fun click(label: String) {
        descendants(controller!!.get().window.decorView).filterIsInstance<Button>().first { it.text == label }.performClick()
        idle()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
}
