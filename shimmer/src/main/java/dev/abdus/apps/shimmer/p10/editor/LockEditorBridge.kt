package dev.abdus.apps.shimmer.p10.editor

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import java.util.UUID

/**
 * Main-thread-only, in-process handoff. The authentication Activity receives a random token,
 * never an image URI or a serialised session. Specific Engine IDs never fall back to HOME or
 * to a replacement LOCK Engine. Its owner must acquire the LOCK hold BEFORE returning Pending.
 */
object LockEditorBridge {
    interface Target { fun prepareLockEdit(): Pending? }

    interface Pending {
        /** Rechecks original image, committed sequence, flags=2, generations and connection. */
        val active: Boolean
        val deadlineElapsedRealtimeMillis: Long
        /** Called once, only after the real system dismissal callback reports success. */
        fun authenticate(): Session?
        /** Releases the exact captured hold; it must not cancel another Engine/session. */
        fun cancel()
    }

    interface Session : LiveEditBridge.EditSession {
        /** Only exposed inside the app after authentication; never passed in an Intent. */
        val previewUri: Uri
        /** 固定本地诊断，不参与认证／业务授权；普通手动编辑默认无记录。 */
        fun recordDiagnostic(cause: Int) = Unit
    }

    internal const val EXTRA_TOKEN = "dev.abdus.apps.shimmer.p10.editor.TOKEN"
    private class Record(val token: String, val owner: String?, val pending: Pending, val auth: LockEditAuthentication) {
        val diagnostic = LockEditDiagnostics.currentRequest()
        var ready: Session? = null
        var capability: PendingIntent? = null
        var timeout: Runnable? = null
        var launchTimeout: Runnable? = null
        var entryClaimed = false
    }

    private val targets = LinkedHashMap<String, Target>()
    private var record: Record? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun register(engineId: String, target: Target) { targets[engineId] = target }

    fun unregister(engineId: String) {
        targets.remove(engineId)
        record?.takeIf { it.owner == engineId }?.let { discard(it) }
    }

    /** There is deliberately no unspecified-ID / last-visible fallback in this bridge. */
    fun launch(context: Context, engineId: String): Boolean {
        discardExpired()
        if (record != null) return false
        val pending = targets[engineId]?.prepareLockEdit() ?: return false
        return launchPrepared(context, pending, engineId)
    }

    /** CommandHost may already own the exact Pending captured under its admission ticket. */
    fun launchPrepared(context: Context, pending: Pending, ownerEngineId: String? = null): Boolean {
        val next = prepareRecord(pending, ownerEngineId) ?: return false
        return try {
            context.startActivity(entryIntent(context, next))
            true
        } catch (_: RuntimeException) {
            discard(next)
            false
        }
    }

    /**
     * One-shot immutable activity capability for the authenticated SystemUI command channel.
     * The hold and token exist before this is returned. The sender cannot fill in a URI, target
     * or replacement picture; this capability can only reach our non-exported auth entry.
     */
    fun createPreparedPendingIntent(context: Context, pending: Pending, ownerEngineId: String? = null): PendingIntent? {
        val next = prepareRecord(pending, ownerEngineId) ?: return null
        return try {
            val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle() else null
            PendingIntent.getActivity(context, 0, entryIntent(context, next),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT, options).also {
                next.capability = it
                val launchTimeout = Runnable { if (record === next && !next.entryClaimed) discard(next) }
                next.launchTimeout = launchTimeout
                handler.postDelayed(launchTimeout, 5_000L)
            }
        } catch (_: RuntimeException) {
            discard(next)
            null
        }
    }

