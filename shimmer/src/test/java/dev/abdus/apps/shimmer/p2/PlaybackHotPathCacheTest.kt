package dev.abdus.apps.shimmer.p2

import org.junit.Assert.*
import org.junit.Test

class PlaybackHotPathCacheTest {
    private fun items(vararg ids: String) = ids.map { PlaybackItem(it, "$it.jpg", "$it.jpg", "source") }
    private fun play(subject: PlaybackCoordinator): String? = subject.requestNext().let {
        assertTrue(subject.commit(it.version)); it.item?.id
    }
    private fun cache(subject: PlaybackCoordinator, name: String): Any? =
        PlaybackCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(subject)

    @Test fun unchangedLargeCatalogReusesDerivedQueuesAndPositionIndexInAllOrders() {
        val catalog = (1..11_890).map { PlaybackItem("id-$it", "$it.jpg", "dir/$it.jpg", "source") }
        for (order in PlaybackOrder.entries) {
            val subject = PlaybackCoordinator(7L)
            subject.setItems(catalog)
            subject.setOrder(order)
            play(subject)
            // 初次准备已冻结派生队列；下次restore仍从完整持久化形态重建但命中memo。
            subject.restore(subject.snapshot().forPersistence())
            val queue = subject.snapshot().queueIds
            val position = cache(subject, "queuePositionCache")
            val derived = if (order == PlaybackOrder.MANUAL) cache(subject, "manualQueueCache")
                else if (order == PlaybackOrder.RANDOM) cache(subject, "filteredRandomQueueCache") else null
            repeat(12) {
                subject.setItems(catalog.toList())
                subject.restore(subject.snapshot().forPersistence())
                assertSame("$order 队列不应重复全量重建", queue, subject.snapshot().queueIds)
                assertSame("$order 位置表不应每步重建", position, cache(subject, "queuePositionCache"))
                if (derived != null) assertSame(derived, cache(subject,
                    if (order == PlaybackOrder.MANUAL) "manualQueueCache" else "filteredRandomQueueCache"))
                play(subject)
            }
            assertEquals(11_890, subject.snapshot().queueIds.size)
            assertEquals(13, subject.snapshot().historyIds.size)
        }
    }

    @Test fun catalogMetadataAndInsertionOrderChangesInvalidateMemoExactly() {
        val subject = PlaybackCoordinator()
        val initial = items("a", "b", "c")
        subject.setItems(initial); subject.setOrder(PlaybackOrder.MANUAL); play(subject)
        val manual = cache(subject, "manualQueueCache")
        val positions = cache(subject, "queuePositionCache")
        subject.setItems(initial.map { if (it.id == "b") it.copy(sourceKind = "changed") else it })
        assertNotSame(manual, cache(subject, "manualQueueCache"))
        assertNotSame(positions, cache(subject, "queuePositionCache"))
        assertEquals("changed", subject.catalogItems().first { it.id == "b" }.sourceKind)
        val previous = cache(subject, "manualQueueCache")
        subject.setItems(initial.reversed())
        assertNotSame(previous, cache(subject, "manualQueueCache"))
        assertEquals(listOf("c", "b", "a"), subject.catalogItems().map { it.id })
    }

