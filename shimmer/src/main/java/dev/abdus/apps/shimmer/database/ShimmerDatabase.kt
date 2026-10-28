package dev.abdus.apps.shimmer.database

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.flow.Flow

class UriConverters {
    @TypeConverter
    fun fromString(value: String?): Uri? {
        return value?.let { Uri.parse(it) }
    }

    @TypeConverter
    fun toString(uri: Uri?): String? {
        return uri?.toString()
    }
}

@Entity(
    tableName = "folders",
    indices = [Index(value = ["uri"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: Uri,
    val isEnabled: Boolean = true,
    val lastScannedAt: String? = null,
    val createdAt: String? = null,
    val lastPickedAt: String? = null
)

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("folderId"), Index("lastShownAt"), Index(value = ["uri"], unique = true)]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val uri: Uri,
    val lastShownAt: String? = null,
    val createdAt: String? = null,
    val favoriteRank: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val fileSize: Long? = null
)

data class FolderMetadata(
    val folderId: Long,
    val folderUri: Uri,
    val isEnabled: Boolean,
    val imageCount: Int,
    val thumbnailUri: Uri?
)

data class ImageEntry(
    val uri: Uri,
    val width: Int?,
    val height: Int?
)

data class ImageUriAndSize(
    val uri: Uri,
    val fileSize: Long?
)

/** P1 source contract. Existing folders/images remain intact for backwards compatibility. */
@Entity(
    tableName = "sources",
    indices = [Index(value = ["rootUri"], unique = true)],
)
data class P1SourceEntity(
    @PrimaryKey val id: String,
    val rootUri: String,
    val kind: String,
    @ColumnInfo(defaultValue = "1") val includeSubdirectories: Boolean = true,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    @ColumnInfo(defaultValue = "'NOT_SCANNED'") val scanState: String = "NOT_SCANNED",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val permissionState: String = "UNKNOWN",
    val lastScanAt: String? = null,
    val lastError: String? = null,
    /** Monotonic insertion position used to keep separately added sources grouped. */
    @ColumnInfo(defaultValue = "0") val sourceOrder: Long = 0,
)

@Entity(
    tableName = "image_records",
    indices = [Index(value = ["uri"], unique = true)],
)
data class P1ImageRecordEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @ColumnInfo(defaultValue = "'COMMITTED'") val metadataState: String = "COMMITTED",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Entity(
    tableName = "source_image_links",
    primaryKeys = ["sourceId", "imageId"],
    foreignKeys = [
        ForeignKey(entity = P1SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = CASCADE),
        ForeignKey(entity = P1ImageRecordEntity::class, parentColumns = ["id"], childColumns = ["imageId"], onDelete = CASCADE),
    ],
    indices = [Index("sourceId"), Index("imageId"), Index(value = ["sourceId", "sortOrder"])],
)
data class P1SourceImageLinkEntity(
    val sourceId: String,
    val imageId: String,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)

/**
 * One opted-out directory inside an otherwise enabled source.
 *
 * Absence of a row means the directory plays. That keeps an upgraded database playing exactly what
 * it played before, and lets a rescan that discovers new folders play them without writing a row
 * per folder. A row also covers every descendant directory, so stopping a parent stops its subtree.
 */
@Entity(
    tableName = "source_directory_exclusions",
    primaryKeys = ["sourceId", "directoryPath"],
    foreignKeys = [
        ForeignKey(entity = P1SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = CASCADE),
    ],
    indices = [Index("sourceId")],
)
data class P1SourceDirectoryExclusionEntity(
    val sourceId: String,
    /** Directory path relative to the source root, `/`-separated, without leading or trailing `/`. */
    val directoryPath: String,
    val updatedAt: String? = null,
)

/** Stable playback projection joining a committed image to an enabled P1 source. */
data class P1PlaybackImage(
    val id: String,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sourceId: String,
    val sourceOrder: Long,
    val sortOrder: Int,
    val sourceKind: String = "",
    val sourceRootUri: String = "",
)

@Dao
interface P1SourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: P1SourceEntity)

    @Query("""
        UPDATE sources SET rootUri = :rootUri, kind = :kind, includeSubdirectories = :includeSubdirectories,
            enabled = :enabled, updatedAt = :updatedAt
        WHERE id = :sourceId
    """)
    suspend fun updateSourceDefinition(
        sourceId: String,
        rootUri: String,
        kind: String,
        includeSubdirectories: Boolean,
        enabled: Boolean,
        updatedAt: String?,
    )

    @Query("UPDATE sources SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :sourceId")
    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean, updatedAt: String?)

    /**
     * Removes a source and its links. Foreign-key CASCADE deletes only the links;
     * image_records are intentionally retained because records can be shared by
     * multiple sources and may still belong to the playback snapshot.
     */
    @Query("DELETE FROM sources WHERE id = :sourceId")
    suspend fun deleteSource(sourceId: String): Int

    @Query("""
        UPDATE sources SET scanState = :scanState, permissionState = :permissionState,
            lastScanAt = :lastScanAt, lastError = :lastError, updatedAt = :updatedAt
        WHERE id = :sourceId
    """)
    suspend fun updateSourceState(
        sourceId: String,
        scanState: String,
        permissionState: String,
        lastScanAt: String?,
        lastError: String?,
        updatedAt: String?,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImages(images: List<P1ImageRecordEntity>)

    @Update
    suspend fun updateImages(images: List<P1ImageRecordEntity>)

    @Query("""
        UPDATE image_records SET uri = :uri, displayName = :displayName, relativePath = :relativePath,
            sizeBytes = :sizeBytes, width = :width, height = :height, metadataState = :metadataState,
            updatedAt = :updatedAt
        WHERE id = :imageId
    """)
    suspend fun updateImage(
        imageId: String,
        uri: String,
        displayName: String,
        relativePath: String,
        sizeBytes: Long?,
        width: Int?,
        height: Int?,
        metadataState: String,
        updatedAt: String?,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<P1SourceImageLinkEntity>)

    @Query("UPDATE source_image_links SET sortOrder = :sortOrder WHERE sourceId = :sourceId AND imageId = :imageId")
    suspend fun updateLinkOrder(sourceId: String, imageId: String, sortOrder: Int)

    @Query("SELECT * FROM sources WHERE id = :sourceId LIMIT 1")
    suspend fun getSource(sourceId: String): P1SourceEntity?

    @Query("SELECT * FROM sources WHERE rootUri = :rootUri LIMIT 1")
    suspend fun getSourceByUri(rootUri: String): P1SourceEntity?

    @Query("SELECT * FROM sources ORDER BY createdAt, id")
    suspend fun getSources(): List<P1SourceEntity>

    /** Keep the old SQL's exact enabled = 1 predicate, not Boolean's non-zero decoding. */
    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY createdAt, id")
    suspend fun getEnabledPlaybackSources(): List<P1SourceEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM sources)")
    suspend fun hasSources(): Boolean

    @Query("SELECT * FROM sources ORDER BY createdAt, id")
    fun sourcesFlow(): Flow<List<P1SourceEntity>>

    @Query("SELECT COALESCE(MAX(sourceOrder), -1) + 1 FROM sources")
    suspend fun nextSourceOrder(): Long

    @Query("SELECT * FROM image_records WHERE uri = :uri LIMIT 1")
    suspend fun getImageByUri(uri: String): P1ImageRecordEntity?

    @Query("SELECT * FROM image_records WHERE id = :imageId LIMIT 1")
    suspend fun getImage(imageId: String): P1ImageRecordEntity?

    @Query("SELECT * FROM image_records WHERE id IN (:imageIds)")
    suspend fun getImages(imageIds: List<String>): List<P1ImageRecordEntity>

    @Query("SELECT * FROM image_records WHERE uri IN (:uris)")
    suspend fun getImagesByUris(uris: List<String>): List<P1ImageRecordEntity>

    @Query("SELECT * FROM image_records ORDER BY id")
    suspend fun getAllImages(): List<P1ImageRecordEntity>

    @Query("SELECT * FROM source_image_links ORDER BY sourceId, sortOrder, imageId")
    suspend fun getAllLinks(): List<P1SourceImageLinkEntity>

    @Query("SELECT * FROM source_image_links WHERE sourceId = :sourceId ORDER BY sortOrder ASC, imageId ASC")
    suspend fun getLinks(sourceId: String): List<P1SourceImageLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExclusion(exclusion: P1SourceDirectoryExclusionEntity)

    @Query("DELETE FROM source_directory_exclusions WHERE sourceId = :sourceId AND directoryPath = :directoryPath")
    suspend fun deleteExclusion(sourceId: String, directoryPath: String)

    @Query("SELECT * FROM source_directory_exclusions WHERE sourceId = :sourceId ORDER BY directoryPath")
    suspend fun getExclusions(sourceId: String): List<P1SourceDirectoryExclusionEntity>

    @Query("SELECT * FROM source_directory_exclusions ORDER BY sourceId, directoryPath")
    suspend fun getAllExclusions(): List<P1SourceDirectoryExclusionEntity>

    /**
     * Stops or resumes one directory of a source. The row set is the whole state, so resuming a
     * directory whose parent is still excluded leaves the parent's exclusion in place.
     */
    suspend fun setDirectoryExcluded(sourceId: String, directoryPath: String, excluded: Boolean, updatedAt: String?) {
        val path = directoryPath.replace('\\', '/').trim('/')
        require(path.isNotEmpty()) { "The source root is controlled by the source enabled flag" }
        if (excluded) insertExclusion(P1SourceDirectoryExclusionEntity(sourceId, path, updatedAt))
        else deleteExclusion(sourceId, path)
    }

    @Query("""
        SELECT i.* FROM image_records i INNER JOIN source_image_links l ON i.id = l.imageId
        WHERE l.sourceId = :sourceId ORDER BY l.sortOrder, i.id
    """)
    suspend fun getSourceImages(sourceId: String): List<P1ImageRecordEntity>

    /**
     * One row per enabled link, in the same key order the playback projection uses. A single
     * join replaces five correlated subqueries per image, which stalled catalogs of thousands.
     *
     * Excluded directories are matched with `substr`, never `LIKE`: real folder names contain `%`
     * and `_`, which `LIKE` would treat as wildcards and silently exclude sibling folders.
     */
    @Query("""
        SELECT i.id AS id, i.uri AS uri, i.displayName AS displayName,
            i.relativePath AS relativePath, s.id AS sourceId, s.sourceOrder AS sourceOrder,
            l.sortOrder AS sortOrder, s.kind AS sourceKind, s.rootUri AS sourceRootUri
        FROM image_records i
        INNER JOIN source_image_links l ON l.imageId = i.id
        INNER JOIN sources s ON s.id = l.sourceId
        WHERE i.metadataState = 'COMMITTED' AND s.enabled = 1
          AND NOT EXISTS (
              SELECT 1 FROM source_directory_exclusions x
              WHERE x.sourceId = s.id
                AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'
          )
        ORDER BY s.sourceOrder ASC, l.sortOrder ASC, s.id ASC, i.id ASC
    """)
    suspend fun getEnabledPlaybackLinks(): List<P1PlaybackImage>

    /** Same filtering and complete ordering as [getEnabledPlaybackLinks], with source metadata
     * read once by [getEnabledPlaybackImages] rather than remapped for every linked image. */
    @Query("""
        SELECT i.id AS id, i.uri AS uri, i.displayName AS displayName,
            i.relativePath AS relativePath, s.id AS sourceId, l.sortOrder AS sortOrder
        FROM image_records i
        INNER JOIN source_image_links l ON l.imageId = i.id
        INNER JOIN sources s ON s.id = l.sourceId
        WHERE i.metadataState = 'COMMITTED' AND s.enabled = 1
          AND NOT EXISTS (
              SELECT 1 FROM source_directory_exclusions x
              WHERE x.sourceId = s.id
                AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'
          )
        ORDER BY s.sourceOrder ASC, l.sortOrder ASC, s.id ASC, i.id ASC
    """)
    suspend fun getEnabledPlaybackLinkRows(): List<P1PlaybackLinkRow>

    /** Real per-source rows use the existing (sourceId, sortOrder) index. Sources are selected
     * inside the caller's Room transaction; rules may deliberately select a disabled source. */
    @Query("""
        SELECT i.id AS id, i.uri AS uri, i.displayName AS displayName,
            i.relativePath AS relativePath, l.sortOrder AS sortOrder
        FROM source_image_links l
        INNER JOIN image_records i ON i.id = l.imageId
        WHERE l.sourceId = :sourceId AND i.metadataState = 'COMMITTED'
          AND NOT EXISTS (
              SELECT 1 FROM source_directory_exclusions x
              WHERE x.sourceId = l.sourceId
                AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'
          )
        ORDER BY l.sortOrder ASC, l.imageId ASC
    """)
    suspend fun getPlaybackSourceLinkRows(sourceId: String): List<P1PlaybackSourceLinkRow>

    /** Same committed rows/order as the five-column reference; all source exclusions still apply. */
    @Query("""
        SELECT CAST(printf('%08x%08x%08x%08x',
                length(CAST(i.id AS BLOB)), length(CAST(i.uri AS BLOB)),
                length(CAST(i.displayName AS BLOB)), length(CAST(i.relativePath AS BLOB))) ||
            CAST(i.id AS BLOB) || CAST(i.uri AS BLOB) ||
            CAST(i.displayName AS BLOB) || CAST(i.relativePath AS BLOB) AS BLOB) AS packedFields,
            l.sortOrder AS sortOrder
        FROM source_image_links l
        INNER JOIN image_records i ON i.id = l.imageId
        WHERE l.sourceId = :sourceId AND i.metadataState = 'COMMITTED'
          AND NOT EXISTS (
              SELECT 1 FROM source_directory_exclusions x
              WHERE x.sourceId = l.sourceId
                AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'
          )
        ORDER BY l.sortOrder ASC, l.imageId ASC
    """)
    suspend fun getPlaybackSourcePackedLinkRows(sourceId: String): List<P1PlaybackPackedSourceLinkRow>

    /** Only used after a fresh empty exclusion read inside this same Room transaction. */
    @Query("""
        SELECT CAST(printf('%08x%08x%08x%08x',
                length(CAST(i.id AS BLOB)), length(CAST(i.uri AS BLOB)),
                length(CAST(i.displayName AS BLOB)), length(CAST(i.relativePath AS BLOB))) ||
            CAST(i.id AS BLOB) || CAST(i.uri AS BLOB) ||
            CAST(i.displayName AS BLOB) || CAST(i.relativePath AS BLOB) AS BLOB) AS packedFields,
            l.sortOrder AS sortOrder
        FROM source_image_links l
        INNER JOIN image_records i ON i.id = l.imageId
        WHERE l.sourceId = :sourceId AND i.metadataState = 'COMMITTED'
        ORDER BY l.sortOrder ASC, l.imageId ASC
    """)
    suspend fun getPlaybackSourcePackedLinkRowsWithoutExclusions(sourceId: String): List<P1PlaybackPackedSourceLinkRow>

    /** No cross-action decisions: unusual TEXT bytes cause a whole-source reference re-read. */
    private suspend fun readPlaybackSourceLinks(sourceId: String): List<P1PlaybackSourceLinkRow> {
        val rows = if (getExclusions(sourceId).isEmpty()) {
            getPlaybackSourcePackedLinkRowsWithoutExclusions(sourceId)
        } else {
            getPlaybackSourcePackedLinkRows(sourceId)
        }
        return P1PlaybackPackedProjection.decode(rows) ?: getPlaybackSourceLinkRows(sourceId)
    }

    /**
     * Returns each committed image once, ordered by its enabled source links. The first row of
     * an image is its lowest (sourceOrder, sortOrder, sourceId) link, so keeping first
     * occurrences preserves both the chosen source and the global order.
     */
    @Transaction
    suspend fun getEnabledPlaybackImages(): List<P1PlaybackImage> {
        val batches = ArrayList<P1PlaybackSourceLinks>()
        for (source in getEnabledPlaybackSources()) {
            batches.add(P1PlaybackSourceLinks(source, readPlaybackSourceLinks(source.id)))
        }
        return P1PlaybackProjection.resolveSourceLinks(batches)
    }

    /**
     * P4 rule catalog: the same projection limited to [sourceIds], whether or not those sources
     * are enabled in P1. Directory exclusions still apply.
     */
    @Query("""
        SELECT i.id AS id, i.uri AS uri, i.displayName AS displayName,
            i.relativePath AS relativePath, s.id AS sourceId, s.sourceOrder AS sourceOrder,
            l.sortOrder AS sortOrder, s.kind AS sourceKind, s.rootUri AS sourceRootUri
        FROM image_records i
        INNER JOIN source_image_links l ON l.imageId = i.id
        INNER JOIN sources s ON s.id = l.sourceId
        WHERE i.metadataState = 'COMMITTED' AND s.id IN (:sourceIds)
          AND NOT EXISTS (
              SELECT 1 FROM source_directory_exclusions x
              WHERE x.sourceId = s.id
                AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'
          )
        ORDER BY s.sourceOrder ASC, l.sortOrder ASC, s.id ASC, i.id ASC
    """)
    suspend fun getRulePlaybackLinks(sourceIds: List<String>): List<P1PlaybackImage>

    /** The compact rule projection keeps disabled rule sources and literal directory exclusions. */
    @Query("""
        SELECT i.id AS id, i.uri AS uri, i.displayName AS displayName,
            i.relativePath AS relativePath, s.id AS sourceId, l.sortOrder AS sortOrder
        FROM image_records i
        INNER JOIN source_image_links l ON l.imageId = i.id
        INNER JOIN sources s ON s.id = l.sourceId
        WHERE i.metadataState = 'COMMITTED' AND s.id IN (:sourceIds)
          AND NOT EXISTS (
              SELECT 1 FROM source_directory_exclusions x
              WHERE x.sourceId = s.id
                AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'
          )
        ORDER BY s.sourceOrder ASC, l.sortOrder ASC, s.id ASC, i.id ASC
    """)
    suspend fun getRulePlaybackLinkRows(sourceIds: List<String>): List<P1PlaybackLinkRow>

    /** [getRulePlaybackLinks] with each image once, as [getEnabledPlaybackImages] does. */
    @Transaction
    suspend fun getRulePlaybackImages(sourceIds: List<String>): List<P1PlaybackImage> {
        if (sourceIds.isEmpty()) return emptyList()
        val selected = sourceIds.toHashSet()
        val batches = ArrayList<P1PlaybackSourceLinks>()
        for (source in getSources()) {
            if (source.id in selected) batches.add(P1PlaybackSourceLinks(source, readPlaybackSourceLinks(source.id)))
        }
        return P1PlaybackProjection.resolveSourceLinks(batches)
    }

    /** Re-selecting an existing URI must reuse its identity and never cascade-delete links. */
    @Transaction
    suspend fun registerSource(source: P1SourceEntity): P1SourceEntity {
        val existingById = getSource(source.id)
        val existingByUri = getSourceByUri(source.rootUri)
        // Re-authorisation deliberately keeps the existing source ID. If the replacement URI is
        // already bound to another source, fail before changing either source; silently adopting
        // that source would attach future scans and links to the wrong configuration.
        if (existingById != null && existingByUri != null && existingById.id != existingByUri.id) {
            throw IllegalStateException("Source URI is already bound to another source")
        }
        val existing = existingById ?: existingByUri
        // A stable root URI is the source identity. Re-selecting it must not silently change
        // the source kind (for example DIRECTORY_TREE to MULTIPLE_DOCUMENTS).
        val canonical = source.copy(
            id = existing?.id ?: source.id,
            kind = existing?.kind ?: source.kind,
            sourceOrder = existing?.sourceOrder ?: nextSourceOrder(),
            includeSubdirectories = existing?.includeSubdirectories ?: source.includeSubdirectories,
        )
        insertSource(canonical)
        updateSourceDefinition(canonical.id, canonical.rootUri, canonical.kind,
            canonical.includeSubdirectories, canonical.enabled, canonical.updatedAt)
        return requireNotNull(getSource(canonical.id))
    }

    /**
     * Atomically accepts a complete, decoded scan. It is deliberately add/update-only: absence
     * from a provider view is not permission to delete the user's committed configuration.
     * Global URI identity preserves links from overlapping sources; existing order is retained.
     * A throw/cancellation rolls back all image/link/state changes through Room's transaction.
     */
    @Transaction
    suspend fun commitScan(
        sourceId: String,
        images: List<P1ImageRecordEntity>,
        permissionState: String,
        now: String,
    ): Int {
        requireNotNull(getSource(sourceId)) { "Source must be registered before committing" }
        if (images.isEmpty()) {
            updateSourceState(sourceId, "EMPTY", permissionState, now, "EMPTY_RESULT", now)
            return 0
        }
        val existing = linkedMapOf<String, P1ImageRecordEntity>()
        images.map { it.id }.distinct().chunked(P1ScanCommitPlan.QUERY_CHUNK_SIZE).forEach { chunk ->
            getImages(chunk).forEach { existing[it.id] = it }
        }
        images.map { it.uri }.distinct().chunked(P1ScanCommitPlan.QUERY_CHUNK_SIZE).forEach { chunk ->
            getImagesByUris(chunk).forEach { existing[it.id] = it }
        }
        val links = getLinks(sourceId)
        // Re-authorisation can change a stable ID's access URI. A rare ordered URI-release
        // sequence cannot safely be collapsed into final rows (e.g. A:X->Z, B:Y->X, A:Z->Y).
        // Keep the proven per-row path for that case; ordinary new scans and unchanged rescans
        // take the bounded batch path below. Both run inside this same transaction.
        if (images.any { image -> existing[image.id]?.let { it.uri != image.uri } == true }) {
            val accepted = commitAccessUriChanges(sourceId, images, existing, links, now)
            updateSourceState(sourceId, "COMMITTED", permissionState, now, null, now)
            return accepted
        }
        val plan = P1ScanCommitPlan.prepare(sourceId, images, existing.values.toList(), links, now)
        // List operations still execute individual prepared SQLite statements, but avoid the
        // per-image Room coroutine/query round trips. Never use REPLACE: it cascades links away.
        // Release a changed access URI on an existing row before inserting a newly discovered
        // row using that old URI. INSERT IGNORE first would silently skip the new row and then
        // make its link fail the foreign key check, rolling back an otherwise valid scan.
        updateImages(plan.images.filter { it.id in existing })
        insertImages(plan.images.filter { it.id !in existing })
        insertLinks(plan.newLinks)
        updateSourceState(sourceId, "COMMITTED", permissionState, now, null, now)
        return plan.acceptedCount
    }

    @Transaction
    suspend fun commitAccessUriChanges(
        sourceId: String,
        images: List<P1ImageRecordEntity>,
        initiallyExisting: Map<String, P1ImageRecordEntity>,
        existingLinks: List<P1SourceImageLinkEntity>,
        now: String,
    ): Int {
        val links = existingLinks.associateBy { it.imageId }.toMutableMap()
        var nextOrder = (existingLinks.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val accepted = linkedSetOf<String>()
        for (image in images) {
            require(image.uri.isNotBlank() && image.id.isNotBlank()) { "Image identity is required" }
            require(image.metadataState == "COMMITTED") { "Only complete image metadata may commit" }
            val idRow = initiallyExisting[image.id]
            val uriRow = getImageByUri(image.uri)
            val existing = when {
                idRow != null && (uriRow == null || uriRow.id == idRow.id) -> idRow
                uriRow != null -> uriRow
                else -> null
            }
            val collision = getImage(image.id)
            require(existing != null || collision == null || collision.uri == image.uri) {
                "Image ID cannot silently change document identity"
            }
            val canonical = image.copy(id = existing?.id ?: image.id, createdAt = existing?.createdAt ?: image.createdAt)
            insertImages(listOf(canonical))
            updateImage(canonical.id, canonical.uri, canonical.displayName, canonical.relativePath,
                canonical.sizeBytes ?: existing?.sizeBytes, canonical.width ?: existing?.width,
                canonical.height ?: existing?.height, canonical.metadataState, now)
            if (canonical.id !in links) {
                val link = P1SourceImageLinkEntity(sourceId, canonical.id, nextOrder++)
                insertLinks(listOf(link))
                links[canonical.id] = link
            }
            accepted += canonical.id
        }
        return accepted.size
    }

    /** Reordering changes only this source's links and rejects missing/duplicate IDs. */
    @Transaction
    suspend fun reorderSource(sourceId: String, imageIds: List<String>) {
        val current = getLinks(sourceId).map { it.imageId }.toSet()
        require(imageIds.size == current.size && imageIds.toSet() == current) {
            "Order must contain every linked image exactly once"
        }
        imageIds.forEachIndexed { index, imageId -> updateLinkOrder(sourceId, imageId, index) }
    }
}

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Query("SELECT id FROM folders WHERE uri = :uri LIMIT 1")
    suspend fun getFolderId(uri: Uri): Long?

    @Query("SELECT lastScannedAt FROM folders WHERE id = :id")
    suspend fun getFolderLastScanned(id: Long): String?

    @Query("UPDATE folders SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateFolderEnabled(id: Long, isEnabled: Boolean)

    @Query("UPDATE folders SET lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateFolderLastScanned(id: Long, lastScannedAt: String)

    @Query("UPDATE folders SET lastPickedAt = :isoDate WHERE id = :id")
    suspend fun updateFolderLastPicked(id: Long, isoDate: String)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun deleteFolderByUri(uri: Uri)

    @Query("SELECT * FROM folders")
    fun getAllFoldersFlow(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImages(images: List<ImageEntity>)

    @Query("DELETE FROM images WHERE folderId = :folderId AND uri NOT IN (:validUris)")
    suspend fun deleteInvalidImages(folderId: Long, validUris: List<Uri>)

    @Query("DELETE FROM images WHERE uri = :uri")
    suspend fun deleteImageByUri(uri: Uri)

    /**
     * Finds the next folder in the round-robin cycle.
     */
    @Query("""
        SELECT * FROM folders
        WHERE isEnabled = 1
        ORDER BY lastPickedAt ASC, id ASC
        LIMIT 1
    """)
    suspend fun getNextRoundRobinFolder(): FolderEntity?

    /**
     * Picks the next image from a specific folder.
     */
    @Query("""
        SELECT * FROM images 
        WHERE folderId = :folderId 
        ORDER BY lastShownAt ASC, favoriteRank DESC, RANDOM() 
        LIMIT 1
    """)
    suspend fun getNextImageFromFolder(folderId: Long): ImageEntity?

    @Query("SELECT * FROM images ORDER BY lastShownAt DESC LIMIT 1")
    suspend fun getLatestShownImage(): ImageEntity?

    @Query("SELECT * FROM images ORDER BY lastShownAt DESC LIMIT 1")
    fun getLatestShownImageFlow(): Flow<ImageEntity?>

    @Query("SELECT uri, width, height FROM images WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun getImagesForFolderFlow(folderId: Long): Flow<List<ImageEntry>>

    @Query("UPDATE images SET lastShownAt = :isoDate WHERE id = :id")
    suspend fun updateLastShown(id: Long, isoDate: String)

    @Query("UPDATE images SET lastShownAt = :isoDate WHERE uri = :uri")
    suspend fun updateLastShownByUri(uri: Uri, isoDate: String)

    @Query("UPDATE images SET favoriteRank = favoriteRank + 1 WHERE uri = :uri")
    suspend fun incrementFavoriteRank(uri: Uri)

    @Query("SELECT * FROM images WHERE uri = :uri LIMIT 1")
    suspend fun getImageByUri(uri: Uri): ImageEntity?

    @Query("""
        SELECT COUNT(*) > 0 FROM images i 
        INNER JOIN folders f ON i.folderId = f.id 
        WHERE i.uri = :uri AND f.isEnabled = 1
    """)
    suspend fun isImageManagedAndEnabled(uri: Uri): Boolean

    @Query("SELECT uri, fileSize FROM images WHERE folderId = :folderId")
    suspend fun getImageUrisAndSizesForFolder(folderId: Long): List<ImageUriAndSize>

    @Query("""
        SELECT images.* FROM images
        INNER JOIN folders ON images.folderId = folders.id
        WHERE folders.isEnabled = 1
        ORDER BY images.id ASC
    """)
    suspend fun getEnabledImagesForPlayback(): List<ImageEntity>

    /**
     * Finds the next image in the round-robin cycle across all enabled folders.
     * 1. Prioritizes folders that haven't been picked from recently.
     * 2. Within a folder, prioritizes images that have never been shown.
     * 3. Picks randomly among the top candidates.
     */
    @Query("""
        SELECT images.* 
        FROM images 
        JOIN folders ON images.folderId = folders.id
        WHERE folders.isEnabled = 1
        ORDER BY 
            folders.lastPickedAt ASC,
            (images.lastShownAt IS NOT NULL) ASC,
            RANDOM()
        LIMIT 1
    """)
    suspend fun findNextCycleImage(): ImageEntity?

    @Query("SELECT COUNT(*) FROM folders WHERE isEnabled = 1")
    fun getEnabledFoldersCountFlow(): Flow<Int>

    @Query("""
        SELECT 
            f.id AS folderId,
            f.uri AS folderUri, 
            f.isEnabled AS isEnabled,
            (SELECT COUNT(*) FROM images WHERE folderId = f.id) AS imageCount,
            (SELECT uri FROM images WHERE folderId = f.id ORDER BY id DESC LIMIT 1) AS thumbnailUri
        FROM folders f;
""")
    fun getFoldersMetadataFlow(): Flow<List<FolderMetadata>>
}

@Database(
    entities = [
        FolderEntity::class,
        ImageEntity::class,
        P1SourceEntity::class,
        P1ImageRecordEntity::class,
        P1SourceImageLinkEntity::class,
        P1SourceDirectoryExclusionEntity::class,
        P1CatalogRevisionEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(UriConverters::class)
abstract class ShimmerDatabase : RoomDatabase() {
    abstract fun imageDao(): ImageDao
    abstract fun p1SourceDao(): P1SourceDao

    companion object {
        @Volatile
        private var INSTANCE: ShimmerDatabase? = null

        @Volatile
        private var LOCK_INSTANCE: ShimmerDatabase? = null

        /** HOME retains its original file and schema; LOCK starts as a separate, empty v8 graph. */
        fun getInstance(context: Context, target: WallpaperTarget = WallpaperTarget.HOME): ShimmerDatabase =
            when (target) {
                WallpaperTarget.HOME -> INSTANCE ?: synchronized(this) {
                    INSTANCE ?: buildDatabase(context, target).also { INSTANCE = it }
                }
                WallpaperTarget.LOCK -> LOCK_INSTANCE ?: synchronized(this) {
                    LOCK_INSTANCE ?: buildDatabase(context, target).also { LOCK_INSTANCE = it }
                }
            }

        fun nameFor(target: WallpaperTarget): String = when (target) {
            WallpaperTarget.HOME -> "shimmer_database"
            WallpaperTarget.LOCK -> "p10_lock_database"
        }

        private fun buildDatabase(context: Context, target: WallpaperTarget): ShimmerDatabase =
            Room.databaseBuilder(context.applicationContext, ShimmerDatabase::class.java, nameFor(target))
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .addCallback(P1CatalogRevision.callback)
                .build()

        /**
         * Adds the P1 source graph without dropping v3 folders/images. Legacy rows are copied
         * into the new graph using deterministic IDs so an upgrade remains inspectable and
         * existing wallpaper configuration stays available through the old DAO.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sources (
                        id TEXT NOT NULL PRIMARY KEY,
                        rootUri TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        includeSubdirectories INTEGER NOT NULL DEFAULT 1,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        createdAt TEXT,
                        updatedAt TEXT,
                        sourceOrder INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sources_rootUri ON sources(rootUri)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS image_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        uri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        sizeBytes INTEGER,
                        width INTEGER,
                        height INTEGER,
                        metadataState TEXT NOT NULL DEFAULT 'COMMITTED',
                        createdAt TEXT,
                        updatedAt TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_image_links (
                        sourceId TEXT NOT NULL,
                        imageId TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(sourceId, imageId),
                        FOREIGN KEY(sourceId) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(imageId) REFERENCES image_records(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_image_links_sourceId ON source_image_links(sourceId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_image_links_imageId ON source_image_links(imageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_image_links_sourceId_sortOrder ON source_image_links(sourceId, sortOrder)")
                database.execSQL("""
                    INSERT OR IGNORE INTO sources(id, rootUri, kind, includeSubdirectories, enabled, createdAt, updatedAt, sourceOrder)
                    SELECT 'legacy-folder:' || id, uri, 'DIRECTORY_TREE', 1, isEnabled,
                           createdAt, lastScannedAt, id
                    FROM folders
                """.trimIndent())
                database.execSQL("""
                    INSERT OR IGNORE INTO image_records(
                        id, uri, displayName, relativePath, sizeBytes, width, height, metadataState, createdAt, updatedAt
                    )
                    SELECT 'legacy-image:' || id, uri,
                           substr(uri, instr(uri, '/') + 1),
                           substr(uri, instr(uri, '/') + 1),
                           fileSize, width, height, 'COMMITTED', createdAt, lastShownAt
                    FROM images
                """.trimIndent())
                database.execSQL("""
                    INSERT OR IGNORE INTO source_image_links(sourceId, imageId, sortOrder)
                    SELECT 'legacy-folder:' || folderId, 'legacy-image:' || id, id
                    FROM images
                """.trimIndent())
            }
        }

        /**
         * Normalizes both historical v4 table variants (fresh Room-created tables had no SQL
         * defaults; migrated tables did). All sources and source associations survive; URI
         * duplicates become one image identity with the union of their associations.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE sources_v5 (
                        id TEXT NOT NULL PRIMARY KEY, rootUri TEXT NOT NULL, kind TEXT NOT NULL,
                        includeSubdirectories INTEGER NOT NULL DEFAULT 1,
                        enabled INTEGER NOT NULL DEFAULT 1, createdAt TEXT, updatedAt TEXT,
                        scanState TEXT NOT NULL DEFAULT 'NOT_SCANNED',
                        permissionState TEXT NOT NULL DEFAULT 'UNKNOWN', lastScanAt TEXT, lastError TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO sources_v5(id, rootUri, kind, includeSubdirectories, enabled, createdAt, updatedAt)
                    SELECT id, rootUri, kind, includeSubdirectories, enabled, createdAt, updatedAt FROM sources
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE image_records_v5 (
                        id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, displayName TEXT NOT NULL,
                        relativePath TEXT NOT NULL, sizeBytes INTEGER, width INTEGER, height INTEGER,
                        metadataState TEXT NOT NULL DEFAULT 'COMMITTED', createdAt TEXT, updatedAt TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO image_records_v5
                    SELECT * FROM image_records WHERE id IN
                        (SELECT MIN(id) FROM image_records GROUP BY uri)
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE source_image_links_v5 (
                        sourceId TEXT NOT NULL, imageId TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(sourceId, imageId),
                        FOREIGN KEY(sourceId) REFERENCES sources_v5(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(imageId) REFERENCES image_records_v5(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO source_image_links_v5(sourceId, imageId, sortOrder)
                    SELECT l.sourceId, canonical.id, MIN(l.sortOrder)
                    FROM source_image_links l
                    INNER JOIN image_records i ON i.id = l.imageId
                    INNER JOIN image_records_v5 canonical ON canonical.uri = i.uri
                    GROUP BY l.sourceId, canonical.id
                """.trimIndent())
                // New tables already contain the complete normalized graph before old tables
                // are retired. Room runs this migration in one rollback-capable transaction.
                database.execSQL("DROP TABLE source_image_links")
                database.execSQL("DROP TABLE image_records")
                database.execSQL("DROP TABLE sources")
                database.execSQL("ALTER TABLE sources_v5 RENAME TO sources")
                database.execSQL("ALTER TABLE image_records_v5 RENAME TO image_records")
                database.execSQL("ALTER TABLE source_image_links_v5 RENAME TO source_image_links")
                database.execSQL("CREATE UNIQUE INDEX index_sources_rootUri ON sources(rootUri)")
                database.execSQL("CREATE UNIQUE INDEX index_image_records_uri ON image_records(uri)")
                database.execSQL("CREATE INDEX index_source_image_links_sourceId ON source_image_links(sourceId)")
                database.execSQL("CREATE INDEX index_source_image_links_imageId ON source_image_links(imageId)")
                database.execSQL("CREATE INDEX index_source_image_links_sourceId_sortOrder ON source_image_links(sourceId, sortOrder)")
            }
        }

        /** Adds the persistent source insertion position to an existing v5 database. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sources ADD COLUMN sourceOrder INTEGER NOT NULL DEFAULT 0")
                // Existing v5 rows had no insertion position. Reconstruct a deterministic order
                // from the original creation timestamp and source ID before new sources append.
                database.execSQL("""
                    UPDATE sources
                    SET sourceOrder = (
                        SELECT COUNT(*)
                        FROM sources AS prior
                        WHERE COALESCE(prior.createdAt, '') < COALESCE(sources.createdAt, '')
                           OR (
                               COALESCE(prior.createdAt, '') = COALESCE(sources.createdAt, '')
                               AND prior.id < sources.id
                           )
                    )
                """.trimIndent())
            }
        }

        /**
         * Adds the per-directory opt-out table. Purely additive: no existing row is read or
         * rewritten, and an empty table means every directory of an enabled source keeps playing.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_directory_exclusions (
                        sourceId TEXT NOT NULL,
                        directoryPath TEXT NOT NULL,
                        updatedAt TEXT,
                        PRIMARY KEY(sourceId, directoryPath),
                        FOREIGN KEY(sourceId) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_source_directory_exclusions_sourceId " +
                        "ON source_directory_exclusions(sourceId)",
                )
            }
        }

        /** Adds an exact directory revision without changing any existing image or source. */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                P1CatalogRevision.install(database)
            }
        }
    }
}
