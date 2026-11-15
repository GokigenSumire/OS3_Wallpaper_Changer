package dev.abdus.apps.shimmer.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.abdus.apps.shimmer.p2.FileTimestamps
import dev.abdus.apps.shimmer.p2.PlaybackDirectoryTime

@Entity(tableName = "source_file_times", primaryKeys = ["sourceId", "nodeKey"],
    foreignKeys = [ForeignKey(entity = P1SourceEntity::class, parentColumns = ["id"],
        childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sourceId")])
data class P1FileTimeEntity(
    val sourceId: String,
    /** Files use their access URI; directories use dir: plus the source-relative path. */
    val nodeKey: String,
    val createdMillis: Long? = null,
    val modifiedMillis: Long? = null,
) {
    fun times() = FileTimestamps(createdMillis?.takeIf { it > 0 }, modifiedMillis?.takeIf { it > 0 })
}

@Dao
interface P1FileTimeDao {
    @Query("SELECT * FROM source_file_times WHERE sourceId = :sourceId")
    suspend fun getForSource(sourceId: String): List<P1FileTimeEntity>
    @Query("SELECT * FROM source_file_times WHERE sourceId = :sourceId AND nodeKey = :uri")
    suspend fun getFile(sourceId: String, uri: String): P1FileTimeEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<P1FileTimeEntity>)
}

/** Index once per source; ancestor lists are immutable and shared within that source. */
internal class P1FileTimeIndex(rows: List<P1FileTimeEntity>) {
    private val byKey = rows.associateBy { it.nodeKey }
    // Overlapping tree grants can open the same original document through different routes.
    // Keep source scoping, but match the canonical document when the committed access URI moved.
    private val byIdentity by lazy {
        byKey.values.filterNot { it.nodeKey.startsWith("dir:") }.associateBy {
            dev.abdus.apps.shimmer.p1.p1DocumentIdentity(android.net.Uri.parse(it.nodeKey))
        }
    }
    private val directories = HashMap<String, List<PlaybackDirectoryTime>>()
    fun file(uri: String): FileTimestamps {
        byKey[uri]?.let { return it.times() }
        if (byKey.isEmpty()) return FileTimestamps()
        return byIdentity[dev.abdus.apps.shimmer.p1.p1DocumentIdentity(android.net.Uri.parse(uri))]?.times()
            ?: FileTimestamps()
    }
    fun ancestors(relativePath: String, directorySource: Boolean): List<PlaybackDirectoryTime> {
        if (!directorySource || byKey.isEmpty()) return emptyList()
        val parent = relativePath.replace('\\', '/').trim('/').substringBeforeLast('/', "")
        return directories.getOrPut(parent) {
            val paths = mutableListOf("")
            var path = ""
            for (part in parent.split('/').filter { it.isNotEmpty() }) {
                path = if (path.isEmpty()) part else "$path/$part"
                paths += path
            }
            java.util.Collections.unmodifiableList(paths.map { PlaybackDirectoryTime(it,
                byKey["dir:$it"]?.times() ?: FileTimestamps()) })
        }
    }
}