    @Test fun manualMemoPreservesHiddenSlotsAndRestoredOrderChanges() {
        val subject = PlaybackCoordinator()
        subject.setItems(items("a", "b", "c"))
        subject.restore(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf("c", "hidden", "a", "b")))
        val first = subject.snapshot().queueIds
        subject.setItems(items("a", "c"))
        assertEquals(listOf("c", "hidden", "a", "b"), subject.snapshot().queueIds)
        subject.setItems(items("a", "b", "c", "new"))
        assertEquals(listOf("c", "hidden", "a", "b", "new"), subject.snapshot().queueIds)
        subject.restore(subject.snapshot().copy(queueIds = listOf("b", "hidden", "c", "a", "new")))
        assertEquals(listOf("b", "c", "a", "new"), subject.orderedItems().map { it.id })
        assertNotSame(first, subject.snapshot().queueIds)
    }

    @Test fun mutableQueueInputCannotChangeTheMemoOrCommittedQueue() {
        val input = mutableListOf("c", "hidden", "a", "b")
        val subject = PlaybackCoordinator()
        subject.setItems(items("a", "b", "c"))
        subject.restore(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = input))
        input[0] = "b"
        assertEquals(listOf("c", "hidden", "a", "b"), subject.snapshot().queueIds)
        try { (subject.snapshot().queueIds as MutableList<*>).clear(); fail("派生队列必须冻结") }
        catch (_: UnsupportedOperationException) { }
        subject.restore(subject.snapshot().copy(queueIds = input))
        assertEquals(listOf("b", "hidden", "a", "c"), subject.snapshot().queueIds)
    }

    @Test fun mutableCatalogListSameReferenceStillChecksActualItems() {
        val input = items("a", "b").toMutableList()
        val subject = PlaybackCoordinator()
        subject.setItems(input)
        input[0] = input[0].copy(displayName = "new-name.jpg")
        subject.setItems(input)
        assertEquals("new-name.jpg", subject.catalogItems().first().displayName)
    }

    @Test fun randomCatalogHashCollisionDoesNotReuseRemovedIdentity() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val subject = PlaybackCoordinator(5L)
        subject.setItems(items("Aa", "z")); subject.setOrder(PlaybackOrder.RANDOM)
        val old = subject.snapshot()
        subject.setItems(items("BB", "z"))
        assertEquals(old.queueFingerprint, subject.snapshot().queueFingerprint)
        assertEquals(setOf("BB", "z"), subject.snapshot().queueIds.toSet())
        assertFalse(subject.snapshot().queueIds.contains("Aa"))
        subject.restore(old.forPersistence())
        assertEquals(setOf("BB", "z"), subject.snapshot().queueIds.toSet())
    }

    @Test fun manualListHashCollisionDoesNotCountAsExactQueueMatch() {
        assertEquals(listOf("Aa", "z").hashCode(), listOf("BB", "z").hashCode())
        val subject = PlaybackCoordinator()
        subject.setItems(items("Aa", "BB", "z"))
        subject.restore(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf("Aa", "z")))
        subject.restore(subject.snapshot().copy(queueIds = listOf("BB", "z")))
        assertEquals(listOf("BB", "z", "Aa"), subject.snapshot().queueIds)
    }

    @Test fun catalogChangeRejectsPendingRequestAndEmptyAndSingleStaySafe() {
        val subject = PlaybackCoordinator()
        subject.setItems(items("a", "b")); subject.setOrder(PlaybackOrder.MANUAL)
        val pending = subject.requestNext()
        subject.setItems(emptyList())
        assertFalse(subject.commit(pending.version))
        assertNull(subject.requestNext().item)
        subject.setItems(items("a"))
        assertEquals("a", play(subject)); assertEquals("a", play(subject))
        assertEquals(1, subject.snapshot().historyIds.size)
    }

    @Test fun cachedPositionLookupKeepsHistoryTransformsAndSingleStepNavigation() {
        val subject = PlaybackCoordinator()
        subject.setItems(items("a", "b", "c")); subject.setOrder(PlaybackOrder.MANUAL)
        val transform = dev.abdus.apps.shimmer.p3.ImageTransform(userScale = 2f)
        repeat(3) { assertTrue(subject.commit(subject.requestNext().version, transform)) }
        subject.restore(subject.snapshot().forPersistence())
        val index = cache(subject, "queuePositionCache")
        val previous = subject.requestPrevious()
        assertEquals("b", previous.item?.id); assertEquals(transform, previous.entryTransform)
        assertTrue(subject.commit(previous.version))
        assertEquals("c", play(subject))
        assertEquals(3, subject.snapshot().historyIds.size)
        assertSame(index, cache(subject, "queuePositionCache"))
    }
}
