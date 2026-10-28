package dev.abdus.apps.shimmer.p10.editor

import personal.wallpaper.protocol.lock.LockProtocol

/** 固定业务阶段，只记内存整数；不导出会话、原图票据、路径、图片内容或认证内容。 */
object LockEditDiagnosticCause {
    const val AUTH_WAITING = 1
    const val AUTH_SUCCEEDED = 2
    const val AUTH_CANCELLED = 3
    const val AUTH_ERROR = 4
    const val AUTH_ABORTED = 5
    const val EDITOR_OPENED = 6
    const val EDITOR_SAVE_REQUESTED = 7
    const val EDITOR_SAVED = 8
    const val EDITOR_SAVE_REJECTED = 9
    const val EDITOR_CANCEL_REQUESTED = 10
    const val EDITOR_CLOSED = 11
    const val RESULT_UNKNOWN = 12
    const val AUTH_UNLOCK_PENDING = 13
    const val AUTH_UNLOCK_TIMEOUT = 14
    const val AUTH_ENTRY_DESTROYED = 15
    const val NAV_NEXT_PRESENTED = 16
    const val NAV_PREVIOUS_PRESENTED = 17
    const val NAV_UNCHANGED = 18
    const val LAST_CAUSE = NAV_UNCHANGED
    fun valid(cause: Int) = cause in AUTH_WAITING..LAST_CAUSE
}

/** 单个已鉴别 HOST owner 的只读诊断账本；撤权后旧请求永远不能向新 owner 写入。 */
internal class LockEditDiagnosticLedger(private val rowLimit: Int = 32, private val requestLimit: Int = 2) {
    data class Row(val sequence: Long, val cause: Int)
    data class Snapshot(val latestSequence: Long, val truncated: Boolean, val rows: List<Row>)

    class Request internal constructor(
        private val ledger: LockEditDiagnosticLedger,
        internal val owner: String,
        internal val requestId: String,
        internal val generation: Long,
    ) {
        internal val seen = BooleanArray(LockEditDiagnosticCause.LAST_CAUSE + 1)
        fun record(cause: Int): Boolean = ledger.record(this, cause)
    }

    init { require(rowLimit in 1..32 && requestLimit in 1..2) }
    private var owner: String? = null
    private var generation = 0L
    private var latestSequence = 0L
    private val rows = ArrayDeque<Row>()
    private val requests = LinkedHashMap<String, Request>()

    @Synchronized fun openOwner(next: String) {
        require(LockProtocol.token(next))
        check(generation < Long.MAX_VALUE) { "DIAGNOSTIC_GENERATION_EXHAUSTED" }
        generation++; owner = next; latestSequence = 0; rows.clear(); requests.clear()
    }
    @Synchronized fun revokeOwner(expected: String) {
        if (owner != expected) return
        owner = null; rows.clear(); requests.clear()
    }
    @Synchronized fun begin(expectedOwner: String, requestId: String): Request? {
        if (owner != expectedOwner || !LockProtocol.token(requestId)) return null
        requests[requestId]?.let { return it }
        val request = Request(this, expectedOwner, requestId, generation)
        if (requests.size >= requestLimit) requests.remove(requests.keys.first())
        requests[requestId] = request
        return request
    }
    @Synchronized private fun record(request: Request, cause: Int): Boolean {
        if (!LockEditDiagnosticCause.valid(cause) || request.owner != owner || request.generation != generation
            || requests[request.requestId] !== request || request.seen[cause] || latestSequence == Long.MAX_VALUE) return false
        request.seen[cause] = true
        rows.addLast(Row(++latestSequence, cause))
        if (rows.size > rowLimit) rows.removeFirst()
        return true
    }
    @Synchronized fun read(expectedOwner: String, afterSequence: Long): Snapshot? {
        if (owner != expectedOwner || afterSequence < 0 || afterSequence > latestSequence) return null
        val first = rows.firstOrNull()?.sequence ?: (latestSequence + 1)
        return Snapshot(latestSequence, afterSequence < first - 1, rows.filter { it.sequence > afterSequence })
    }
}

/** 仅同步 EDIT dispatch 传播原请求归属；finally 恢复，不产生新的业务授权。 */
internal object LockEditDiagnostics {
    /** 只能由已鉴别且成功的显式诊断读取续窗；普通状态查询和导航均不能续期。 */
    fun navigationCaptureActive(now: Long, lastRead: Long): Boolean =
        lastRead >= 0L && now >= lastRead && now - lastRead < 2_500L
    val ledger = LockEditDiagnosticLedger()
    private val dispatchRequest = ThreadLocal<LockEditDiagnosticLedger.Request?>()
    fun currentRequest(): LockEditDiagnosticLedger.Request? = dispatchRequest.get()
    fun <T> withRequest(request: LockEditDiagnosticLedger.Request?, dispatch: () -> T): T {
        val previous = dispatchRequest.get()
        dispatchRequest.set(request)
        return try { dispatch() } finally {
            if (previous == null) dispatchRequest.remove() else dispatchRequest.set(previous)
        }
    }
}

/** 仅真实持久化结果源实现；调用发生在原提交函数返回后，不参与提交准入。 */
internal interface LockEditSaveResultSource {
    fun observeSaveResult(observer: (Boolean) -> Unit)
}
