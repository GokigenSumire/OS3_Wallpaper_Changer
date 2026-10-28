package dev.abdus.apps.shimmer.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure merge correctness; no host timing is asserted as target-device performance. */
class P1PlaybackSourceMergeTest {
    @Test
    fun emptyAndEmptySourceBatchesStayEmpty() {
        assertTrue(P1PlaybackProjection.resolveSourceLinks(emptyList()).isEmpty())
        assertTrue(P1PlaybackProjection.resolveSourceLinks(listOf(batch("empty"))).isEmpty())
    }

    @Test
    fun uniqueSourceOrdersKeepSqlOrderEvenWhenInputsAreReversed() {
        val result = merge(batch("later", 2, row("later", -100)),
            batch("earlier", 1, row("earlier", 100)))
        assertEquals(listOf("earlier", "later"), result.map { it.id })
    }

    @Test
    fun equalSourceOrderMergesLinkOrderBeforeSourceIdentity() {
        val result = merge(batch("a", 7, row("a1", 1), row("a4", 4)),
            batch("b", 7, row("b0", 0), row("b2", 2), row("b4", 4)))
        assertEquals(listOf("b0", "a1", "b2", "a4", "b4"), result.map { it.id })
    }

    @Test
    fun equalLinkOrderUsesSourceIdentityBeforeSameSourceImageIdentity() {
        val result = merge(batch("b", 0, row("b-a", 1), row("b-z", 1)),
            batch("a", 0, row("a-a", 1), row("a-z", 1)))
        assertEquals(listOf("a-a", "a-z", "b-a", "b-z"), result.map { it.id })
    }

    @Test
    fun sourceIdentityUsesSqliteUtf8BinaryInsteadOfUtf16Order() {
        // U+E000 sorts before U+1F600 in SQLite UTF-8 BINARY, but after it in String.compareTo.
        val result = merge(batch("😀", 0, row("supplementary")),
            batch("\uE000", 0, row("bmp")), batch("a", 0, row("ascii")))
        assertEquals(listOf("ascii", "bmp", "supplementary"), result.map { it.id })
    }

    @Test
    fun sourcePrefixAndEmbeddedNulFollowTheWholeBinaryString() {
        val result = merge(batch("a\u0000b", 0, row("nul")), batch("ab", 0, row("longer")),
            batch("a", 0, row("prefix")))
        assertEquals(listOf("prefix", "nul", "longer"), result.map { it.id })
    }

    @Test
    fun emptyBatchesDoNotDisturbSameOrderMerge() {
        val result = merge(batch("z", 0), batch("a", 0, row("a", 2)), batch("b", 0),
            batch("c", 0, row("c", 1)))
        assertEquals(listOf("c", "a"), result.map { it.id })
    }

    @Test
    fun sharedImageKeepsFirstGlobalOrderedLinkNotFirstSourceInput() {
        val result = merge(batch("a", 0, row("shared", 9)),
            batch("b", 0, row("shared", 1), row("only-b", 2)))
        assertEquals(listOf("shared", "only-b"), result.map { it.id })
        assertEquals("b", result.first().sourceId)
        assertEquals(1, result.first().sortOrder)
    }

    @Test
    fun deduplicationUsesRecordIdNotUriOrFileName() {
        val result = merge(batch("a", 0, row("one").copy(uri = "content://same", displayName = "same.png")),
            batch("b", 1, row("two").copy(uri = "content://same", displayName = "same.png")))
        assertEquals(listOf("one", "two"), result.map { it.id })
    }

    @Test
    fun duplicateSourceIdentityFailsClosedBeforeAnyResultIncludingEmptyBatch() {
        val failure = assertThrows(IllegalStateException::class.java) {
            merge(batch("a", 0, row("one")), batch("a", 1))
        }
        assertEquals("Duplicate playback source identity", failure.message)
    }

