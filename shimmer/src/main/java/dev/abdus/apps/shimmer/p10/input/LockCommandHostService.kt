package dev.abdus.apps.shimmer.p10.input

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import dev.abdus.apps.shimmer.p10.editor.LockEditDiagnosticCause
import dev.abdus.apps.shimmer.p10.editor.LockEditDiagnostics
import personal.wallpaper.protocol.lock.LockProtocol
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 独立证书模块专用的有限Binder入口。导出不代表授权，逐方法核真实caller。 */
class LockCommandHostService : Service() {
    companion object {
        @Volatile var endpoint: LockHostEndpoint? = null
        private val requestLeases = RequestLeaseLedger(2)
        private const val EXECUTION_DEADLINE_MS = 15_000L
        private val RESULTS = setOf("COMMITTED", "EDIT_READY", "NO_ENGINE", "STALE_TICKET", "BUSY_EDIT",
            "CANCELLED", "FAILED", "EXPIRED", "TARGET_NOT_LOCK", "UNKNOWN_STATE")

        /** Safe from IO/GL threads. Null is unauthorized; a manual editor must opt out explicitly. */
        @JvmStatic fun isRequestAuthorized(requestId: String?): Boolean =
            requestLeases.authorized(requestId, SystemClock.elapsedRealtime())

        /** Storage monitor MUST be acquired first. This never wraps decoding, GL or IPC. */
        @JvmStatic fun <T : Any> withRequestAuthorization(requestId: String?, write: () -> T): T? =
            requestLeases.withAuthorization(requestId, SystemClock.elapsedRealtime(), write)

        /** 由正式LOCK Engine持有，解绑不卸载、不改永久用户意愿。 */
        fun keepRelayAlive(context: Context): Closeable {
            val app = context.applicationContext
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            val intent = Intent().setComponent(ComponentName(LockProtocol.MODULE_PACKAGE,
                "personal.wallpaper.os4.e1.relay.LockRelayService"))
            val bound = try { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (_: RuntimeException) { false }
            return object : Closeable {
                private var closed = false
                @Synchronized override fun close() {
                    if (!closed && bound) try { app.unbindService(connection) } catch (_: RuntimeException) { }
                    closed = true
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val guard = Any()
    private val callbacks = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "LOCK-host-callback").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private class Connection(val uid: Int, val pid: Int, val callback: IBinder) {
        val session: String = UUID.randomUUID().toString()
        val ledger = LockProtocol.Ledger(session)
        var active = true
        var pending: String? = null
        var activeEditRequestId: String? = null
        var deadline = 0L
        var diagnosticWindow = -1L
        var diagnosticReads = 0
        var diagnosticLastRead = -1L
        lateinit var death: IBinder.DeathRecipient
    }
    private var connection: Connection? = null
    private fun leaseId(owner: Connection, wireRequestId: String): String =
        RequestLeaseLedger.scopedRequestId(owner.session, wireRequestId)

    private fun snapshot(): LockHostState = try {
        endpoint?.snapshot()?.takeIf { !it.ready || LockProtocol.token(it.ticket) }
            ?.let { if (LockProtocol.token(it.reason)) it else it.copy(reason = "UNKNOWN_STATE") }
            ?: LockHostState(false, "", "NO_ENGINE")
    } catch (_: RuntimeException) { LockHostState(false, "", "UNKNOWN_STATE") }

    private fun Parcel.state(value: LockHostState) {
        val inputScene = LockProtocol.sceneId(value.ticket) != null && LockProtocol.inputOnlyPriorityReason(value.reason) != null
        writeInt(if (value.ready) 1 else 0); writeString(if (value.ready || inputScene) value.ticket else ""); writeString(value.reason)
    }

    private fun current(session: String?, uid: Int, pid: Int): Connection? = synchronized(guard) {
        connection?.takeIf { it.active && it.session == session && it.uid == uid && it.pid == pid && it.callback.isBinderAlive }
    }

    private fun revoke(value: Connection) {
        val requestIds = synchronized(guard) {
            if (!value.active) return
            val ids = listOfNotNull(value.pending, value.activeEditRequestId).distinct().map { leaseId(value, it) }
            // This is the synchronous authority boundary, before Connection.active becomes
            // false and before any asynchronous Main cleanup or CLOSE acknowledgement.
            requestLeases.revokeOwner(value.session)
            LockEditDiagnostics.ledger.revokeOwner(value.session)
            value.active = false; value.pending = null
            value.activeEditRequestId = null
            if (connection === value) connection = null
            ids
        }
        try { value.callback.unlinkToDeath(value.death, 0) } catch (_: RuntimeException) { }
        val cancel = { requestIds.forEach { endpoint?.revoke(it) } }
        if (Looper.myLooper() == Looper.getMainLooper()) cancel() else main.post { cancel() }
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val uid = getCallingUid()
            val pid = getCallingPid()
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(LockProtocol.HOST); return true }
            if (code !in LockProtocol.HELLO..LockProtocol.EDIT_DIAGNOSTICS) return super.onTransact(code, data, reply, flags)
            if (reply == null || flags and IBinder.FLAG_ONEWAY != 0 || data.dataSize() > LockProtocol.MAX_PARCEL_BYTES)
                return false
            try {
                data.enforceInterface(LockProtocol.HOST)
                if (!LockCallerAuth.module(this@LockCommandHostService, uid, pid)) throw SecurityException("UNAUTHORIZED")
                when (code) {
                    LockProtocol.HELLO -> {
                        val version = data.readInt()
                        val callback = data.readStrongBinder()
                        require(data.dataAvail() == 0) { "TRAILING_DATA" }
                        if (version != LockProtocol.VERSION || callback == null || !callback.isBinderAlive)
                            throw SecurityException("BAD_HANDSHAKE")
                        val next = Connection(uid, pid, callback)
                        next.death = IBinder.DeathRecipient { revoke(next) }
                        callback.linkToDeath(next.death, 0)
                        val previous = synchronized(guard) {
                            connection.also { old ->
                                old?.let { requestLeases.revokeOwner(it.session) }
                                connection = next
                                LockEditDiagnostics.ledger.openOwner(next.session)
                            }
                        }
                        previous?.let(::revoke)
                        reply.writeNoException(); reply.writeString(next.session); reply.state(snapshot())
                    }
                    LockProtocol.STATE -> {
                        val session = data.readString(); require(data.dataAvail() == 0) { "TRAILING_DATA" }
                        if (current(session, uid, pid) == null) throw SecurityException("STALE_SESSION")
                        reply.writeNoException(); reply.state(snapshot())
                    }
                    LockProtocol.COMMAND -> {
                        val command = LockProtocol.Command(data.readString(), data.readString(), data.readString(),
                            data.readLong(), data.readLong(), data.readInt())
                        require(data.dataAvail() == 0) { "TRAILING_DATA" }
                        val owner = current(command.session, uid, pid) ?: throw SecurityException("STALE_SESSION")
                        val now = SystemClock.elapsedRealtime()
                        var enqueue = false
                        val status = synchronized(guard) {
                            if (!owner.active || connection !== owner) "STALE_SESSION" else {
                                val duplicate = owner.ledger.contains(command.requestId)
                                var result = owner.ledger.admit(command, now)
                                if (result == "ACCEPTED" && !duplicate) {
                                    if (owner.pending != null) { result = "BUSY"; owner.ledger.complete(command.requestId, result) }
                                    else if (!requestLeases.grant(owner.session, leaseId(owner, command.requestId), now + EXECUTION_DEADLINE_MS)) {
                                        result = "BUSY"; owner.ledger.complete(command.requestId, result)
                                    } else { owner.pending = command.requestId; owner.deadline = now + EXECUTION_DEADLINE_MS; enqueue = true }
                                }
                                result
                            }
                        }
                        reply.writeNoException(); reply.writeString(status)
                        if (enqueue) main.post { dispatch(owner, command) }
                    }
                    LockProtocol.CLOSE -> {
                        val session = data.readString(); require(data.dataAvail() == 0) { "TRAILING_DATA" }
                        val owner = current(session, uid, pid) ?: throw SecurityException("STALE_SESSION")
                        revoke(owner); reply.writeNoException()
                    }
                    LockProtocol.EDIT_DIAGNOSTICS -> {
                        val session = data.readString(); val after = data.readLong()
                        require(data.dataAvail() == 0) { "TRAILING_DATA" }
                        val owner = current(session, uid, pid) ?: throw SecurityException("STALE_SESSION")
                        val page = synchronized(guard) {
                            if (!owner.active || connection !== owner) throw SecurityException("STALE_SESSION")
                            val now = SystemClock.elapsedRealtime()
                            if (owner.diagnosticWindow < 0 || now < owner.diagnosticWindow || now - owner.diagnosticWindow >= 1000) {
                                owner.diagnosticWindow = now; owner.diagnosticReads = 0
                            }
                            if (owner.diagnosticReads >= 32) throw SecurityException("DIAGNOSTIC_RATE_LIMIT")
                            owner.diagnosticReads++
                            val page = LockEditDiagnostics.ledger.read(owner.session, after) ?: throw SecurityException("DIAGNOSTIC_CURSOR_REJECTED")
                            owner.diagnosticLastRead = now
                            page
                        }
                        reply.writeNoException(); reply.writeInt(1); reply.writeLong(page.latestSequence)
                        reply.writeInt(if (page.truncated) 1 else 0); reply.writeInt(page.rows.size)
                        page.rows.forEach { reply.writeLong(it.sequence); reply.writeInt(it.cause) }
                    }
                }
            } catch (_: SecurityException) { reply.writeException(SecurityException("UNAUTHORIZED_OR_STALE")) }
            catch (_: RuntimeException) { reply.writeException(IllegalArgumentException("BAD_REQUEST")) }
            return true
        }
    }

    private fun dispatch(owner: Connection, command: LockProtocol.Command) {
        val authorityId = leaseId(owner, command.requestId)
        if (current(owner.session, owner.uid, owner.pid) !== owner ||
            !LockCallerAuth.module(this, owner.uid, owner.pid)) { revoke(owner); return }
        if (!isRequestAuthorized(authorityId)) { complete(owner, command, LockHostResult("EXPIRED")); return }
        if (!LockProtocol.timely(command.action, command.issuedElapsed, SystemClock.elapsedRealtime())) {
            complete(owner, command, LockHostResult("EXPIRED")); return
        }
        val state = snapshot()
        if (!state.ready) { complete(owner, command, LockHostResult(state.reason)); return }
        if (state.ticket != command.ticket) { complete(owner, command, LockHostResult("STALE_TICKET")); return }
        val executor = endpoint ?: run { complete(owner, command, LockHostResult("NO_ENGINE")); return }
        main.postDelayed({
            val timedOut = synchronized(guard) { owner.active && owner.pending == command.requestId &&
                SystemClock.elapsedRealtime() >= owner.deadline }
            if (timedOut) {
                requestLeases.revokeRequest(owner.session, authorityId)
                executor.revoke(authorityId)
                complete(owner, command, LockHostResult("EXPIRED"))
            }
        }, EXECUTION_DEADLINE_MS)
        // 新导航标记只在显式短诊断的近期成功读取窗口内产生；普通STATE或动作不能续窗。
        // 原有EDIT认证账本契约不变。该窗口绝不是业务授权／命令租约。
        val captureNavigation = synchronized(guard) {
            owner.active && connection === owner && LockEditDiagnostics.navigationCaptureActive(
                SystemClock.elapsedRealtime(), owner.diagnosticLastRead)
        }
        val diagnosticRequest = if (command.action == LockProtocol.EDIT || captureNavigation)
            LockEditDiagnostics.ledger.begin(owner.session, authorityId) else null
        try { LockEditDiagnostics.withRequest(diagnosticRequest) {
            executor.dispatch(command.action, command.ticket, authorityId) { result ->
                main.post { complete(owner, command, result) }
            }
        } } catch (_: RuntimeException) {
            diagnosticRequest?.record(LockEditDiagnosticCause.RESULT_UNKNOWN)
            complete(owner, command, LockHostResult("FAILED"))
        }
    }

    private fun complete(owner: Connection, command: LockProtocol.Command, result: LockHostResult) {
        val expired = SystemClock.elapsedRealtime() > owner.deadline
        val launchValid = result.editLaunch?.let { it.isActivity && it.isImmutable && it.creatorPackage == packageName } == true
        val invalidEdit = result.status == "EDIT_READY" && !launchValid
        val status = if (expired) "EXPIRED" else if (invalidEdit) "FAILED" else result.status.takeIf { it in RESULTS } ?: "FAILED"
        val accepted = synchronized(guard) {
            if (!owner.active || connection !== owner || owner.pending != command.requestId) false
            else {
                owner.pending = null
                requestLeases.complete(owner.session, leaseId(owner, command.requestId), status == "EDIT_READY")
                if (status == "EDIT_READY") owner.activeEditRequestId = command.requestId
                owner.ledger.complete(command.requestId, status)
                true
            }
        }
        if (!accepted) { result.editLaunch?.cancel(); return }
        if (expired || invalidEdit) endpoint?.revoke(leaseId(owner, command.requestId))
        val launch = result.editLaunch?.takeIf { status == "EDIT_READY" && it.isActivity && it.isImmutable && it.creatorPackage == packageName }
        if (launch !== result.editLaunch) result.editLaunch?.cancel()
        try { callbacks.execute {
            if (current(owner.session, owner.uid, owner.pid) !== owner || !LockCallerAuth.module(this, owner.uid, owner.pid)) {
                launch?.cancel(); return@execute
            }
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LockProtocol.CALLBACK); data.writeString(owner.session)
                data.writeString(command.requestId); data.writeString(status)
                data.writeTypedObject(launch, 0)
                if (!owner.callback.transact(LockProtocol.CALLBACK_RESULT, data, reply, 0)) throw IllegalStateException("CALLBACK_REJECTED")
                reply.readException()
            } catch (_: Exception) { launch?.cancel(); revoke(owner) }
            finally { reply.recycle(); data.recycle() }
        } } catch (_: RuntimeException) { launch?.cancel(); revoke(owner) }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        synchronized(guard) { connection }?.let(::revoke)
        callbacks.shutdownNow(); main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
