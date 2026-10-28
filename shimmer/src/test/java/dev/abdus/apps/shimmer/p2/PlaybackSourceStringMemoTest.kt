package dev.abdus.apps.shimmer.p2

import org.junit.Assert.*
import org.junit.Test

/** 成功String.equals引用对memo的等价验证，不以宿主大目录断言手机时延。 */
class PlaybackSourceStringMemoTest {
    private fun text(value: String) = String(value.toCharArray())
    private fun items(count: Int, sourceId: String, kind: String, root: String) = (1..count).map {
        PlaybackItem("content://item/$it", "$it.jpg", "dir/$it.jpg", sourceId, 1, kind, root)
    }
    private fun exact(c: PlaybackCoordinator, input: List<PlaybackItem>) =
        PlaybackCoordinator::class.java.getDeclaredMethod("sameCatalog", List::class.java)
            .apply { isAccessible = true }.invoke(c, input) as Boolean

    @Test fun largeCanonicalSourceStringsFromDifferentReadsStillCompareExactly() {
        val root = "content://storage.documents/tree/" + "long%2Fpath".repeat(500)
        val old = items(11890, text("source"), text("DIRECTORY_TREE"), text(root))
        val fresh = items(11890, text("source"), text("DIRECTORY_TREE"), text(root))
        assertNotSame(old.first().sourceRootUri, fresh.first().sourceRootUri)
        val c = PlaybackCoordinator().apply { setItems(old) }
        assertTrue(exact(c, fresh))
        c.setItemsAndRestore(fresh, PlaybackSnapshot())
        assertEquals(old, c.catalogItems())
    }

    @Test fun repeatedSourceIdWithContradictoryLastRootCannotHitEarlierSuccessfulMemo() {
        val old = items(4, text("source"), text("DIRECTORY_TREE"), text("content://old"))
        val fresh = items(4, text("source"), text("DIRECTORY_TREE"), text("content://old")).toMutableList()
        fresh[3] = fresh[3].copy(sourceRootUri = "content://changed")
        val c = PlaybackCoordinator().apply { setItems(old) }
        assertFalse(exact(c, fresh))
        c.setItems(fresh)
        assertEquals("content://changed", c.catalogItems().last().sourceRootUri)
    }

    @Test fun repeatedSourceIdWithContradictoryKindIsNotTreatedAsCanonicalByName() {
        val old = items(4, "source", "DIRECTORY_TREE", "content://root")
        val fresh = old.map { it.copy(sourceId = text("source")) }.toMutableList()
        fresh[2] = fresh[2].copy(sourceKind = "MULTIPLE_DOCUMENTS")
        val c = PlaybackCoordinator().apply { setItems(old) }
        assertFalse(exact(c, fresh))
        c.setItems(fresh)
        assertEquals("MULTIPLE_DOCUMENTS", c.catalogItems()[2].sourceKind)
    }

    @Test fun distinctSourceIdOnSameKindAndRootStillChangesTheCatalog() {
        val old = items(3, "source", "DIRECTORY_TREE", "content://root")
        val fresh = old.toMutableList().apply { this[2] = this[2].copy(sourceId = "different") }
        val c = PlaybackCoordinator().apply { setItems(old) }
        assertFalse(exact(c, fresh))
        c.setItems(fresh)
        assertEquals("different", c.catalogItems()[2].sourceId)
    }

    @Test fun sameEqualStringContentsWithDistinctReferencesOnEveryRowRemainExact() {
        val old = items(20, text("source"), text("DIRECTORY_TREE"), text("content://root"))
        val fresh = old.map { it.copy(sourceId = text(it.sourceId), sourceKind = text(it.sourceKind), sourceRootUri = text(it.sourceRootUri)) }
        val c = PlaybackCoordinator().apply { setItems(old) }
        assertTrue(exact(c, fresh))
    }

    @Test fun sourceMemoDoesNotIgnoreIdentityDisplayPathOrSourceOrder() {
        val old = items(4, "source", "DIRECTORY_TREE", "content://root")
        val edits = listOf(
            old[3].copy(id = "different"), old[3].copy(displayName = "different"),
            old[3].copy(relativePath = "different"), old[3].copy(sourceOrder = 2),
        )
        for (edit in edits) {
            val c = PlaybackCoordinator().apply { setItems(old) }
            assertFalse(exact(c, old.take(3) + edit))
        }
    }

    @Test fun memoIsLocalToComparisonAndMutableListReplacementMustBeRechecked() {
        val input = items(3, "source", "DIRECTORY_TREE", "content://root").toMutableList()
        val c = PlaybackCoordinator().apply { setItems(input) }
        assertTrue(exact(c, input))
        input[2] = input[2].copy(sourceRootUri = "content://changed")
        assertFalse(exact(c, input))
        c.setItems(input)
        assertTrue(exact(c, input))
    }

    @Test fun insertionOrderAndFirstWinsDuplicatesAreNotReplacedByStringMemo() {
        val old = items(3, "source", "DIRECTORY_TREE", "content://root")
        val c = PlaybackCoordinator().apply { setItems(old) }
        assertFalse(exact(c, old.reversed()))
        val duplicate = old + old[0].copy(sourceRootUri = "content://wrong", sourceKind = "wrong")
        assertFalse(exact(c, duplicate))
        c.setItems(duplicate)
        assertEquals(old, c.catalogItems())
    }

    @Test fun invalidTailIdentityIsNotHiddenBySuccessfulCanonicalSourceComparisons() {
        val old = items(3, "source", "DIRECTORY_TREE", "content://root")
        val c = PlaybackCoordinator().apply { setItems(old) }
        val input = old.toMutableList().apply { this[2] = this[2].copy(id = " ") }
        try { c.setItems(input); fail("空白身份应拒绝") } catch (_: IllegalArgumentException) { }
        assertEquals(old, c.catalogItems())
    }

    @Test fun alternatingSourceCanonicalReferencesAreComparedPerPairNotPerSourceId() {
        val old = (1..10).map { index -> PlaybackItem("$index", "$index", sourceId = if (index % 2 == 0) "A" else "B", sourceKind = "DIRECTORY_TREE", sourceRootUri = if (index % 2 == 0) "content://A" else "content://B") }
        val newA = text("A")
        val newB = text("B")
        val rootA = text("content://A")
        val rootB = text("content://B")
        val fresh = old.map { it.copy(sourceId = if (it.sourceId == "A") newA else newB, sourceRootUri = if (it.sourceId == "A") rootA else rootB, sourceKind = text(it.sourceKind)) }
        val c = PlaybackCoordinator().apply { setItems(old) }
        assertTrue(exact(c, fresh))
        assertFalse(exact(c, fresh.dropLast(1) + fresh.last().copy(sourceRootUri = rootB)))
    }
}
