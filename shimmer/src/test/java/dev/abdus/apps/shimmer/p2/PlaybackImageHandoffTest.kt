package dev.abdus.apps.shimmer.p2

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure resource ownership / real coroutine cancellation, not a GLES or device performance test. */
class PlaybackImageHandoffTest {
    private class Resource { val disposed = AtomicInteger() }
    private fun envelope() = PlaybackImageHandoff<Resource> { it.disposed.incrementAndGet() }

    @Test
    fun closingAnEmptyEnvelopeDoesNotDisposeAnything() {
        var releases = 0
        val handoff = PlaybackImageHandoff<Resource> { releases++ }
        handoff.close()
        handoff.close()
        assertEquals(0, releases)
    }

    @Test
    fun capturedUntransferredResourceIsDisposedExactlyOnce() {
        val resource = Resource()
        val handoff = envelope()
        handoff.capture(resource)
        handoff.close()
        handoff.close()
        assertEquals(1, resource.disposed.get())
    }

    @Test
    fun transferredResourceIsNotDisposedByTheEnvelope() {
        val resource = Resource()
        val handoff = envelope()
        handoff.capture(resource)
        handoff.transfer(resource)
        handoff.close()
        handoff.close()
        assertEquals(0, resource.disposed.get())
    }

    @Test
    fun wrongIdentityDoesNotDiscardTheCapturedResource() {
        val resource = Resource()
        val other = Resource()
        val handoff = envelope()
        handoff.capture(resource)
        assertThrows(IllegalStateException::class.java) { handoff.transfer(other) }
        handoff.close()
        assertEquals(1, resource.disposed.get())
        assertEquals(0, other.disposed.get())
    }

    @Test
    fun secondCaptureCannotOverwriteTheFirstResource() {
        val resource = Resource()
        val other = Resource()
        val handoff = envelope()
        handoff.capture(resource)
        assertThrows(IllegalStateException::class.java) { handoff.capture(other) }
        handoff.close()
        assertEquals(1, resource.disposed.get())
        // The documented caller-owned resource of a rejected capture is not silently disposed.
        assertEquals(0, other.disposed.get())
    }

