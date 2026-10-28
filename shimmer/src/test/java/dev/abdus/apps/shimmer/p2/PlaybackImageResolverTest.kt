package dev.abdus.apps.shimmer.p2

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackImageResolverTest {
    private fun coordinator(): PlaybackCoordinator = PlaybackCoordinator().apply {
        setItems(listOf("a", "b", "c", "d").map { PlaybackItem(it, it) })
    }

    @Test fun forwardSkipsConsecutiveUnreadableItemsWithoutCommittingThem() = runBlocking {
        val c = coordinator()
        c.requestNext().also { c.commit(it.version) }
        val calls = mutableListOf<String>()
        val loaded = PlaybackImageResolver<String>(c).resolve(PlaybackDirection.NEXT) {
            calls += it.id
            it.id.takeUnless { id -> id == "b" || id == "c" }
        }
        assertEquals(listOf("b", "c", "d"), calls)
        assertEquals(listOf("a"), c.snapshot().historyIds)
        assertEquals("d", loaded?.image)
        assertTrue(c.commit(loaded!!.request.version))
        assertEquals(listOf("a", "d"), c.snapshot().historyIds)
    }

    @Test fun backwardSkipsUnreadableHistoryAndForwardUsesRemainingHistory() = runBlocking {
        val c = coordinator()
        repeat(4) { c.requestNext().also { c.commit(it.version) } }
        val resolver = PlaybackImageResolver<String>(c)
        val previous = resolver.resolve(PlaybackDirection.PREVIOUS) { it.id.takeUnless { id -> id in listOf("b", "c") } }
        assertEquals("a", previous?.image)
        c.commit(previous!!.request.version)
        val next = resolver.resolve(PlaybackDirection.NEXT) { it.id.takeUnless { id -> id in listOf("b", "c") } }
        assertEquals("d", next?.image)
        c.commit(next!!.request.version)
        assertEquals(listOf("a", "b", "c", "d"), c.snapshot().historyIds)
    }

    @Test fun everyUnreadableItemIsTriedAtMostOnceAndCommittedStateIsRetained() = runBlocking {
        val c = coordinator()
        c.requestNext().also { c.commit(it.version) }
        val before = c.snapshot()
        val calls = mutableListOf<String>()
        val resolver = PlaybackImageResolver<String>(c)
        assertNull(resolver.resolve(PlaybackDirection.NEXT) { calls += it.id; null })
        assertEquals(listOf("b", "c", "d", "a"), calls)
        assertEquals(before, c.snapshot())
        val recovered = resolver.resolve(PlaybackDirection.NEXT) { it.id }
        assertEquals("b", recovered?.image)
    }

    @Test fun randomRoundBoundaryFindsReadableImageAfterOldTailAndNewRoundFailures() = runBlocking {
        // The sole readable image was earlier in the saved round. A new shuffled round may
        // put it last, after more failed positions than one round's length across the boundary.
        for (seed in 0L..63L) {
            val c = PlaybackCoordinator(seed).apply {
                setItems(listOf("a", "b", "c", "d").map { PlaybackItem(it, it) })
                restore(PlaybackSnapshot(
                    order = PlaybackOrder.RANDOM,
                    queueIds = listOf("a", "b", "c", "d"),
                    queueIndex = 1,
                    historyIds = listOf("a", "b"),
                    historyCursor = 1,
                    currentId = "b",
                ))
            }
            val calls = mutableListOf<String>()
            val loaded = PlaybackImageResolver<String>(c).resolve(PlaybackDirection.NEXT) {
                calls += it.id
                it.id.takeIf { id -> id == "a" }
            }
            assertEquals("Readable image must be reached for seed $seed; tried $calls", "a", loaded?.image)
            assertEquals("Each failed image is decoded only once for seed $seed", calls.size, calls.toSet().size)
            assertEquals(listOf("a", "b"), c.snapshot().historyIds)
            assertTrue(c.commit(loaded!!.request.version))
            assertEquals(listOf("a", "b", "a"), c.snapshot().historyIds)
            assertEquals(1, c.snapshot().randomRound)
        }
    }

    @Test fun retainingCurrentImageAcrossRandomBoundaryStillCommitsTheNewRound() = runBlocking {
        for (seed in 0L..63L) {
            val c = PlaybackCoordinator(seed).apply {
                setItems(listOf("a", "b", "c", "d").map { PlaybackItem(it, it) })
                restore(PlaybackSnapshot(
                    order = PlaybackOrder.RANDOM,
                    queueIds = listOf("a", "b", "c", "d"),
                    queueIndex = 1,
                    historyIds = listOf("a", "b"),
                    historyCursor = 1,
                    currentId = "b",
                ))
            }
            val resolver = PlaybackImageResolver<String>(c)
            val attempts = mutableListOf<String>()
            val load: suspend (PlaybackItem) -> String? = { item ->
                attempts += item.id
                item.id.takeIf { it == "a" || it == "b" }
            }
            val first = requireNotNull(resolver.resolve(PlaybackDirection.NEXT, load))
            assertEquals(attempts.size, attempts.toSet().size)
            assertTrue(c.commit(first.request.version))
            assertEquals("Crossing the round must be retained for seed $seed", 1, c.snapshot().randomRound)
            if (first.image == "b") {
                assertEquals("Keeping the current image must not add history", listOf("a", "b"), c.snapshot().historyIds)
                attempts.clear()
                val following = requireNotNull(resolver.resolve(PlaybackDirection.NEXT, load))
                assertEquals("The next gesture must leave the retained image for seed $seed", "a", following.image)
                assertEquals(attempts.size, attempts.toSet().size)
                assertTrue(c.commit(following.request.version))
            }
            assertEquals(listOf("a", "b", "a"), c.snapshot().historyIds)
        }
    }
}
