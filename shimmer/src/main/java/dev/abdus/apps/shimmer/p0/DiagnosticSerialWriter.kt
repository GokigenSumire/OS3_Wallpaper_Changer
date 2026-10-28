package dev.abdus.apps.shimmer.p0

/** Production uses one HandlerThread; tests control scheduling without sleeps. */
interface DiagnosticWriteScheduler {
    fun schedule(task: Runnable, delayMillis: Long)
    fun cancel(task: Runnable)
}

/**
 * At most one running I/O and one latest request. Reset never queues a separate destructive clear:
 * its complete new-run tail replaces the old tail in the same serial writer. A started old commit
 * precedes (never follows) the new commit. A reset is not SAVED until the new-run acknowledgment.
 */
class DiagnosticSerialWriter(
    private val scheduler: DiagnosticWriteScheduler,
    private val snapshot: () -> DiagnosticStoredSnapshot,
    private val store: (DiagnosticStoredSnapshot) -> Boolean,
    private val acknowledgment: (DiagnosticStoredSnapshot, Boolean, String?) -> Unit,
    private val protocolFailure: (DiagnosticToken?, Long?, String) -> Unit = { _, _, _ -> },
    private val captureRunToken: () -> DiagnosticToken? = { null },
) {
    private var requested = false
    private var running = false
    private var scheduled: Runnable? = null

    @Synchronized fun request(immediate: Boolean = false) {
        requested = true
        if (running) return
        if (scheduled != null && !immediate) return
        scheduled?.let { scheduler.cancel(it) }
        lateinit var task: Runnable
        task = Runnable { drain(task) }
        scheduled = task
        scheduler.schedule(task, if (immediate) 0 else 500)
    }

    private fun drain(task: Runnable) {
        synchronized(this) {
            // A canceled old task cannot clear the latest task's pending state.
            if (scheduled !== task || running) return
            scheduled = null
            running = true
            requested = false
        }
        var failureToken: DiagnosticToken? = null
        var failureRevision: Long? = null
        try {
            // Snapshot callback failure still belongs to the run captured before that callback.
            failureToken = captureRunToken()
            val data = snapshot()
            failureToken = data.token
            failureRevision = data.revision
            var failure: String? = null
            val success = try { store(data) } catch (failed: Exception) {
                failure = failed.javaClass.simpleName
                false
            }
            acknowledgment(data, success, failure)
        } catch (failed: Exception) {
            // Even a snapshot/ack callback fault must not leave the writer permanently running.
            protocolFailure(failureToken, failureRevision, failed.javaClass.simpleName)
        } finally {
            synchronized(this) {
                running = false
                if (requested) request(immediate = true)
            }
        }
    }
}
