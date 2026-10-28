package dev.abdus.apps.shimmer.p10.editor

import android.app.KeyguardManager
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/** No private image is loaded or exposed by this Activity, including before authentication. */
class LockEditEntryActivity : ComponentActivity() {
    private var token: String? = null
    private var requested = false
    private var entryOwned = false
    private var handoff = false
    private var finished = false
    private var credentialRequested = false
    private var credentialHandled = false
    private var systemSuccessObserved = false
    private var unlockSyncDeadline = -1L
    private val credentialConfirmation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!credentialRequested || credentialHandled || finished) return@registerForActivityResult
        credentialHandled = true
        if (result.resultCode != Activity.RESULT_OK) {
            cancelAndFinish(LockEditDiagnosticCause.AUTH_CANCELLED)
        } else completeSystemAuthentication()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { cancelAndFinish() }
    private val unlockRecheck = Runnable { finishAuthenticationWhenUnlocked() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setShowWhenLocked(true)
        window.setBackgroundDrawableResource(android.R.color.black)
        token = intent.getStringExtra(LockEditorBridge.EXTRA_TOKEN)
        if (!LockEditorBridge.beginAuthentication(token)) {
            finished = true
            finish()
            return
        }
        entryOwned = true
        setContentView(TextView(this).apply {
            text = "请先完成系统安全解锁\n解锁后编辑刚才的锁屏图片；取消不保存修改。"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelAndFinish(LockEditDiagnosticCause.AUTH_CANCELLED)
        })
        val deadline = LockEditorBridge.deadline(token) ?: run { cancelAndFinish(); return }
        handler.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || finished) return
        if (!LockEditorBridge.pendingValid(token)) { cancelAndFinish(); return }
        if (requested) return
        val keyguard = getSystemService(KeyguardManager::class.java) ?: run { cancelAndFinish(); return }
        requested = true
        try {
            // USER_PRESENT, loss of keyguard visibility and Activity focus are not success.
            // Only this framework dismissal callback may advance the captured ticket.
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (!credentialRequested) completeSystemAuthentication()
                }
                override fun onDismissCancelled() {
                    if (!credentialRequested && !systemSuccessObserved) cancelAndFinish(LockEditDiagnosticCause.AUTH_CANCELLED)
                }
                override fun onDismissError() = confirmSystemCredential(keyguard)
            })
        } catch (_: RuntimeException) { confirmSystemCredential(keyguard) }
    }

    /** A failed dismiss callback is not authentication. Only a separate real system result can recover it. */
    @Suppress("DEPRECATION")
    private fun confirmSystemCredential(keyguard: KeyguardManager) {
        if (finished || credentialRequested || systemSuccessObserved || isFinishing || isDestroyed) return
        if (!LockEditorBridge.pendingValid(token)) { cancelAndFinish(); return }
        try {
            if (!keyguard.isDeviceSecure) { cancelAndFinish(LockEditDiagnosticCause.AUTH_ERROR); return }
            val confirmation = keyguard.createConfirmDeviceCredentialIntent("确认后编辑锁屏图片", "请完成系统身份验证；取消不会修改壁纸。")
                ?: run { cancelAndFinish(LockEditDiagnosticCause.AUTH_ERROR); return }
            credentialRequested = true
            credentialConfirmation.launch(confirmation)
        } catch (_: RuntimeException) { cancelAndFinish(LockEditDiagnosticCause.AUTH_ERROR) }
    }

    /** Called only by a framework success callback or the registered system credential result. */
    private fun completeSystemAuthentication() {
        if (finished || isFinishing || isDestroyed || systemSuccessObserved) return
        // 仅由系统成功回调／真实凭据RESULT_OK设置。重读锁定属性不是新的认证凭据。
        systemSuccessObserved = true
        val deadline = LockEditorBridge.deadline(token) ?: run { cancelAndFinish(); return }
        unlockSyncDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 1_000L)
        finishAuthenticationWhenUnlocked()
    }

    private fun finishAuthenticationWhenUnlocked() {
        if (finished || isFinishing || isDestroyed || !systemSuccessObserved) return
        if (!LockEditorBridge.pendingValid(token)) { cancelAndFinish(); return }
        val stillLocked = try { getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false } catch (_: RuntimeException) { true }
        if (stillLocked) {
            if (SystemClock.elapsedRealtime() >= unlockSyncDeadline) {
                cancelAndFinish(LockEditDiagnosticCause.AUTH_UNLOCK_TIMEOUT)
            } else {
                LockEditorBridge.recordDiagnostic(token, LockEditDiagnosticCause.AUTH_UNLOCK_PENDING)
                handler.removeCallbacks(unlockRecheck)
                handler.postDelayed(unlockRecheck, 50L)
            }
            return
        }
        if (!LockEditorBridge.systemSucceeded(token, stillLocked)) { cancelAndFinish(); return }
        try {
            startActivity(Intent(this, LockEditActivity::class.java).putExtra(LockEditorBridge.EXTRA_TOKEN, token))
            // The exact captured hold crosses authentication without release/reacquire or retargeting.
            handoff = true
            finished = true
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(unlockRecheck)
            finish()
        } catch (_: RuntimeException) { cancelAndFinish() }
    }

    override fun onResume() {
        super.onResume()
        // Handler delays use uptime. Recheck the elapsed deadline after sleep rather than
        // granting another awake interval to an already expired authentication hold.
        if (!finished && token != null && !LockEditorBridge.pendingValid(token)) cancelAndFinish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(unlockRecheck)
        if (entryOwned && !handoff) LockEditorBridge.cancel(token, LockEditDiagnosticCause.AUTH_ENTRY_DESTROYED)
        super.onDestroy()
    }

    private fun cancelAndFinish(diagnosticCause: Int = LockEditDiagnosticCause.AUTH_ABORTED) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(unlockRecheck)
        LockEditorBridge.cancel(token, diagnosticCause)
        finish()
    }
}
