package dev.abdus.apps.shimmer.p1

/** The P1 source contract is independent from a provider or a Room implementation. */
enum class P1SourceKind {
    SINGLE_DOCUMENT,
    MULTIPLE_DOCUMENTS,
    DIRECTORY_TREE,
}

data class P1SourceSpec(
    val sourceId: String,
    val rootUri: String,
    val kind: P1SourceKind,
    val includeSubdirectories: Boolean = true,
    val enabled: Boolean = true,
)

data class P1ScannedImage(
    val stableKey: String,
    val sourceId: String,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long?,
    val width: Int? = null,
    val height: Int? = null,
)

data class P1ScanProgress(
    val visitedNodes: Int,
    val discoveredImages: Int,
    val currentPath: String,
    val phase: P1ScanPhase = P1ScanPhase.ENUMERATION,
    val processedImages: Int = 0,
    val totalImages: Int = 0,
    val elapsedMillis: Long = 0,
)

enum class P1ScanPhase { ENUMERATION, METADATA, COMMIT }

enum class P1ScanFailure {
    READ,
    PERMISSION,
    INCOMPLETE_RESULT,
    METADATA,
}

sealed interface P1ScanOutcome {
    data class Complete(
        val images: List<P1ScannedImage>,
        val visitedNodes: Int,
        /** Number of discovered image nodes whose metadata could not be decoded. */
        val skippedMetadata: Int = 0,
    ) : P1ScanOutcome

    /** A failed/partial walk must never be handed to a destructive reconciliation step. */
    data class Failed(
        val reason: P1ScanFailure,
        val visitedNodes: Int,
        val discoveredImages: Int,
    ) : P1ScanOutcome

    data class Cancelled(
        val visitedNodes: Int,
        val discoveredImages: Int,
    ) : P1ScanOutcome
}

/** Provider-neutral tree node used by the scanner and fake-provider tests. */
interface P1TreeNode {
    /** Stable document ID or canonical file identity, never a display name alone. */
    val id: String

    /** URI used to open/read this node. It may include a tree grant route. */
    val uri: String
        get() = id
    val name: String
    val isDirectory: Boolean
    val mimeType: String?
    val sizeBytes: Long?

    /** May throw when a provider returns an incomplete/unauthorized child listing. */
    fun children(): Iterable<P1TreeNode>

    /**
     * Cancellation-aware child enumeration. Existing provider-neutral fakes only need to
     * implement [children]; Android adapters override this overload to check between rows.
     */
    fun children(checkActive: () -> Unit): Iterable<P1TreeNode> = children()
}

class P1TreeReadException(val failure: P1ScanFailure) : Exception(failure.name)