    @Test
    fun disposalFailureIsNotRetriedAndDoesNotReplaceTheResourceIdentity() {
        val resource = Resource()
        val failure = IllegalStateException("dispose")
        val handoff = PlaybackImageHandoff<Resource> { assertSame(resource, it); it.disposed.incrementAndGet(); throw failure }
        handoff.capture(resource)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { handoff.close() })
        handoff.close()
        assertEquals(1, resource.disposed.get())
    }

    @Test
    fun returningAResourceWithoutAHandoffCanLoseItAtPromptCancellation() {
        val main = StepDispatcher()
        val worker = StepDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val resource = Resource()
        var produced = false
        var consumed = false
        val task = scope.launch {
            val result = withContext(worker) { produced = true; resource }
            assertSame(resource, result)
            consumed = true
        }
        try {
            assertTrue(main.runOne())
            assertTrue(worker.runOne())
            assertTrue(produced)
            assertFalse(consumed)
            task.cancel(CancellationException("cancel before Main resumes"))
            main.drain()
            assertTrue(task.isCompleted)
            assertTrue(task.isCancelled)
            assertFalse(consumed)
            assertEquals(0, resource.disposed.get())
        } finally { scope.cancel(); worker.drain(); main.drain() }
    }

    @Test
    fun promptCancellationAfterWorkerReturnsDisposesCapturedResourceExactlyOnce() {
        val main = StepDispatcher()
        val worker = StepDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val resource = Resource()
        val handoff = envelope()
        var produced = false
        var consumed = false
        val task = scope.launch {
            try {
                val result = withContext(worker) { produced = true; handoff.capture(resource); resource }
                consumed = true
                handoff.transfer(result)
            } finally { handoff.close() }
        }
        try {
            assertTrue(main.runOne())
            assertTrue(worker.runOne())
            assertTrue(produced)
            assertFalse(consumed)
            task.cancel(CancellationException("cancel before Main resumes"))
            main.drain()
            assertTrue(task.isCompleted)
            assertTrue(task.isCancelled)
            assertFalse(consumed)
            handoff.close()
            assertEquals(1, resource.disposed.get())
        } finally { scope.cancel(); worker.drain(); main.drain() }
    }

    @Test
    fun ordinaryCrossDispatcherTransferPreservesResourceAndBusinessResult() {
        val main = StepDispatcher()
        val worker = StepDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val resource = Resource()
        val handoff = envelope()
        var owner: Resource? = null
        val task = scope.launch {
            try {
                val result = withContext(worker) { handoff.capture(resource); resource }
                owner = result
                handoff.transfer(result)
            } finally { handoff.close() }
        }
        try {
            assertTrue(main.runOne())
            assertTrue(worker.runOne())
            main.drain()
            assertTrue(task.isCompleted)
            assertFalse(task.isCancelled)
            assertSame(resource, owner)
            assertEquals(0, resource.disposed.get())
        } finally { scope.cancel(); worker.drain(); main.drain() }
    }

    @Test
    fun cancellationBeforeWorkerStartsCannotPublishOrDisposeAnUncreatedResource() {
        val main = StepDispatcher()
        val worker = StepDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val resource = Resource()
        val handoff = envelope()
        var produced = false
        val task = scope.launch {
            try {
                withContext(worker) { produced = true; handoff.capture(resource) }
            } finally { handoff.close() }
        }
        try {
            assertTrue(main.runOne())
            task.cancel()
            worker.drain()
            main.drain()
            assertTrue(task.isCompleted)
            assertTrue(task.isCancelled)
            assertFalse(produced)
            assertEquals(0, resource.disposed.get())
        } finally { scope.cancel(); worker.drain(); main.drain() }
    }

    @Test
    fun exceptionBeforeOwnerAcceptanceDisposesExactlyOnce() {
        val resource = Resource()
        val handoff = envelope()
        val failure = IllegalStateException("before owner")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            try { handoff.capture(resource); throw failure }
            finally { handoff.close() }
        })
        assertEquals(1, resource.disposed.get())
    }

    @Test
    fun exceptionAfterRealOwnerAcceptanceDoesNotRecycleTheNowDisplayedResource() {
        val resource = Resource()
        val handoff = envelope()
        val failure = IllegalStateException("after owner")
        var owner: Resource? = null
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            try {
                handoff.capture(resource)
                owner = resource
                handoff.transfer(resource)
                throw failure
            } finally { handoff.close() }
        })
        assertSame(resource, owner)
        assertEquals(0, resource.disposed.get())
    }

    @Test
    fun transferCannotRunTwiceOrAfterClose() {
        val resource = Resource()
        val handoff = envelope()
        handoff.capture(resource)
        handoff.transfer(resource)
        assertThrows(IllegalStateException::class.java) { handoff.transfer(resource) }
        handoff.close()
        assertThrows(IllegalStateException::class.java) { handoff.transfer(resource) }
        assertEquals(0, resource.disposed.get())
    }

    @Test
    fun aClosedEnvelopeRejectsCaptureWithoutTakingOwnershipFromTheCaller() {
        val resource = Resource()
        val handoff = envelope()
        handoff.close()
        assertThrows(IllegalStateException::class.java) { handoff.capture(resource) }
        handoff.close()
        assertEquals(0, resource.disposed.get())
    }

    @Test
    fun workerFailureAfterCapturePreservesFailureAndDisposesExactlyOnce() {
        val main = StepDispatcher()
        val worker = StepDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val resource = Resource()
        val handoff = envelope()
        val failure = IllegalStateException("worker failed")
        var observed: Throwable? = null
        val task = scope.launch {
            try {
                withContext(worker) { handoff.capture(resource); throw failure }
            } catch (caught: Throwable) { observed = caught }
            finally { handoff.close() }
        }
        try {
            assertTrue(main.runOne())
            assertTrue(worker.runOne())
            main.drain()
            assertTrue(task.isCompleted)
            assertFalse(task.isCancelled)
            val caught = checkNotNull(observed)
            assertEquals(failure.javaClass, caught.javaClass)
            assertEquals(failure.message, caught.message)
            if (caught !== failure) {
                // JVM coroutine debug recovery may copy a standard exception, but it must retain
                // this exact original as its cause and mark the recovered coroutine boundary.
                assertSame(failure, caught.cause)
                assertTrue(caught.stackTrace.any { it.className.startsWith("_COROUTINE.") })
            }
            assertEquals(1, resource.disposed.get())
        } finally { scope.cancel(); worker.drain(); main.drain() }
    }

    @Test
    fun concurrentAndRepeatedCloseHasOneReleaseWinner() {
        val resource = Resource()
        val handoff = envelope()
        handoff.capture(resource)
        val start = CountDownLatch(1)
        val threads = (0 until 6).map {
            Thread { start.await(); repeat(3) { handoff.close() } }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        handoff.close()
        assertEquals(1, resource.disposed.get())
    }

    private class StepDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runOne(): Boolean {
            val next = queue.poll() ?: return false
            next.run()
            return true
        }
        fun drain() { while (runOne()) Unit }
    }
}
