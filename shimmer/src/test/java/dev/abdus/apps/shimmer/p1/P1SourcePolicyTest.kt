package dev.abdus.apps.shimmer.p1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P1SourcePolicyTest {
    private val image = P1ScannedImage(
        stableKey = "image:content://provider/document/1",
        sourceId = "source:directory_tree:one",
        uri = "content://provider/document/1",
        displayName = "one.jpg",
        relativePath = "one.jpg",
        sizeBytes = 12,
    )

    @Test
    fun sourceIdentityIsStableForTheSameUriAndKind() {
        val uri = "content://provider/tree/pictures"
        assertEquals(
            P1SourceRepository.stableSourceId(uri, P1SourceKind.DIRECTORY_TREE),
            P1SourceRepository.stableSourceId(uri, P1SourceKind.DIRECTORY_TREE),
        )
        assertNotEquals(
            P1SourceRepository.stableSourceId(uri, P1SourceKind.DIRECTORY_TREE),
            P1SourceRepository.stableSourceId(uri, P1SourceKind.SINGLE_DOCUMENT),
        )
    }

    @Test
    fun overlappingSourcesUseOneGlobalImageIdentity() {
        val sameUri = image.copy(sourceId = "source:directory_tree:two")
        assertEquals(image.stableKey, sameUri.stableKey)
        assertEquals(image.uri, sameUri.uri)
    }

    @Test
    fun emptyAndFailureOutcomesPreserveCommittedConfiguration() {
        val outcomes = listOf(
            P1ScanOutcome.Complete(emptyList(), visitedNodes = 1),
            P1ScanOutcome.Cancelled(visitedNodes = 1, discoveredImages = 1),
            P1ScanOutcome.Failed(P1ScanFailure.READ, visitedNodes = 1, discoveredImages = 1),
        )
        outcomes.forEach { outcome ->
            assertTrue(P1ScanCommitGate.decide(outcome) is P1ScanCommitDecision.Preserve)
        }
    }
}
