package dev.abdus.apps.shimmer.database

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Fresh full catalog reads using Room's public platform Cursor API in its configured Android
 * compatibility mode. The generated SQLiteStatement already delegates to a Cursor: this removes
 * its repeated field guards and the SQL-packed BLOB round trip, not the authoritative read.
 * Sources, exclusions and links share one Room transaction. No context hop or cached catalog is
 * allowed inside it; SQL/data/cancellation failures propagate rather than becoming an empty set.
 */
class P1PlaybackCursorReader(private val database: ShimmerDatabase) {
    private val dao = database.p1SourceDao()

    suspend fun getEnabledPlaybackImages(): List<P1PlaybackImage> = database.withTransaction {
        currentCoroutineContext().ensureActive()
        val batches = ArrayList<P1PlaybackSourceLinks>()
        for (source in dao.getEnabledPlaybackSources()) {
            batches.add(P1PlaybackSourceLinks(source, readSourceLinks(source.id)))
        }
        currentCoroutineContext().ensureActive()
        P1PlaybackProjection.resolveSourceLinks(batches)
    }

    suspend fun getRulePlaybackImages(sourceIds: List<String>): List<P1PlaybackImage> {
        if (sourceIds.isEmpty()) return emptyList()
        return database.withTransaction {
            currentCoroutineContext().ensureActive()
            val selected = sourceIds.toHashSet()
            val batches = ArrayList<P1PlaybackSourceLinks>()
            for (source in dao.getSources()) {
                if (source.id in selected) {
                    batches.add(P1PlaybackSourceLinks(source, readSourceLinks(source.id)))
                }
            }
            currentCoroutineContext().ensureActive()
            P1PlaybackProjection.resolveSourceLinks(batches)
        }
    }

    private suspend fun readSourceLinks(sourceId: String): List<P1PlaybackSourceLinkRow> {
        val sql = if (dao.getExclusions(sourceId).isEmpty()) {
            SOURCE_LINKS_WITHOUT_EXCLUSIONS
        } else {
            SOURCE_LINKS_WITH_EXCLUSIONS
        }
        val context = currentCoroutineContext()
        context.ensureActive()
        // Room.query checks the Main/suspending-transaction boundary and uses the same platform
        // connection as the source/exclusion DAOs. Never open a second database or switch context.
        return readRows(database.query(SimpleSQLiteQuery(sql, arrayOf(sourceId)))) { context.ensureActive() }
    }

    companion object {
        /** Original five-column WHERE/order, with a bind argument rather than interpolated ID. */
        internal val SOURCE_LINKS_WITH_EXCLUSIONS = """
            SELECT i.id AS id, i.uri AS uri, i.displayName AS displayName,
                i.relativePath AS relativePath, l.sortOrder AS sortOrder
            FROM source_image_links l
            INNER JOIN image_records i ON i.id = l.imageId
            WHERE l.sourceId = ? AND i.metadataState = 'COMMITTED'
              AND NOT EXISTS (
                  SELECT 1 FROM source_directory_exclusions x
                  WHERE x.sourceId = l.sourceId
                    AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'
              )
            ORDER BY l.sortOrder ASC, l.imageId ASC
        """.trimIndent()

        /** Only chosen after a real empty exclusion read in this same Room transaction. */
        internal val SOURCE_LINKS_WITHOUT_EXCLUSIONS = """
            SELECT i.id AS id, i.uri AS uri, i.displayName AS displayName,
                i.relativePath AS relativePath, l.sortOrder AS sortOrder
            FROM source_image_links l
            INNER JOIN image_records i ON i.id = l.imageId
            WHERE l.sourceId = ? AND i.metadataState = 'COMMITTED'
            ORDER BY l.sortOrder ASC, l.imageId ASC
        """.trimIndent()

        /** Owns the real query Cursor even if a getter or cancellation check fails. */
        internal fun readRows(cursor: Cursor, checkActive: () -> Unit): List<P1PlaybackSourceLinkRow> = cursor.use {
            checkActive()
            check(cursor.columnCount == 5) { "Unexpected playback cursor projection" }
            val rows = ArrayList<P1PlaybackSourceLinkRow>()
            while (cursor.moveToNext()) {
                checkActive()
                rows.add(P1PlaybackSourceLinkRow(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4),
                ))
            }
            checkActive()
            rows
        }
    }
}
