package dev.abdus.apps.shimmer.database

import org.junit.Assert.*
import org.junit.Test

class P1ScanCommitPlanTest {
    private fun image(id: String, uri: String = "file:///$id") =
        P1ImageRecordEntity(id, uri, id, id, createdAt = "old")
    private fun plan(rows: List<P1ImageRecordEntity>, old: List<P1ImageRecordEntity> = emptyList(),
        links: List<P1SourceImageLinkEntity> = emptyList()) =
        P1ScanCommitPlan.prepare("s", rows, old, links, "now")

    @Test fun newImagesAppendInEnumerationOrder() {
        val result = plan(listOf(image("b"), image("a")))
        assertEquals(listOf("b", "a"), result.images.map { it.id })
        assertEquals(listOf(0, 1), result.newLinks.map { it.sortOrder })
        assertEquals(2, result.acceptedCount)
    }
    @Test fun existingManualLinkOrderIsNotWritten() {
        val result = plan(listOf(image("b"), image("a"), image("c")),
            listOf(image("a"), image("b")), listOf(P1SourceImageLinkEntity("s", "a", 10), P1SourceImageLinkEntity("s", "b", 2)))
        assertEquals(listOf(P1SourceImageLinkEntity("s", "c", 11)), result.newLinks)
    }
    @Test fun uriIdentityReusesLegacyKeyAndCreatedAt() {
        val old = image("legacy", "file:///shared").copy(createdAt = "first", width = 9, height = 8, sizeBytes = 7)
        val row = plan(listOf(image("canonical", old.uri)), listOf(old)).images.single()
        assertEquals("legacy", row.id)
        assertEquals("first", row.createdAt)
        assertEquals(9, row.width)
        assertEquals(8, row.height)
        assertEquals(7L, row.sizeBytes)
        assertEquals("now", row.updatedAt)
    }
    @Test fun repeatedUriWithinNewBatchHasOneRecordAndLink() {
        val result = plan(listOf(image("a", "file:///same").copy(width = 5), image("b", "file:///same")))
        assertEquals(1, result.acceptedCount)
        assertEquals("a", result.images.single().id)
        assertEquals(5, result.images.single().width)
        assertEquals(1, result.newLinks.size)
    }
    @Test fun overlappingSourceAddsLinkWithoutDuplicatingImage() {
        val result = plan(listOf(image("a")), listOf(image("a")))
        assertEquals(listOf(P1SourceImageLinkEntity("s", "a", 0)), result.newLinks)
    }
    @Test fun uriRowWinsOverDifferentExistingId() {
        val result = plan(listOf(image("a", "file:///b")), listOf(image("a"), image("b")))
        assertEquals("b", result.images.single().id)
    }
    @Test(expected = IllegalArgumentException::class) fun blankIdentityRejectedBeforeAnyWrites() {
        plan(listOf(image("valid"), image("")))
    }
    @Test(expected = IllegalArgumentException::class) fun uncommittedMetadataRejected() {
        plan(listOf(image("a").copy(metadataState = "FAILED")))
    }
    @Test(expected = IllegalArgumentException::class) fun sameNewIdCannotSilentlyReplaceAnotherUri() {
        plan(listOf(image("a", "file:///x"), image("a", "file:///y")))
    }
    @Test fun tenThousandRowsRemainLinearAndUnique() {
        val result = plan((1..11890).map { image("$it") })
        assertEquals(11890, result.acceptedCount)
        assertEquals(11890, result.newLinks.size)
        assertEquals(11889, result.newLinks.last().sortOrder)
    }
    @Test fun emptyPlanDoesNotRemoveExistingRowsOrLinks() {
        val result = plan(emptyList(), listOf(image("a")), listOf(P1SourceImageLinkEntity("s", "a", 0)))
        assertTrue(result.images.isEmpty())
        assertTrue(result.newLinks.isEmpty())
        assertEquals(0, result.acceptedCount)
    }
}
