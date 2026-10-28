package dev.abdus.apps.shimmer.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class P1PlaybackProjectionHelperTest {
    @Test
    fun noSourcesAndNoLinksIsEmpty() {
        assertTrue(P1PlaybackProjection.resolve(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun existingSourceWithoutLinksIsEmpty() {
        assertTrue(P1PlaybackProjection.resolve(listOf(source()), emptyList()).isEmpty())
    }

    @Test
    fun allFieldsArePreservedAndSourceMetadataUsesCanonicalObjects() {
        val source = source()
        val link = link(sourceId = String(source.id.toCharArray()), sortOrder = 17)
        assertNotSame(source.id, link.sourceId)
        val image = P1PlaybackProjection.resolve(listOf(source), listOf(link)).single()
        assertEquals(P1PlaybackImage(link.id, link.uri, link.displayName, link.relativePath,
            source.id, source.sourceOrder, link.sortOrder, source.kind, source.rootUri), image)
        assertSame(source.id, image.sourceId)
        assertSame(source.kind, image.sourceKind)
        assertSame(source.rootUri, image.sourceRootUri)
    }

    @Test
    fun duplicateImageKeepsItsFirstOrderedLink() {
        val first = source("b", order = 3)
        val second = source("a", order = 4)
        val result = P1PlaybackProjection.resolve(listOf(second, first), listOf(
            link("shared", "b", sortOrder = 8), link("only-a", "a"), link("shared", "a"),
        ))
        assertEquals(listOf("shared", "only-a"), result.map { it.id })
        assertEquals(listOf("b", "a"), result.map { it.sourceId })
        assertEquals(8, result.first().sortOrder)
    }

    @Test
    fun helperDoesNotSortOrSecondGuessTheQueryOrder() {
        val result = P1PlaybackProjection.resolve(listOf(source("a", order = 0), source("b", order = 1)),
            listOf(link("b-first", "b", 9), link("a-second", "a", -1)))
        assertEquals(listOf("b-first", "a-second"), result.map { it.id })
    }

    @Test
    fun disabledSourceCanSupplyMetadataForRuleCatalog() {
        val stopped = source().copy(enabled = false)
        assertEquals("source", P1PlaybackProjection.resolve(listOf(stopped), listOf(link())).single().sourceId)
    }

    @Test
    fun identityDeduplicationIsNotFilenameOrUriDeduplication() {
        val sameUri = "content://test/shared"
        val result = P1PlaybackProjection.resolve(listOf(source()), listOf(
            link("first").copy(uri = sameUri, displayName = "same.png"),
            link("second").copy(uri = sameUri, displayName = "same.png"),
        ))
        assertEquals(listOf("first", "second"), result.map { it.id })
    }

    @Test
    fun missingSourceFailsClosedInsteadOfPublishingPartialOrWrongMetadata() {
        val failure = assertThrows(IllegalStateException::class.java) {
            P1PlaybackProjection.resolve(listOf(source()), listOf(link(), link("missing", "absent")))
        }
        assertEquals("Playback link source is missing", failure.message)
    }

    @Test
    fun missingSourceOnDuplicateImageStillFailsClosed() {
        val failure = assertThrows(IllegalStateException::class.java) {
            P1PlaybackProjection.resolve(listOf(source()), listOf(link(), link(sourceId = "absent")))
        }
        assertEquals("Playback link source is missing", failure.message)
    }

    @Test
    fun duplicateSourceIdentityFailsClosedRatherThanUsingLastMetadata() {
        val failure = assertThrows(IllegalStateException::class.java) {
            P1PlaybackProjection.resolve(listOf(source(), source().copy(rootUri = "content://other")), listOf(link()))
        }
        assertEquals("Duplicate playback source identity", failure.message)
    }

    @Test
    fun mutatedInputsAreReadAgainAndNoCatalogCacheIsUsed() {
        val sources = mutableListOf(source())
        val links = mutableListOf(link())
        val original = P1PlaybackProjection.resolve(sources, links).single()
        sources[0] = sources[0].copy(rootUri = "content://new", sourceOrder = 27)
        links[0] = links[0].copy(displayName = "new.png", sortOrder = 28)
        val newer = P1PlaybackProjection.resolve(sources, links).single()
        assertEquals("content://new", newer.sourceRootUri)
        assertEquals(27L, newer.sourceOrder)
        assertEquals("new.png", newer.displayName)
        assertEquals(28, newer.sortOrder)
        assertEquals("content://root/目录", original.sourceRootUri)
    }

    @Test
    fun literalUnicodeAndWildcardLookingPathsAreNotNormalized() {
        val path = "100%_合集/😀/one.png"
        val input = link().copy(relativePath = path)
        assertSame(path, P1PlaybackProjection.resolve(listOf(source()), listOf(input)).single().relativePath)
    }

    @Test
    fun largeCatalogKeepsEveryItemAndReusesSourceMetadataWithoutATimingAssertion() {
        val source = source()
        val links = (0 until 11890).map { link("image-$it", String(source.id.toCharArray()), it) }
        val images = P1PlaybackProjection.resolve(listOf(source), links)
        assertEquals(11890, images.size)
        for (index in images.indices) {
            assertEquals(links[index].id, images[index].id)
            assertEquals(index, images[index].sortOrder)
            assertSame(source.id, images[index].sourceId)
            assertSame(source.kind, images[index].sourceKind)
            assertSame(source.rootUri, images[index].sourceRootUri)
        }
    }

    private fun source(id: String = "source", order: Long = 4) = P1SourceEntity(
        id = String(id.toCharArray()), rootUri = String("content://root/目录".toCharArray()),
        kind = String("DIRECTORY_TREE".toCharArray()), sourceOrder = order,
    )

    private fun link(id: String = "image", sourceId: String = "source", sortOrder: Int = 0) =
        P1PlaybackLinkRow(id, "content://test/$id", "$id.png", "folder/$id.png", sourceId, sortOrder)
}