    private fun entryIntent(context: Context, next: Record) = Intent(context, LockEditEntryActivity::class.java)
        .setData(Uri.parse("hwp-lock-edit:${next.token}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_TOKEN, next.token)

    private fun prepareRecord(pending: Pending, ownerEngineId: String?): Record? {
        discardExpired()
        if (record != null || !pending.active) {
            pending.cancel()
            return null
        }
        val now = SystemClock.elapsedRealtime()
        val auth = LockEditAuthentication(now, pending.deadlineElapsedRealtimeMillis)
        if (!auth.valid(now, true)) {
            pending.cancel()
            return null
        }
        val next = Record(UUID.randomUUID().toString(), ownerEngineId, pending, auth)
        record = next
        val timeout = Runnable { if (record === next) discard(next) }
        next.timeout = timeout
        handler.postDelayed(timeout, pending.deadlineElapsedRealtimeMillis - now)
        return next
    }

    internal fun beginAuthentication(token: String?): Boolean {
        val current = matching(token) ?: return false
        if (current.entryClaimed || !current.auth.begin(SystemClock.elapsedRealtime(), current.pending.active)) {
            return false
        }
        current.entryClaimed = true
        current.launchTimeout?.let(handler::removeCallbacks)
        current.launchTimeout = null
        current.diagnostic?.record(LockEditDiagnosticCause.AUTH_WAITING)
        return true
    }

    internal fun pendingValid(token: String?): Boolean {
        val current = matching(token) ?: return false
        val valid = current.auth.valid(SystemClock.elapsedRealtime(), current.ready?.active ?: current.pending.active)
        if (!valid) discard(current)
        return valid
    }

    internal fun deadline(token: String?): Long? = matching(token)?.pending?.deadlineElapsedRealtimeMillis

    internal fun recordDiagnostic(token: String?, cause: Int) {
        matching(token)?.diagnostic?.record(cause)
    }

    internal fun systemSucceeded(token: String?, deviceStillLocked: Boolean): Boolean {
        val current = matching(token) ?: return false
        if (current.auth.phase != LockEditAuthentication.Phase.AUTHENTICATING) return false
        if (!current.auth.systemSucceeded(SystemClock.elapsedRealtime(), current.pending.active, deviceStillLocked)) {
            if (current.auth.phase == LockEditAuthentication.Phase.CANCELLED) discard(current)
            return false
        }
        current.diagnostic?.record(LockEditDiagnosticCause.AUTH_SUCCEEDED)
        val session = try { current.pending.authenticate() } catch (_: RuntimeException) { null }
        if (session == null || !session.active) {
            session?.cancel()
            discard(current)
            return false
        }
        if (session is LockEditSaveResultSource) session.observeSaveResult { saved ->
            current.diagnostic?.record(if (saved) LockEditDiagnosticCause.EDITOR_SAVED else LockEditDiagnosticCause.EDITOR_SAVE_REJECTED)
        }
        current.ready = session
        return true
    }

    internal fun consumeAuthenticated(token: String?): Session? {
        val current = matching(token) ?: return null
        val session = current.ready
        // Pending.active may become false as authenticate transfers its hold to the Session;
        // the authenticated session now owns the exact Engine/image validity recheck.
        if (session == null || !current.auth.consume(SystemClock.elapsedRealtime(), session.active)) {
            discard(current)
            return null
        }
        current.timeout?.let(handler::removeCallbacks)
        current.launchTimeout?.let(handler::removeCallbacks)
        current.capability?.cancel()
        current.ready = null
        record = null
        return object : Session by session {
            override fun recordDiagnostic(cause: Int) { current.diagnostic?.record(cause) }
            override fun save(transform: dev.abdus.apps.shimmer.p3.ImageTransform?, saveUptimeMillis: Long) {
                current.diagnostic?.record(LockEditDiagnosticCause.EDITOR_SAVE_REQUESTED)
                if (session !is LockEditSaveResultSource) current.diagnostic?.record(LockEditDiagnosticCause.RESULT_UNKNOWN)
                session.save(transform, saveUptimeMillis)
            }
        }
    }

    internal fun cancel(token: String?, diagnosticCause: Int = LockEditDiagnosticCause.AUTH_ABORTED) {
        matching(token)?.let { discard(it, diagnosticCause) }
    }

    private fun matching(token: String?): Record? = record?.takeIf { token != null && it.token == token }

    private fun discardExpired() {
        record?.let { current ->
            val active = current.ready?.active ?: current.pending.active
            if (!current.auth.valid(SystemClock.elapsedRealtime(), active)) discard(current)
        }
    }

    private fun discard(current: Record, diagnosticCause: Int = LockEditDiagnosticCause.AUTH_ABORTED) {
        if (record !== current) return
        current.diagnostic?.record(diagnosticCause)
        record = null
        current.timeout?.let(handler::removeCallbacks)
        current.capability?.cancel()
        current.auth.cancel()
        current.ready?.cancel() ?: current.pending.cancel()
        current.ready = null
    }
}
