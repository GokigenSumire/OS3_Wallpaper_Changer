package dev.abdus.apps.shimmer.p1

/**
 * Decides whether a scan result may replace the source's committed image set.
 *
 * The gate is intentionally provider neutral. A failed or cancelled walk is never
 * represented as an empty successful result, so callers cannot accidentally reconcile
 * away images that were already committed by an earlier scan.
 */
sealed interface P1ScanCommitDecision {
    data class Commit(val images: List<P1ScannedImage>) : P1ScanCommitDecision

    data class Preserve(val reason: PreserveReason) : P1ScanCommitDecision
}

enum class PreserveReason {
    EMPTY_RESULT,
    CANCELLED,
    READ_FAILURE,
    PERMISSION_FAILURE,
    INCOMPLETE_RESULT,
    METADATA_FAILURE,
}

object P1ScanCommitGate {
    fun decide(outcome: P1ScanOutcome): P1ScanCommitDecision = when (outcome) {
        is P1ScanOutcome.Complete -> if (outcome.images.isEmpty()) {
            // An empty provider view is not proof that the user removed the committed set.
            P1ScanCommitDecision.Preserve(PreserveReason.EMPTY_RESULT)
        } else {
            P1ScanCommitDecision.Commit(outcome.images)
        }
        is P1ScanOutcome.Cancelled -> P1ScanCommitDecision.Preserve(PreserveReason.CANCELLED)
        is P1ScanOutcome.Failed -> P1ScanCommitDecision.Preserve(outcome.reason.toPreserveReason())
    }

    private fun P1ScanFailure.toPreserveReason(): PreserveReason = when (this) {
        P1ScanFailure.READ -> PreserveReason.READ_FAILURE
        P1ScanFailure.PERMISSION -> PreserveReason.PERMISSION_FAILURE
        P1ScanFailure.INCOMPLETE_RESULT -> PreserveReason.INCOMPLETE_RESULT
        P1ScanFailure.METADATA -> PreserveReason.METADATA_FAILURE
    }
}
