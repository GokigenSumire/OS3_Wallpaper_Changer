package dev.abdus.apps.shimmer.database

import org.junit.Assert.*
import org.junit.Test

class P1PlaybackProjectionCapacityTest {
    @Test fun emptyCatalogHasZeroHint() { assertEquals(0, P1PlaybackProjection.projectionCapacity(emptyList())) }
    @Test fun realRowsAreSummedWithoutChangingOrdering() {
        assertEquals(11890, P1PlaybackProjection.projectionCapacity(listOf(10000, 1000, 890)))
    }
    @Test fun zeroSourcesDoNotCreateEntries() { assertEquals(0, P1PlaybackProjection.projectionCapacity(listOf(0, 0))) }
    @Test fun hintIsBoundedWithoutIntegerOverflow() {
        assertEquals(1_000_000, P1PlaybackProjection.projectionCapacity(listOf(Int.MAX_VALUE, Int.MAX_VALUE)))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidNegativeRowsDoNotBecomeSuccess() {
        P1PlaybackProjection.projectionCapacity(listOf(-1))
    }
    @Test(expected = IllegalArgumentException::class) fun boundedHintStillValidatesAllFollowingSources() {
        P1PlaybackProjection.projectionCapacity(listOf(Int.MAX_VALUE, -1))
    }
    @Test fun duplicateImagesStillKeepExactFirstSourceAndMetadata() {
        val one = P1PlaybackSourceLinkRow("same", "file:///one", "1.png", "1.png", 0)
        val two = P1PlaybackSourceLinkRow("same", "file:///two", "changed.png", "changed.png", 0)
        val a = P1SourceEntity("a", "root-a", "DIRECTORY_TREE", sourceOrder = 1)
        val b = P1SourceEntity("b", "root-b", "DIRECTORY_TREE", sourceOrder = 2)
        val result = P1PlaybackProjection.resolveSourceLinks(listOf(
            P1PlaybackSourceLinks(b, listOf(two)), P1PlaybackSourceLinks(a, listOf(one)),
        ))
        assertEquals(1, result.size)
        assertEquals("a", result.single().sourceId)
        assertEquals("file:///one", result.single().uri)
        assertEquals("1.png", result.single().displayName)
    }
}
