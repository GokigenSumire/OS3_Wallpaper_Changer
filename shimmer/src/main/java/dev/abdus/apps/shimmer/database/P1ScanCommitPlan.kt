package dev.abdus.apps.shimmer.database

/** Prepare an add/update-only transaction without per-image database queries or suspensions. */
internal data class P1ScanCommitPlan(
    val images: List<P1ImageRecordEntity>,
    val newLinks: List<P1SourceImageLinkEntity>,
    val acceptedCount: Int,
) {
    companion object {
        const val QUERY_CHUNK_SIZE = 400 // Below older SQLite's 999 bound-parameter limit.

        fun prepare(
            sourceId: String,
            incoming: List<P1ImageRecordEntity>,
            existingImages: List<P1ImageRecordEntity>,
            existingLinks: List<P1SourceImageLinkEntity>,
            now: String,
        ): P1ScanCommitPlan {
            val byId = existingImages.associateBy { it.id }.toMutableMap()
            val initiallyExistingIds = byId.keys.toSet()
            val byUri = existingImages.associateBy { it.uri }.toMutableMap()
            val links = existingLinks.associateBy { it.imageId }.toMutableMap()
            val pendingImages = linkedMapOf<String, P1ImageRecordEntity>()
            val pendingLinks = mutableListOf<P1SourceImageLinkEntity>()
            var nextOrder = (existingLinks.maxOfOrNull { it.sortOrder } ?: -1) + 1
            incoming.forEach { image ->
                require(image.id.isNotBlank() && image.uri.isNotBlank()) { "Image identity is required" }
                require(image.metadataState == "COMMITTED") { "Only complete image metadata may commit" }
                val idRow = byId[image.id]
                val uriRow = byUri[image.uri]
                require(image.id in initiallyExistingIds || idRow == null || uriRow != null || idRow.uri == image.uri) {
                    "Image ID cannot silently change document identity inside a new batch"
                }
                // A legacy URI row wins when its key differs from the canonical document key.
                val existing = when {
                    idRow != null && (uriRow == null || uriRow.id == idRow.id) -> idRow
                    uriRow != null -> uriRow
                    else -> null
                }
                val canonical = image.copy(
                    id = existing?.id ?: image.id,
                    createdAt = existing?.createdAt ?: image.createdAt,
                    updatedAt = now,
                    sizeBytes = image.sizeBytes ?: existing?.sizeBytes,
                    width = image.width ?: existing?.width,
                    height = image.height ?: existing?.height,
                )
                // Track writes inside this very scan as well as committed rows. A repeated URI
                // must not allocate a second key/link or lose fields accepted earlier in it.
                existing?.let { byUri.remove(it.uri) }
                byId[canonical.id] = canonical
                byUri[canonical.uri] = canonical
                pendingImages[canonical.id] = canonical
                if (canonical.id !in links) {
                    val link = P1SourceImageLinkEntity(sourceId, canonical.id, nextOrder++)
                    links[canonical.id] = link
                    pendingLinks += link
                }
            }
            return P1ScanCommitPlan(pendingImages.values.toList(), pendingLinks, pendingImages.size)
        }
    }
}
