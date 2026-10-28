package dev.abdus.apps.shimmer.p1

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class P1MetadataValidatorTest {
    private fun outcome(count: Int) = P1ScanOutcome.Complete((0 until count).map {
        P1ScannedImage("$it", "s", "file:///$it", "$it", "$it", null)
    }, count + 1)

    @Test fun boundedWorkersStillCheckEveryImageAndPreserveOrder() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val progress = mutableListOf<Int>()
        val result = P1MetadataValidator.validate(outcome(80), {
            calls.incrementAndGet()
            val current = active.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, current) }
            try { delay((it.stableKey.toInt() % 4 + 1).toLong()); P1MetadataValidator.Dimensions(10, 20) }
            finally { active.decrementAndGet() }
        }, { n, _ -> progress += n }) as P1ScanOutcome.Complete
        assertEquals(80, calls.get())
        assertTrue(peak.get() in 2..4)
        assertEquals((0 until 80).map { "$it" }, result.images.map { it.stableKey })
        assertEquals((1..80).toList(), progress)
        assertEquals(0, active.get())
        assertTrue(result.images.all { it.width == 10 && it.height == 20 })
    }
    @Test fun malformedIndividualRowsAreIsolated() = runBlocking {
        val result = P1MetadataValidator.validate(outcome(4), {
            when (it.stableKey) {
                "0" -> null
                "1" -> throw java.io.IOException()
                "2" -> P1MetadataValidator.Dimensions(-1, 10)
                else -> P1MetadataValidator.Dimensions(1, 1)
            }
        }) as P1ScanOutcome.Complete
        assertEquals(listOf("3"), result.images.map { it.stableKey })
        assertEquals(3, result.skippedMetadata)
    }
    @Test fun allInvalidIsFailureNotEmptySuccess() = runBlocking {
        val result = P1MetadataValidator.validate(outcome(2), { null })
        assertEquals(P1ScanFailure.METADATA, (result as P1ScanOutcome.Failed).reason)
        assertTrue(P1ScanCommitGate.decide(result) is P1ScanCommitDecision.Preserve)
    }
    @Test fun permissionFailureCancelsAndJoinsSiblings() = runBlocking {
        val active = AtomicInteger()
        val result = P1MetadataValidator.validate(outcome(12), {
            active.incrementAndGet()
            try {
                if (it.stableKey == "0") { delay(20); throw SecurityException() }
                delay(1000)
                P1MetadataValidator.Dimensions(1, 1)
            } finally { active.decrementAndGet() }
        })
        assertEquals(P1ScanFailure.PERMISSION, (result as P1ScanOutcome.Failed).reason)
        assertEquals(0, active.get())
    }
    @Test fun cancellationPropagatesAndAllWorkersAreJoined() = runBlocking {
        val active = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val task = launch {
            P1MetadataValidator.validate(outcome(100), {
                active.incrementAndGet()
                ready.complete(Unit)
                try { delay(10_000); P1MetadataValidator.Dimensions(1, 1) }
                finally { active.decrementAndGet() }
            })
            fail("cancelled validation must not publish Complete")
        }
        ready.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(0, active.get())
    }
    @Test fun emptyEnumerationDoesNotStartReaders() = runBlocking {
        val result = P1MetadataValidator.validate(outcome(0), { error("unexpected reader") }) as P1ScanOutcome.Complete
        assertTrue(result.images.isEmpty())
    }
    @Test(expected = CancellationException::class) fun readerCancellationIsNeverMalformedMetadata() = runBlocking {
        P1MetadataValidator.validate(outcome(1), { throw CancellationException("cancel") })
        Unit
    }

    @Test fun slowFirstReaderCannotStrandLaterIndicesInItsOldStripe() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val laterRead = CompletableDeferred<Unit>()
        val counts = java.util.concurrent.atomic.AtomicIntegerArray(9)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val task = async {
            P1MetadataValidator.validate(outcome(9), { image ->
                val index = image.stableKey.toInt()
                counts.incrementAndGet(index)
                val current = active.incrementAndGet()
                peak.updateAndGet { old -> maxOf(old, current) }
                try {
                    if (index == 0) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    } else {
                        firstStarted.await()
                        // Index 4 was owned by the blocked index-0 stripe in version 43.
                        if (index == 4) laterRead.complete(Unit)
                    }
                    P1MetadataValidator.Dimensions(index + 1, 20)
                } finally { active.decrementAndGet() }
            }) as P1ScanOutcome.Complete
        }
        try {
            withTimeout(5_000) { laterRead.await() }
            assertFalse("Slow reader is deliberately still held", releaseFirst.isCompleted)
        } finally { releaseFirst.complete(Unit) }
        val result = task.await()
        assertEquals((0 until 9).map { "$it" }, result.images.map { it.stableKey })
        assertEquals((1..9).toList(), result.images.map { it.width })
        repeat(9) { assertEquals("Each input receives one real check", 1, counts.get(it)) }
        assertTrue(peak.get() in 2..P1MetadataValidator.MAX_WORKERS)
        assertEquals(0, active.get())
    }

    @Test fun dynamicWorkersVisitEveryUnevenTailExactlyOnce() = runBlocking {
        for (count in listOf(1, 2, 3, 5, 7, 17, 1001)) {
            val calls = java.util.concurrent.atomic.AtomicIntegerArray(count)
            val progress = mutableListOf<Int>()
            val result = P1MetadataValidator.validate(outcome(count), { image ->
                val index = image.stableKey.toInt()
                calls.incrementAndGet(index)
                if (index % 5 == 0) yield()
                P1MetadataValidator.Dimensions(1, 1)
            }, { processed, total ->
                assertEquals(count, total)
                progress += processed
            }) as P1ScanOutcome.Complete
            assertEquals(count, result.images.size)
            assertEquals((1..count).toList(), progress)
            repeat(count) { assertEquals(1, calls.get(it)) }
            assertEquals((0 until count).map { "$it" }, result.images.map { it.stableKey })
        }
    }

    @Test fun failureAfterDynamicWorkersClaimMoreWorkStillJoinsEveryReader() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val result = P1MetadataValidator.validate(outcome(30), { image ->
            active.incrementAndGet()
            try {
                when (image.stableKey) {
                    "0" -> { firstStarted.complete(Unit); delay(60_000) }
                    "4" -> { firstStarted.await(); throw SecurityException("later permission failure") }
                    else -> firstStarted.await()
                }
                P1MetadataValidator.Dimensions(1, 1)
            } finally { active.decrementAndGet() }
        })
        assertEquals(P1ScanFailure.PERMISSION, (result as P1ScanOutcome.Failed).reason)
        assertEquals(0, active.get())
        assertTrue(P1ScanCommitGate.decide(result) is P1ScanCommitDecision.Preserve)
    }
}