    @Test
    fun canonicalSourceReferencesAndAllLinkFieldsArePreserved() {
        val source = source("a", 9).copy(enabled = false)
        val link = row("image", 17).copy(relativePath = "100%_合集/😀/image.png")
        val image = P1PlaybackProjection.resolveSourceLinks(listOf(P1PlaybackSourceLinks(source, listOf(link)))).single()
        assertEquals(P1PlaybackImage(link.id, link.uri, link.displayName, link.relativePath,
            source.id, 9, 17, source.kind, source.rootUri), image)
        assertSame(source.id, image.sourceId)
        assertSame(source.kind, image.sourceKind)
        assertSame(source.rootUri, image.sourceRootUri)
        assertSame(link.relativePath, image.relativePath)
    }

    @Test
    fun sourceAndLinkIntegerExtremesDoNotOverflowComparators() {
        val result = merge(batch("last", Long.MAX_VALUE, row("last", Int.MIN_VALUE)),
            batch("first", Long.MIN_VALUE, row("first", Int.MAX_VALUE)),
            batch("b", 0, row("min", Int.MIN_VALUE), row("max", Int.MAX_VALUE)),
            batch("a", 0, row("zero", 0)))
        assertEquals(listOf("first", "min", "zero", "max", "last"), result.map { it.id })
    }

    @Test
    fun mutableBatchChangesAreReadOnNextCallAndPreviousResultIsNotRewritten() {
        val links = mutableListOf(row("one"))
        val batches = mutableListOf(P1PlaybackSourceLinks(source("a", 0), links))
        val before = P1PlaybackProjection.resolveSourceLinks(batches).single()
        links[0] = links[0].copy(displayName = "changed.png", sortOrder = 9L)
        batches[0] = batches[0].copy(source = source("a", 7).copy(rootUri = "content://new"))
        val after = P1PlaybackProjection.resolveSourceLinks(batches).single()
        assertEquals("one.png", before.displayName)
        assertEquals("changed.png", after.displayName)
        assertEquals(7L, after.sourceOrder)
        assertEquals("content://new", after.sourceRootUri)
        assertEquals(9, after.sortOrder)
    }

    @Test
    fun largeSingleSourcePreservesAll11890OrderedRowsWithoutTimingAssertion() {
        val source = source("source", 0)
        val rows = (0 until 11890).map { row("image-$it", it) }
        val images = P1PlaybackProjection.resolveSourceLinks(listOf(P1PlaybackSourceLinks(source, rows)))
        assertEquals(11890, images.size)
        for (index in images.indices) {
            assertEquals(rows[index].id, images[index].id)
            assertSame(source.id, images[index].sourceId)
            assertSame(source.rootUri, images[index].sourceRootUri)
        }
    }

    @Test
    fun largeEqualOrderSourcesMergeEveryLinkThenKeepFirstSharedId() {
        val count = 11890
        val a = batch("a", 5, *(0 until count step 2).map { row("image-$it", it) }.toTypedArray())
        val b = batch("b", 5, *(1 until count step 2).map { row("image-$it", it) }.toTypedArray())
        val c = batch("c", 6, row("image-1", -100), row("new-tail", -99))
        val images = merge(c, b, a)
        assertEquals(count + 1, images.size)
        for (index in 0 until count) assertEquals("image-$index", images[index].id)
        assertEquals("new-tail", images.last().id)
        assertEquals("b", images[1].sourceId)
    }

    private fun merge(vararg batches: P1PlaybackSourceLinks) = P1PlaybackProjection.resolveSourceLinks(batches.toList())

    private fun batch(id: String, order: Long = 0, vararg rows: P1PlaybackSourceLinkRow) =
        P1PlaybackSourceLinks(source(id, order), rows.toList())

    private fun source(id: String, order: Long) = P1SourceEntity(
        String(id.toCharArray()), String("content://roots/$id".toCharArray()),
        String("DIRECTORY_TREE".toCharArray()), sourceOrder = order,
    )

    private fun row(id: String, order: Int = 0) = P1PlaybackSourceLinkRow(
        id, "content://images/$id", "$id.png", "目录/😀/$id.png", order.toLong(),
    )
}
