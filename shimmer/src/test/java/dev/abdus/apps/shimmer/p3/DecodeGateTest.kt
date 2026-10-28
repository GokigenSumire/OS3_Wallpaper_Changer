package dev.abdus.apps.shimmer.p3

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeGateTest {
    @Test
    fun runsOneDecodeAtATime() = runBlocking {
        var now = 0L
        val gate = DecodeGate(clock = { synchronized(this@DecodeGateTest) { now += 10; now } })
        val inside = AtomicInteger()
        val observedPeak = AtomicInteger()

        (1..8).map { index ->
            async(Dispatchers.Default) {
                gate.run {
                    observedPeak.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    // A blocking decode: it cannot be interrupted, so overlap would show here.
                    Thread.sleep(5)
                    inside.decrementAndGet()
                    index
                }
            }
        }.awaitAll()

        assertEquals(1, observedPeak.get())
        assertEquals(1, gate.peakConcurrency)
        assertEquals(8, gate.completedCount)
        assertEquals(10L, gate.lastDurationMillis)
    }

    @Test
    fun countsAFailedDecodeAndReleasesTheGate() = runBlocking {
        val gate = DecodeGate(clock = { 0L })
        runCatching { gate.run { error("broken image") } }
        assertEquals(42, gate.run { 42 })
        assertEquals(2, gate.completedCount)
    }
}
