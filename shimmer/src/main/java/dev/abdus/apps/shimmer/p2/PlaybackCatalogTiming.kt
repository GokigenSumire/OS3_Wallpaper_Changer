package dev.abdus.apps.shimmer.p2

/** Nested within catalogMs, not extra work to add again to playback total. */
enum class PlaybackCatalogTimingStage { DECISIONS, DATABASE, MAPPING }

data class PlaybackCatalogMeasurement(
    val decisionMillis: Long,
    val databaseMillis: Long,
    val mappingMillis: Long,
) {
    fun diagnosticFields(): String =
        "catalogDecisionMs=$decisionMillis catalogDatabaseMs=$databaseMillis catalogMappingMs=$mappingMillis " +
            "nestedIn=catalogMs databaseIncludesRuleAndProjection=true notTouchOrGpu=true"
}

/** Request-local and bounded; no source strings, listeners, cached metadata or global state. */
class PlaybackCatalogTiming(private val clockNanos: () -> Long = System::nanoTime) {
    private val durations = LongArray(PlaybackCatalogTimingStage.entries.size)
    private var attempted = false
    private var finished = false

    suspend fun <T> measure(stage: PlaybackCatalogTimingStage, block: suspend () -> T): T {
        synchronized(this) { if (!finished) attempted = true }
        val start = clockNanos()
        try {
            return block()
        } finally {
            val end = clockNanos()
            val elapsed = if (end < start) 0L
                else if (start < 0 && end > Long.MAX_VALUE + start) Long.MAX_VALUE else end - start
            synchronized(this) {
                if (!finished) {
                    val old = durations[stage.ordinal]
                    durations[stage.ordinal] = if (old > Long.MAX_VALUE - elapsed) Long.MAX_VALUE else old + elapsed
                }
            }
        }
    }

    @Synchronized fun finish(): PlaybackCatalogMeasurement? {
        if (finished) return null
        finished = true
        if (!attempted) return null
        return PlaybackCatalogMeasurement(
            durations[PlaybackCatalogTimingStage.DECISIONS.ordinal] / 1_000_000,
            durations[PlaybackCatalogTimingStage.DATABASE.ordinal] / 1_000_000,
            durations[PlaybackCatalogTimingStage.MAPPING.ordinal] / 1_000_000,
        )
    }
}
