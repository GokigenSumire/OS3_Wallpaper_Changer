package dev.abdus.apps.shimmer.p10.editor

import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p3.FrameStats
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p3.P3EditActivity

/**
 * The same editor controls and back/save/cancel semantics as HOME, but an opaque, private LOCK
 * renderer. Direct launch, stale tokens and a newly locked device expose no private pixels.
 */
open class LockEditActivity : P3EditActivity() {
    private val lockStats = FrameStats()
    private var authenticated: LockEditorBridge.Session? = null
    private var preview: LockEditorPreview? = null
    private var lifecycleExit = false
    override val editorTarget get() = WallpaperTarget.LOCK
    override val editorFrameStats get() = lockStats
    override val noEditorMessage get() = "锁屏编辑请求已失效，未保存修改。请从锁屏重新发起编辑并完成系统安全解锁。"
    override fun editorDiagnosticImageLabel(imageId: String) = "LOCK_IMAGE"

    override fun onCreate(savedInstanceState: Bundle?) {
        // The Manifest uses an opaque non-wallpaper theme as well; set both explicitly so a
        // future shared-theme change cannot leak the HOME wallpaper into this editor.
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setBackgroundDrawableResource(android.R.color.black)
        setShowWhenLocked(false)
        super.onCreate(savedInstanceState)
        if (authenticated != null && !isFinishing) authenticated?.recordDiagnostic(LockEditDiagnosticCause.EDITOR_OPENED)
    }

    override fun acquireEditorSession(): LiveEditBridge.EditSession? {
        val token = intent.getStringExtra(LockEditorBridge.EXTRA_TOKEN)
        if (deviceLocked()) {
            LockEditorBridge.cancel(token)
            return null
        }
        val owner = LockEditorBridge.consumeAuthenticated(token) ?: return null
        authenticated = owner
        return object : LiveEditBridge.EditSession {
            override var image: LiveEditBridge.EditImage
                get() = owner.image
                set(value) { owner.image = value }
            override val active get() = owner.active
            override fun preview(transform: ImageTransform, inputUptimeMillis: Long) {
                // Never call the formal Engine's preview API. This private renderer owns all
                // drafts; the currently committed LOCK picture changes only through Save.
                if (owner.active) preview?.preview(transform, inputUptimeMillis)
            }
            override fun apply(transform: ImageTransform?) = owner.apply(transform)
            override fun save(transform: ImageTransform?, saveUptimeMillis: Long) = owner.save(transform, saveUptimeMillis)
            override fun cancel() {
                owner.recordDiagnostic(if (lifecycleExit) LockEditDiagnosticCause.RESULT_UNKNOWN else LockEditDiagnosticCause.EDITOR_CANCEL_REQUESTED)
                owner.cancel()
            }
            override fun statusLine() = "LOCK 独立预览；预览帧统计不冒正式壁纸呈现\n${owner.statusLine()}"
            override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
                owner.step(forward, saveFirst, transform) { result ->
                    if (owner.active && result is LiveEditBridge.StepResult.Success) {
                        preview?.show(result.image, owner.previewUri, result.image.original)
                    }
                    onDone(result)
                }
            }
        }
    }

    override fun attachEditorPreview(root: FrameLayout) {
        val owner = authenticated ?: return
        val display = createLockPreview()
        preview = display
        root.addView(display.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        display.show(owner.image, owner.previewUri, owner.image.original)
    }

    protected open fun createLockPreview(): LockEditorPreview =
        LockImagePreview(this, lockStats) { lifecycleExit = true; interruptEditor() }

    override fun onResume() {
        super.onResume()
        if (deviceLocked()) {
            lifecycleExit = true
            authenticated?.recordDiagnostic(LockEditDiagnosticCause.RESULT_UNKNOWN)
            authenticated?.cancel()
            interruptEditor()
            return
        }
        preview?.resumePreview()
    }

    override fun onPause() {
        preview?.pausePreview()
        super.onPause()
    }

    override fun onStop() {
        lifecycleExit = true
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleExit = true
        authenticated?.recordDiagnostic(LockEditDiagnosticCause.EDITOR_CLOSED)
        preview?.closePreview()
        preview = null
        super.onDestroy()
    }

    private fun deviceLocked(): Boolean = try {
        getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
    } catch (_: RuntimeException) { true }
}
