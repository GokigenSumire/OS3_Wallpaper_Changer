package dev.abdus.apps.shimmer.p2

/** Local business-work timing, never a touch-to-screen or GPU presentation measurement. */
enum class PlaybackTimingStage { CATALOG, STATE_PREPARATION, IMAGE_LOAD, COMMIT }

/** Nested in STATE_PREPARATION; never added again to total or remainder. */
enum class PlaybackStateTimingStage { SNAPSHOT_READ, CATALOG_APPLICATION, SNAPSHOT_RESTORE }

enum class PlaybackTimingResult {
    COMMITTED, COMMITTED_SAVE_CANCELLED, COMMITTED_SAVE_ERROR,
    NO_CANDIDATE, DROPPED, STALE, CANCELLED, ERROR,
    DROPPED_EDIT, HELD_EDIT, NOT_READY, DEFERRED_TRANSITION, POLICY_SKIP,
}

data class PlaybackStepMeasurement(
    val result: PlaybackTimingResult,
    val catalogMillis: Long,
    val stateMillis: Long,
    val imageLoadMillis: Long,
    val commitMillis: Long,
    val remainderMillis: Long,
    val totalMillis: Long,
    val imageAttempts: Int,
    val catalogItems: Int,
    val order: PlaybackOrder?,
    val stateSnapshotMillis: Long = 0,
    val stateCatalogMillis: Long = 0,
    val stateRestoreMillis: Long = 0,
    val catalogParts: PlaybackCatalogMeasurement? = null,
    val selected: Boolean = false,
) {
    fun diagnosticFields(): String = "result=$result catalogMs=$catalogMillis stateMs=$stateMillis " +
        "stateSnapshotMs=$stateSnapshotMillis stateCatalogMs=$stateCatalogMillis stateRestoreMs=$stateRestoreMillis " +
        "imageLoadMs=$imageLoadMillis commitMs=$commitMillis remainderMs=$remainderMillis " +
        "totalMs=$totalMillis imageAttempts=$imageAttempts items=$catalogItems order=${order ?: "UNKNOWN"} " +
        "timingBoundary=BUSINESS_TO_MAIN_APPLY notTouchOrGpu=true"
}

/**
 * One request owns one timer. Injectable monotonic nanoseconds keep tests independent of Android.
 * The Service supplies elapsedRealtimeNanos, including coroutine/lock wait in total and remainder.
 * Completion is synchronized and idempotent because a cancelled launch may finish off Main.
 */
class PlaybackStepTiming(private val clockNanos: () -> Long = System::nanoTime) {
    val catalogTiming = PlaybackCatalogTiming(clockNanos)
    private val startedAt = clockNanos()
    private val durations = LongArray(PlaybackTimingStage.entries.size)
    private val stateDurations = LongArray(PlaybackStateTimingStage.entries.size)
    private var completed = false
    private var attempts = 0
    private var items = 0
    private var order: PlaybackOrder? = null
    private var selected = false
    @Synchronized fun markSelected() { if (!completed) selected = true }

    @Synchronized fun catalog(itemCount: Int) { if (!completed) items = itemCount.coerceAtLeast(0) }
    @Synchronized fun order(value: PlaybackOrder) { if (!completed) order = value }

    suspend fun <T> measure(stage: PlaybackTimingStage, block: suspend () -> T): T {
        val start = clockNanos()
        if (stage == PlaybackTimingStage.IMAGE_LOAD) synchronized(this) {
            if (!completed && attempts < Int.MAX_VALUE) attempts++
        }
        try {
            return block()
        } finally {
            record(stage, elapsed(start, clockNanos()))
        }
    }

    @Synchronized private fun record(stage: PlaybackTimingStage, nanos: Long) {
        if (!completed) durations[stage.ordinal] = saturatedAdd(durations[stage.ordinal], nanos)
    }

    fun <T> measureState(stage: PlaybackStateTimingStage, block: () -> T): T {
        val start = clockNanos()
        try {
            return block()
        } finally {
            recordState(stage, elapsed(start, clockNanos()))
        }
    }

    /** The synchronous coordinator supplies the one catalog/restore boundary without extra work. */
    fun <T> measureCatalogAndRestore(block: (() -> Unit) -> T): T {
        val start = clockNanos()
        var catalogEnd: Long? = null
        try {
            return block { if (catalogEnd == null) catalogEnd = clockNanos() }
        } finally {
            val end = clockNanos()
            val boundary = catalogEnd
            recordState(PlaybackStateTimingStage.CATALOG_APPLICATION, elapsed(start, boundary ?: end))
            if (boundary != null) recordState(PlaybackStateTimingStage.SNAPSHOT_RESTORE, elapsed(boundary, end))
        }
    }

    @Synchronized private fun recordState(stage: PlaybackStateTimingStage, nanos: Long) {
        if (!completed) stateDurations[stage.ordinal] = saturatedAdd(stateDurations[stage.ordinal], nanos)
    }

    @Synchronized fun finish(result: PlaybackTimingResult): PlaybackStepMeasurement? {
        if (completed) return null
        completed = true
        val total = elapsed(startedAt, clockNanos())
        val measured = durations.fold(0L, ::saturatedAdd)
        return PlaybackStepMeasurement(
            result = result,
            catalogMillis = durations[PlaybackTimingStage.CATALOG.ordinal] / NANOS_PER_MILLI,
            stateMillis = durations[PlaybackTimingStage.STATE_PREPARATION.ordinal] / NANOS_PER_MILLI,
            imageLoadMillis = durations[PlaybackTimingStage.IMAGE_LOAD.ordinal] / NANOS_PER_MILLI,
            commitMillis = durations[PlaybackTimingStage.COMMIT.ordinal] / NANOS_PER_MILLI,
            remainderMillis = (total - measured.coerceAtMost(total)) / NANOS_PER_MILLI,
            totalMillis = total / NANOS_PER_MILLI,
            imageAttempts = attempts,
            catalogItems = items,
            order = order,
            stateSnapshotMillis = stateDurations[PlaybackStateTimingStage.SNAPSHOT_READ.ordinal] / NANOS_PER_MILLI,
            stateCatalogMillis = stateDurations[PlaybackStateTimingStage.CATALOG_APPLICATION.ordinal] / NANOS_PER_MILLI,
            stateRestoreMillis = stateDurations[PlaybackStateTimingStage.SNAPSHOT_RESTORE.ordinal] / NANOS_PER_MILLI,
            catalogParts = catalogTiming.finish(),
            selected = selected,
        )
    }

    private fun elapsed(start: Long, end: Long): Long =
        if (end < start) 0L else if (start < 0 && end > Long.MAX_VALUE + start) Long.MAX_VALUE else end - start

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object { const val NANOS_PER_MILLI = 1_000_000L }
}
