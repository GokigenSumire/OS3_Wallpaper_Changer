package dev.abdus.apps.shimmer.p3

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * At most one decode at a time for one Engine.
 *
 * Cancelling a coroutine does not interrupt a blocking bitmap decode, so without this a cancelled
 * load could still be decoding while its replacement starts a second one. The counters feed the
 * editor's measurement line.
 */
class DecodeGate(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private val mutex = Mutex()
    private val active = AtomicInteger()
    private val peak = AtomicInteger()
    private val completed = AtomicInteger()

    @Volatile var lastDurationMillis = -1L
        private set

    val peakConcurrency: Int get() = peak.get()
    val completedCount: Int get() = completed.get()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val now = active.incrementAndGet()
        peak.accumulateAndGet(now, ::maxOf)
        val start = clock()
        try {
            block()
        } finally {
            lastDurationMillis = clock() - start
            active.decrementAndGet()
            completed.incrementAndGet()
        }
    }
}
