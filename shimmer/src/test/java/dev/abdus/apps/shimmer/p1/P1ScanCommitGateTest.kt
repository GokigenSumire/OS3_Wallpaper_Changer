package dev.abdus.apps.shimmer.p1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P1ScanCommitGateTest {
    private val image = P1ScannedImage(
        stableKey = "source-1:one",
        sourceId = "source-1",
        uri = "content://one",
        displayName = "one.jpg",
        relativePath = "one.jpg",
        sizeBytes = 10,
    )

    @Test
    fun completeScanCommitsImagesButPreservesOnEmptyResult() {
        assertEquals(
            P1ScanCommitDecision.Commit(listOf(image)),
            P1ScanCommitGate.decide(P1ScanOutcome.Complete(listOf(image), visitedNodes = 1)),
        )
        assertEquals(
            P1ScanCommitDecision.Preserve(PreserveReason.EMPTY_RESULT),
            P1ScanCommitGate.decide(P1ScanOutcome.Complete(emptyList(), visitedNodes = 1)),
        )
    }

    @Test
    fun cancellationPreservesPreviouslyCommittedConfiguration() {
        val decision = P1ScanCommitGate.decide(P1ScanOutcome.Cancelled(visitedNodes = 4, discoveredImages = 2))

        assertTrue(decision is P1ScanCommitDecision.Preserve)
        assertEquals(PreserveReason.CANCELLED, (decision as P1ScanCommitDecision.Preserve).reason)
    }

    @Test
    fun everyFailureClassPreservesPreviouslyCommittedConfiguration() {
        P1ScanFailure.entries.forEach { failure ->
            val decision = P1ScanCommitGate.decide(
                P1ScanOutcome.Failed(failure, visitedNodes = 4, discoveredImages = 2),
            )

            assertTrue("$failure must preserve", decision is P1ScanCommitDecision.Preserve)
            assertEquals(failure.toExpectedReason(), (decision as P1ScanCommitDecision.Preserve).reason)
        }
    }

    private fun P1ScanFailure.toExpectedReason(): PreserveReason = when (this) {
        P1ScanFailure.READ -> PreserveReason.READ_FAILURE
        P1ScanFailure.PERMISSION -> PreserveReason.PERMISSION_FAILURE
        P1ScanFailure.INCOMPLETE_RESULT -> PreserveReason.INCOMPLETE_RESULT
        P1ScanFailure.METADATA -> PreserveReason.METADATA_FAILURE
    }
}
