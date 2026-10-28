package dev.abdus.apps.shimmer.database

import java.util.PriorityQueue

/** One ordered link without copying the same source metadata once for every image. */
data class P1PlaybackLinkRow(
    val id: String,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sourceId: String,
    val sortOrder: Int,
)

/** One source's rows; the authoritative source object supplies all repeated source metadata. */
data class P1PlaybackSourceLinkRow(
    val id: String,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    /** Preserve SQLite's full INTEGER ordering before the legacy projection converts to Int. */
    val sortOrder: Long,
)

data class P1PlaybackSourceLinks(
    val source: P1SourceEntity,
    /** SQLite has already ordered these rows by sortOrder then imageId using BINARY collation. */
    val links: List<P1PlaybackSourceLinkRow>,
)

object P1PlaybackProjection {
    /**
     * Reconstructs the unchanged SQL key (sourceOrder, sortOrder, sourceId, imageId) without a
     * whole-catalog join sort or remapping sourceId in every row. Every batch must be read in the
     * same Room transaction, and each batch is already ordered by (sortOrder, imageId).
     * Equal sourceOrder values need a merge, not source-by-source concatenation.
     */
    fun resolveSourceLinks(batches: List<P1PlaybackSourceLinks>): List<P1PlaybackImage> {
        val sourceIds = HashSet<String>(batches.size)
        for (batch in batches) {
            check(sourceIds.add(batch.source.id)) { "Duplicate playback source identity" }
        }
        val ordered = batches.map { OrderedSource(it, it.source.id.toByteArray(Charsets.UTF_8)) }
            .sortedWith { left, right ->
                val byOrder = left.batch.source.sourceOrder.compareTo(right.batch.source.sourceOrder)
                if (byOrder != 0) byOrder else compareBytes(left.binaryId, right.binaryId)
            }
        val expectedRows = projectionCapacity(batches.map { it.links.size })
        val images = ArrayList<P1PlaybackImage>(expectedRows)
        val seen = HashSet<String>(expectedRows + expectedRows / 3 + 1)
        fun append(source: P1SourceEntity, link: P1PlaybackSourceLinkRow) {
            if (!seen.add(link.id)) return
            images.add(P1PlaybackImage(link.id, link.uri, link.displayName, link.relativePath,
                source.id, source.sourceOrder, link.sortOrder.toInt(), source.kind, source.rootUri))
        }
        var start = 0
        while (start < ordered.size) {
            var end = start + 1
            while (end < ordered.size &&
                ordered[end].batch.source.sourceOrder == ordered[start].batch.source.sourceOrder) end++
            if (end == start + 1) {
                val batch = ordered[start].batch
                for (link in batch.links) append(batch.source, link)
            } else {
                val heads = PriorityQueue<SourceCursor> { left, right ->
                    val byLink = left.current.sortOrder.compareTo(right.current.sortOrder)
                    // Ranks are exact SQLite BINARY sourceId order. One head exists per source;
                    // same-source imageId ties are already ordered by the real SQLite query.
                    if (byLink != 0) byLink else left.rank.compareTo(right.rank)
                }
                for (rank in start until end) {
                    val batch = ordered[rank].batch
                    if (batch.links.isNotEmpty()) heads.add(SourceCursor(batch, rank))
                }
                while (heads.isNotEmpty()) {
                    val cursor = heads.remove()
                    append(cursor.batch.source, cursor.current)
                    cursor.index++
                    if (cursor.index < cursor.batch.links.size) heads.add(cursor)
                }
            }
            start = end
        }
        return images
    }

    /** Allocation hint from rows already read, never an identity/freshness or permission shortcut. */
    internal fun projectionCapacity(sizes: List<Int>): Int {
        var total = 0L
        for (size in sizes) {
            require(size >= 0) { "Negative source row count" }
            total = (total + size.toLong()).coerceAtMost(1_000_000)
        }
        return total.toInt()
    }

    private class OrderedSource(val batch: P1PlaybackSourceLinks, val binaryId: ByteArray)
    private class SourceCursor(val batch: P1PlaybackSourceLinks, val rank: Int, var index: Int = 0) {
        val current: P1PlaybackSourceLinkRow get() = batch.links[index]
    }

    /** SQLite's default TEXT BINARY collation compares UTF-8 bytes, not UTF-16 code units. */
    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val byByte = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (byByte != 0) return byByte
        }
        return left.size.compareTo(right.size)
    }

    /**
     * [sources] and [links] must come from the same Room read transaction. The links already have
     * the old SQL's complete order; keep each image's first link without sorting it again.
     * Missing/ambiguous metadata is an error, never permission to attach an unrelated source.
     */
    fun resolve(sources: List<P1SourceEntity>, links: List<P1PlaybackLinkRow>): List<P1PlaybackImage> {
        val bySource = HashMap<String, P1SourceEntity>(sources.size)
        for (source in sources) {
            check(bySource.put(source.id, source) == null) { "Duplicate playback source identity" }
        }
        val seen = HashSet<String>()
        val images = ArrayList<P1PlaybackImage>(links.size)
        for (link in links) {
            val source = checkNotNull(bySource[link.sourceId]) { "Playback link source is missing" }
            if (!seen.add(link.id)) continue
            images.add(
                P1PlaybackImage(
                    id = link.id,
                    uri = link.uri,
                    displayName = link.displayName,
                    relativePath = link.relativePath,
                    sourceId = source.id,
                    sourceOrder = source.sourceOrder,
                    sortOrder = link.sortOrder,
                    sourceKind = source.kind,
                    sourceRootUri = source.rootUri,
                ),
            )
        }
        return images
    }
}
