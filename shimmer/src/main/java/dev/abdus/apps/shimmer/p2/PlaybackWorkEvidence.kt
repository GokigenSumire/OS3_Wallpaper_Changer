package dev.abdus.apps.shimmer.p2

import java.util.concurrent.atomic.AtomicLong

enum class PlaybackWorkScope { NORMAL, SELECTED, EDITOR }

/** Numeric request-local work; unobserved stages are explicitly unknown, never invented zeros. */
data class PlaybackWorkReport(
    val traceId: Long,
    val scope: PlaybackWorkScope,
    val preparationObserved: Boolean,
    val storageObserved: Boolean,
    val catalogItemsVisited: Long,
    val fullRestoreCount: Long,
    val preparationQueueRebuilds: Long,
    val runtimeCharacters: Long,
    val manualIdsEncoded: Long,
    val manualConfigurationWrites: Long,
    val manualIdsCopied: Long,
) {
    /** Fixed names and numbers only: no paths, item IDs, exception messages or wall-clock data. */
    fun diagnosticFields(): String {
        fun observed(known: Boolean, count: Long): String = if (known) count.toString() else "UNKNOWN"
        return "trace=$traceId scope=$scope preparationObserved=$preparationObserved storageObserved=$storageObserved " +
            "catalogItemsVisited=${observed(preparationObserved, catalogItemsVisited)} " +
            "fullRestoreCount=${observed(preparationObserved, fullRestoreCount)} " +
            "preparationQueueRebuilds=${observed(preparationObserved, preparationQueueRebuilds)} " +
            "runtimeCharacters=${observed(storageObserved, runtimeCharacters)} " +
            "manualIdsEncoded=${observed(storageObserved, manualIdsEncoded)} " +
            "manualConfigurationWrites=${observed(storageObserved, manualConfigurationWrites)} " +
            "manualIdsCopied=${observed(storageObserved, manualIdsCopied)}"
    }
}

/** One attempt, independent from all timing clocks and from other attempts or engines. */
class PlaybackWorkEvidence(private val scope: PlaybackWorkScope) {
    val traceId: Long = nextTraceId()
    private val lock = Any()
    private var finished = false
    private var preparationObserved = false
    private var storageObserved = false
    private var catalogItemsVisited = 0L
    private var fullRestoreCount = 0L
    private var preparationQueueRebuilds = 0L
    private var runtimeCharacters = 0L
    private var manualIdsEncoded = 0L
    private var manualConfigurationWrites = 0L
    private var manualIdsCopied = 0L

    fun preparation(work: CoordinatorPreparationWork) = synchronized(lock) {
        require(work.catalogItemsVisited >= 0 && work.fullRestoreCount >= 0 && work.queueRebuilds >= 0)
        if (finished) return@synchronized
        preparationObserved = true
        catalogItemsVisited = saturatingAdd(catalogItemsVisited, work.catalogItemsVisited)
        fullRestoreCount = saturatingAdd(fullRestoreCount, work.fullRestoreCount.toLong())
        preparationQueueRebuilds = saturatingAdd(preparationQueueRebuilds, work.queueRebuilds.toLong())
    }

    fun storage(work: StoreWriteWork) = synchronized(lock) {
        require(work.runtimeCharacters >= 0 && work.manualIdsEncoded >= 0 && work.manualConfigurationWrites >= 0 && work.manualIdsCopied >= 0)
        if (finished) return@synchronized
        storageObserved = true
        runtimeCharacters = saturatingAdd(runtimeCharacters, work.runtimeCharacters.toLong())
        manualIdsEncoded = saturatingAdd(manualIdsEncoded, work.manualIdsEncoded.toLong())
        manualConfigurationWrites = saturatingAdd(manualConfigurationWrites, work.manualConfigurationWrites.toLong())
        manualIdsCopied = saturatingAdd(manualIdsCopied, work.manualIdsCopied.toLong())
    }

    /** Exactly one immutable report per attempt; repeated completion never reports twice. */
    fun finish(): PlaybackWorkReport? = synchronized(lock) {
        if (finished) return@synchronized null
        finished = true
        PlaybackWorkReport(traceId, scope, preparationObserved, storageObserved, catalogItemsVisited,
            fullRestoreCount, preparationQueueRebuilds, runtimeCharacters, manualIdsEncoded,
            manualConfigurationWrites, manualIdsCopied)
    }

    companion object {
        private val traceIds = AtomicLong()
        private fun nextTraceId(): Long {
            while (true) {
                val previous = traceIds.get()
                check(previous < Long.MAX_VALUE) { "Playback work trace exhausted" }
                if (traceIds.compareAndSet(previous, previous + 1L)) return previous + 1L
            }
        }

        internal fun saturatingAdd(current: Long, addition: Long): Long {
            require(current >= 0 && addition >= 0)
            return if (addition > Long.MAX_VALUE - current) Long.MAX_VALUE else current + addition
        }
    }
}
