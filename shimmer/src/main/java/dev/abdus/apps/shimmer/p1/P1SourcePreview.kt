package dev.abdus.apps.shimmer.p1

import dev.abdus.apps.shimmer.database.P1ImageRecordEntity

/** Chooses the deterministic source-card preview without changing the source configuration. */
fun chooseSourcePreview(kind: String, images: List<P1ImageRecordEntity>): P1ImageRecordEntity {
    require(images.isNotEmpty()) { "A source preview requires at least one image" }
    return if (kind == P1SourceKind.DIRECTORY_TREE.name) images.last() else images.first()
}

/** First few file names of a directory level, for the collapsed card summary. */
fun directoryFileSummary(images: List<P1ImageRecordEntity>, limit: Int = 3): String {
    val names = images.take(limit).map { it.displayName.ifBlank { it.relativePath } }
    return names.joinToString("、") + if (images.size > limit) " 等" else ""
}
