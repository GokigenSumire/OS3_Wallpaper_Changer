package dev.abdus.apps.shimmer.p2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 只允许真实指定目标成功后再由外层提交；坏项不能偷换其它图。 */
class PlaybackSelectedResolverTest {
    private fun coordinator() = PlaybackCoordinator().apply {
        setItems(listOf("a", "b", "c").map { PlaybackItem(it, it, sourceId = "A") })
        requestNext().also { assertTrue(commit(it.version)) }
    }

    @Test fun selectedTargetLoadsOnceWithoutCommittingState() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        val calls = mutableListOf<String>()
        val loaded = PlaybackImageResolver<String>(c).resolveSelected("c") { request, item ->
            assertTrue(request.selected)
            assertTrue(request.freshAdvance)
            assertNull(request.entryTransform)
            calls += item.id
            "decoded-${item.id}"
        }
        assertEquals(listOf("c"), calls)
        assertEquals("decoded-c", loaded!!.image)
        assertEquals(before, c.snapshot())
        assertTrue(c.commit(loaded.request.version))
        assertEquals("c", c.current()!!.id)
    }

    @Test fun nullDecodeRejectsOnlyTargetWithoutTryingFallback() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        val calls = mutableListOf<String>()
        var version = -1L
        val loaded = PlaybackImageResolver<String>(c).resolveSelected("b") { request, item ->
            version = request.version
            calls += item.id
            null
        }
        assertNull(loaded)
        assertEquals(listOf("b"), calls)
        assertEquals(before, c.snapshot())
        assertFalse(c.commit(version))
    }

    @Test fun decoderCancellationRejectsAndPropagatesOriginalCancellation() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        val cancelled = CancellationException("selected-cancel")
        var version = -1L
        try {
            PlaybackImageResolver<String>(c).resolveSelected("b") { request, _ ->
                version = request.version
                throw cancelled
            }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertFalse(c.commit(version))
        assertEquals(before, c.snapshot())
    }

    @Test fun ordinaryExceptionRejectsAndPropagatesWithoutFallback() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        val failure = IllegalStateException("selected-read-failure")
        var count = 0
        var version = -1L
        try {
            PlaybackImageResolver<String>(c).resolveSelected("b") { request, _ ->
                count += 1
                version = request.version
                throw failure
            }
            fail("Read failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, count)
        assertEquals(before, c.snapshot())
        assertFalse(c.commit(version))
    }

    @Test fun fatalDecoderErrorRejectsAndPropagatesWithoutFallback() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        val failure = OutOfMemoryError("selected-memory")
        var version = -1L
        try {
            PlaybackImageResolver<String>(c).resolveSelected("c") { request, _ ->
                version = request.version
                throw failure
            }
            fail("Fatal read failure must propagate")
        } catch (actual: OutOfMemoryError) {
            assertSame(failure, actual)
        }
        assertEquals(before, c.snapshot())
        assertFalse(c.commit(version))
    }

    @Test fun alreadyCancelledContextDoesNotIssueSelectionOrDecode() {
        val c = coordinator()
        val before = c.snapshot()
        var called = false
        val job = Job().apply { cancel() }
        try {
            runBlocking(job) {
                PlaybackImageResolver<String>(c).resolveSelected("b") { _, _ -> called = true; "b" }
            }
            fail("Cancelled context must propagate")
        } catch (_: CancellationException) {
            assertFalse(called)
        }
        assertEquals(before, c.snapshot())
    }

    @Test fun unknownTargetNeverInvokesDecoder() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        var calls = 0
        assertNull(PlaybackImageResolver<String>(c).resolveSelected("missing") { _, _ -> calls += 1; "bad" })
        assertEquals(0, calls)
        assertEquals(before, c.snapshot())
    }

    @Test fun sameCurrentNeverInvokesDecoderOrAppendsHistory() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        var calls = 0
        assertNull(PlaybackImageResolver<String>(c).resolveSelected("a") { _, _ -> calls += 1; "bad" })
        assertEquals(0, calls)
        assertEquals(before, c.snapshot())
    }

    @Test fun targetRemovedFromLatestCatalogNeverInvokesDecoder() = runBlocking {
        val c = coordinator()
        c.setItems(c.catalogItems().filterNot { it.id == "b" })
        val before = c.snapshot()
        var calls = 0
        assertNull(PlaybackImageResolver<String>(c).resolveSelected("b") { _, _ -> calls += 1; "bad" })
        assertEquals(0, calls)
        assertEquals(before, c.snapshot())
    }

    @Test fun failingOldDecodeCannotRejectNewerSelectionPreparedDuringDecode() = runBlocking {
        val c = coordinator()
        var newer: PlaybackRequest? = null
        assertNull(PlaybackImageResolver<String>(c).resolveSelected("b") { _, _ ->
            newer = c.requestSelected("c")
            null
        })
        assertTrue(c.commit(newer!!.version))
        assertEquals(listOf("a", "c"), c.snapshot().historyIds)
    }

    @Test fun successfulOldDecodeStillCannotCommitAfterNewerSelection() = runBlocking {
        val c = coordinator()
        var newer: PlaybackRequest? = null
        val old = PlaybackImageResolver<String>(c).resolveSelected("b") { _, _ ->
            newer = c.requestSelected("c")
            "decoded-b"
        }
        assertFalse(c.commit(old!!.request.version))
        assertTrue(c.commit(newer!!.version))
        assertEquals(listOf("a", "c"), c.snapshot().historyIds)
    }

    @Test fun cancellationOfOldDecodeCannotRejectNewerSelection() = runBlocking {
        val c = coordinator()
        var newer: PlaybackRequest? = null
        try {
            PlaybackImageResolver<String>(c).resolveSelected("b") { _, _ ->
                newer = c.requestSelected("c")
                throw CancellationException("older-only")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(c.commit(newer!!.version))
        }
        assertEquals(listOf("a", "c"), c.snapshot().historyIds)
    }

    @Test fun failedSelectionCanBeRetriedWithoutChangingTheQueue() = runBlocking {
        val c = coordinator()
        val before = c.snapshot()
        val resolver = PlaybackImageResolver<String>(c)
        assertNull(resolver.resolveSelected("c") { _, _ -> null })
        assertEquals(before, c.snapshot())
        val retried = resolver.resolveSelected("c") { _, item -> item.id }
        assertTrue(c.commit(retried!!.request.version))
        assertEquals(before.queueIds, c.snapshot().queueIds)
        assertEquals(listOf("a", "c"), c.snapshot().historyIds)
    }
}
