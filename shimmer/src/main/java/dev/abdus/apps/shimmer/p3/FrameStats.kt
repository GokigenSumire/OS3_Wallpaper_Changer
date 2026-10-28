package dev.abdus.apps.shimmer.p3

import kotlin.math.ceil

/**
 * P3 measurement counters (F08). Thresholds mirror the frozen table in P3_PLAN.md and must not be
 * changed after measurements start.
 *
 * Written from the render thread (draws) and the main thread (entry), read by the edit page.
 */
class FrameStats(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lock = Any()
    private val drawNanos = LongRing(capacity)
    private val inputMillis = LongRing(capacity)
    private val saveMillis = LongRing(RECENT_EVENTS)
    private val entryMillis = LongRing(RECENT_EVENTS)
    private var drawCount = 0L
    private var lastDrawUptime = 0L

    fun recordDraw(durationNanos: Long, endUptimeMillis: Long) = synchronized(lock) {
        drawNanos.add(durationNanos.coerceAtLeast(0L))
        drawCount++
        lastDrawUptime = endUptimeMillis
    }

    fun recordInputLatency(millis: Long) = synchronized(lock) { inputMillis.add(millis.coerceAtLeast(0L)) }

    fun recordSave(millis: Long) = synchronized(lock) { saveMillis.add(millis.coerceAtLeast(0L)) }

    fun recordEntry(millis: Long) = synchronized(lock) { entryMillis.add(millis.coerceAtLeast(0L)) }

    fun reset() = synchronized(lock) {
        drawNanos.clear(); inputMillis.clear(); saveMillis.clear(); entryMillis.clear()
        drawCount = 0L
        lastDrawUptime = 0L
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val draws = drawNanos.toSortedArray()
        val inputs = inputMillis.toSortedArray()
        Snapshot(
            drawSamples = draws.size,
            drawP95Millis = percentile(draws, 95)?.let { it / 1_000_000.0 },
            drawP99Millis = percentile(draws, 99)?.let { it / 1_000_000.0 },
            drawOverBudgetFraction = if (draws.isEmpty()) null
                else draws.count { it > DRAW_BUDGET_NANOS }.toDouble() / draws.size,
            inputSamples = inputs.size,
            inputP95Millis = percentile(inputs, 95),
            inputP99Millis = percentile(inputs, 99),
            recentSaveMillis = saveMillis.toList(),
            recentEntryMillis = entryMillis.toList(),
            totalDraws = drawCount,
            lastDrawUptimeMillis = lastDrawUptime,
        )
    }

    data class Snapshot(
        val drawSamples: Int,
        val drawP95Millis: Double?,
        val drawP99Millis: Double?,
        val drawOverBudgetFraction: Double?,
        val inputSamples: Int,
        val inputP95Millis: Long?,
        val inputP99Millis: Long?,
        val recentSaveMillis: List<Long>,
        val recentEntryMillis: List<Long>,
        val totalDraws: Long,
        val lastDrawUptimeMillis: Long,
    ) {
        /** Null until at least [MIN_FRAME_SAMPLES] frames are recorded, then the frozen verdict. */
        val latencyPass: Boolean?
            get() = if (inputSamples < MIN_FRAME_SAMPLES) null
                else inputP95Millis!! <= INPUT_P95_MS && inputP99Millis!! <= INPUT_P99_MS

        val drawPass: Boolean?
            get() = if (drawSamples < MIN_FRAME_SAMPLES) null
                else drawP95Millis!! <= DRAW_P95_MS && drawP99Millis!! <= DRAW_P99_MS &&
                    drawOverBudgetFraction!! <= DRAW_OVER_BUDGET_MAX_FRACTION
    }

    /** Bounded FIFO; the oldest sample is overwritten once full. */
    private class LongRing(private val capacity: Int) {
        private val values = LongArray(capacity)
        private var next = 0
        private var size = 0

        fun add(value: Long) {
            values[next] = value
            next = (next + 1) % capacity
            if (size < capacity) size++
        }

        fun clear() { next = 0; size = 0 }

        fun toList(): List<Long> = List(size) { values[(next - size + it + capacity) % capacity] }

        fun toSortedArray(): LongArray = toList().toLongArray().also { it.sort() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 8_192
        const val RECENT_EVENTS = 5
        const val MIN_FRAME_SAMPLES = 300
        const val INPUT_P95_MS = 50L
        const val INPUT_P99_MS = 100L
        const val DRAW_P95_MS = 8.0
        const val DRAW_P99_MS = 16.7
        const val DRAW_OVER_BUDGET_MAX_FRACTION = 0.01
        const val DRAW_BUDGET_NANOS = 16_700_000L
        const val ENTRY_MAX_MS = 1_500L
        const val SAVE_MAX_MS = 1_000L

        /** Process-wide counters shown on the edit page; only the edited engine records into it. */
        val shared = FrameStats()

        /** Nearest-rank percentile of an ascending array; null when empty. */
        fun percentile(sorted: LongArray, percent: Int): Long? {
            if (sorted.isEmpty()) return null
            val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
