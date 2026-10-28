package dev.abdus.apps.shimmer.p5

import dev.abdus.apps.shimmer.p3.FrameStats

/** Outcome of one frozen P5 threshold on the current samples. */
enum class Verdict { PASS, FAIL, INSUFFICIENT }

/**
 * Process-wide transition diagnostics for the frozen P5 thresholds X1～X7. Only real (non-preview)
 * engines record. A transition buffers its frames in a [Recorder] on the render thread; they enter
 * the shared samples when it ends, and a transition that was hidden part-way ([Recorder.paused])
 * is kept out of X1～X3.
 */
class TransitionStats(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lock = Any()
    private val draws = LongRing(capacity)
    private val intervals = LongRing(capacity)
    private val overshoots = ArrayDeque<Overshoot>()
    private val heaps = ArrayDeque<HeapSample>()
    private val recent = ArrayDeque<String>()
    private var sequence = 0L
    private var interruptedCount = 0
    private var pausedCount = 0
    private var residualCount = 0
    private var peakLayerMax = 0
    private var peakTargetMax = 0
    private var staleFrameCount = 0
    private var fallbackCount = 0

    private class Overshoot(val effect: TransitionEffect, val millis: Long)
    private class HeapSample(val sequence: Long, val imageId: String, val bytes: Long)

    /** Render-thread buffer for one transition; [finish] it once, never share it. */
    inner class Recorder internal constructor(val effect: TransitionEffect, val durationMillis: Int) {
        private val drawNanos = LongArray(RECORDER_CAPACITY)
        private val intervalNanos = LongArray(RECORDER_CAPACITY)
        private var drawCount = 0
        private var intervalCount = 0
        private var lastFrameStart = 0L
        var frames = 0
            private set
        var paused = false

        /** One drawn transition frame: CPU time and its start, for the interval to the previous one. */
        fun frame(drawNanos: Long, startNanos: Long) {
            if (drawCount < RECORDER_CAPACITY) this.drawNanos[drawCount++] = drawNanos.coerceAtLeast(0L)
            if (frames > 0 && intervalCount < RECORDER_CAPACITY) {
                intervalNanos[intervalCount++] = (startNanos - lastFrameStart).coerceAtLeast(0L)
            }
            lastFrameStart = startNanos
            frames++
        }

        /**
         * Ends this transition. [actualMillis] runs from its start to the first frame at full
         * progress (ignored when [interrupted]); [residual] counts GL objects of the outgoing image
         * and the offscreen target still held after release; [nativeHeapBytes] is sampled now.
         */
        fun finish(
            interrupted: Boolean,
            actualMillis: Long,
            residual: Int,
            peakLayers: Int,
            peakTargets: Int,
            nativeHeapBytes: Long,
            imageId: String,
        ): Unit = synchronized(lock) {
            sequence++
            if (interrupted) interruptedCount++
            if (paused) pausedCount++
            if (residual > 0) residualCount++
            peakLayerMax = maxOf(peakLayerMax, peakLayers)
            peakTargetMax = maxOf(peakTargetMax, peakTargets)
            if (!paused) {
                for (i in 0 until drawCount) draws.add(drawNanos[i])
                for (i in 0 until intervalCount) intervals.add(intervalNanos[i])
                if (!interrupted && effect != TransitionEffect.NONE && durationMillis > 0) {
                    overshoots.addLast(Overshoot(effect, actualMillis - durationMillis))
                    if (overshoots.size > capacity) overshoots.removeFirst()
                }
            }
            heaps.addLast(HeapSample(sequence, imageId, nativeHeapBytes))
            if (heaps.size > HEAP_HISTORY) heaps.removeFirst()
            val state = when {
                interrupted -> "interrupted"
                paused -> "paused"
                else -> "${actualMillis}ms"
            }
            recent.addLast("#$sequence $effect ${durationMillis}ms frames=$frames $state residual=$residual")
            if (recent.size > RECENT) recent.removeFirst()
        }
    }

    /** Starts buffering one transition of [effect] lasting [durationMillis]. */
    fun open(effect: TransitionEffect, durationMillis: Int): Recorder = Recorder(effect, durationMillis)

    /** A frame drawn with no transition while the outgoing image still held textures (X5). */
    fun recordStaleFrame(): Unit = synchronized(lock) { staleFrameCount++ }

    /** The offscreen target could not be created; the switch used the legacy direct blend. */
    fun recordTargetFallback(): Unit = synchronized(lock) { fallbackCount++ }

    fun reset(): Unit = synchronized(lock) {
        draws.clear(); intervals.clear(); overshoots.clear(); heaps.clear(); recent.clear()
        sequence = 0L; interruptedCount = 0; pausedCount = 0; residualCount = 0
        peakLayerMax = 0; peakTargetMax = 0; staleFrameCount = 0; fallbackCount = 0
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val drawSorted = draws.toSortedArray()
        val intervalSorted = intervals.toSortedArray()
        val overshootSorted = overshoots.map { it.millis }.toLongArray().also { it.sort() }
        Snapshot(
            transitions = sequence.toInt(),
            interrupted = interruptedCount,
            paused = pausedCount,
            drawSamples = drawSorted.size,
            drawP95Millis = FrameStats.percentile(drawSorted, 95)?.let { it / 1_000_000.0 },
            drawP99Millis = FrameStats.percentile(drawSorted, 99)?.let { it / 1_000_000.0 },
            drawOverBudgetFraction = if (drawSorted.isEmpty()) 0.0
                else drawSorted.count { it > FrameStats.DRAW_BUDGET_NANOS }.toDouble() / drawSorted.size,
            intervalSamples = intervalSorted.size,
            intervalP95Millis = FrameStats.percentile(intervalSorted, 95)?.let { it / 1_000_000.0 },
            intervalMaxMillis = intervalSorted.lastOrNull()?.let { it / 1_000_000.0 },
            completedByEffect = TransitionEffect.ANIMATED.associateWith { e -> overshoots.count { it.effect == e } },
            overshootP95Millis = FrameStats.percentile(overshootSorted, 95),
            overshootMaxMillis = overshootSorted.lastOrNull(),
            residualTransitions = residualCount,
            peakLayers = peakLayerMax,
            peakTargets = peakTargetMax,
            staleFrames = staleFrameCount,
            targetFallbacks = fallbackCount,
            heapGrowth = heapGrowth(),
            recent = recent.toList(),
        )
    }

    /** Latest sample against the earliest one of the same image at least [HEAP_MIN_SPAN] earlier. */
    private fun heapGrowth(): HeapGrowth? {
        val latest = heaps.lastOrNull() ?: return null
        val base = heaps.firstOrNull { it.imageId == latest.imageId && latest.sequence - it.sequence >= HEAP_MIN_SPAN }
            ?: return null
        return HeapGrowth(latest.bytes - base.bytes, (latest.sequence - base.sequence).toInt())
    }

    data class HeapGrowth(val bytes: Long, val span: Int)

    data class Snapshot(
        val transitions: Int,
        val interrupted: Int,
        val paused: Int,
        val drawSamples: Int,
        val drawP95Millis: Double?,
        val drawP99Millis: Double?,
        val drawOverBudgetFraction: Double,
        val intervalSamples: Int,
        val intervalP95Millis: Double?,
        val intervalMaxMillis: Double?,
        /** Completed, uninterrupted, unpaused transitions per animated effect (X1/X3 coverage). */
        val completedByEffect: Map<TransitionEffect, Int>,
        val overshootP95Millis: Long?,
        val overshootMaxMillis: Long?,
        val residualTransitions: Int,
        val peakLayers: Int,
        val peakTargets: Int,
        val staleFrames: Int,
        val targetFallbacks: Int,
        val heapGrowth: HeapGrowth?,
        val recent: List<String>,
    ) {
        private val effectsCovered: Boolean
            get() = TransitionEffect.ANIMATED.all { (completedByEffect[it] ?: 0) >= MIN_PER_EFFECT }

        val x1: Verdict get() = when {
            drawSamples < FrameStats.MIN_FRAME_SAMPLES || !effectsCovered -> Verdict.INSUFFICIENT
            (drawP95Millis ?: 0.0) <= FrameStats.DRAW_P95_MS && (drawP99Millis ?: 0.0) <= FrameStats.DRAW_P99_MS &&
                drawOverBudgetFraction <= FrameStats.DRAW_OVER_BUDGET_MAX_FRACTION -> Verdict.PASS
            else -> Verdict.FAIL
        }

        val x2: Verdict get() = when {
            intervalSamples < MIN_INTERVAL_SAMPLES -> Verdict.INSUFFICIENT
            (intervalP95Millis ?: 0.0) <= INTERVAL_P95_MS && (intervalMaxMillis ?: 0.0) <= INTERVAL_MAX_MS -> Verdict.PASS
            else -> Verdict.FAIL
        }

        val x3: Verdict get() = when {
            !effectsCovered -> Verdict.INSUFFICIENT
            (overshootP95Millis ?: 0L) <= OVERSHOOT_P95_MS -> Verdict.PASS
            else -> Verdict.FAIL
        }

        /** One leak or over-budget peak fails at once; otherwise the sample size must be met. */
        val x4: Verdict get() = when {
            residualTransitions > 0 || peakLayers > MAX_LAYERS || peakTargets > MAX_TARGETS -> Verdict.FAIL
            transitions < MIN_TRANSITIONS -> Verdict.INSUFFICIENT
            else -> Verdict.PASS
        }

        /** Automatic part only; the visual check of `TC-FX-02` is judged by the user. */
        val x5: Verdict get() = when {
            staleFrames > 0 -> Verdict.FAIL
            transitions < MIN_TRANSITIONS -> Verdict.INSUFFICIENT
            else -> Verdict.PASS
        }

        val x7: Verdict get() {
            val growth = heapGrowth
            return when {
                growth == null || transitions < MIN_TRANSITIONS -> Verdict.INSUFFICIENT
                growth.bytes <= HEAP_GROWTH_MAX_BYTES -> Verdict.PASS
                else -> Verdict.FAIL
            }
        }
    }

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

        fun toSortedArray(): LongArray = LongArray(size) { values[(next - size + it + capacity) % capacity] }.also { it.sort() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 8_192
        const val RECORDER_CAPACITY = 1_024
        const val RECENT = 8
        const val HEAP_HISTORY = 512
        const val MIN_PER_EFFECT = 3
        const val MIN_INTERVAL_SAMPLES = 300
        const val MIN_TRANSITIONS = 40
        const val INTERVAL_P95_MS = 20.0
        const val INTERVAL_MAX_MS = 100.0
        const val OVERSHOOT_P95_MS = 50L
        const val MAX_LAYERS = 2
        const val MAX_TARGETS = 1
        const val HEAP_MIN_SPAN = 30
        const val HEAP_GROWTH_MAX_BYTES = 64L * 1024 * 1024

        /** Process-wide counters shown on the P5 page; only real engines record into it. */
        val shared = TransitionStats()
    }
}
