package dev.abdus.apps.shimmer.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Entity(tableName = "playback_catalog_revision")
data class P1CatalogRevisionEntity(
    @PrimaryKey val id: Int = 0,
    val epoch: String,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
)

data class P1CatalogStamp(val epoch: String, val revision: Long, val schemaVersion: Long = 0)

/** Persistent transaction identity, not a listener, content hash or time-to-live. */
object P1CatalogRevision {
    internal const val TABLE = "playback_catalog_revision"
    internal val tables = listOf("sources", "image_records", "source_image_links", "source_directory_exclusions")
    internal val triggerDefinitions: Map<String, String> = buildMap {
        for (table in tables) for (action in listOf("INSERT", "UPDATE", "DELETE")) {
            val name = "p9_catalog_${table}_${action.lowercase()}"
            put(name, "CREATE TRIGGER $name AFTER $action ON $table BEGIN " +
                "UPDATE playback_catalog_revision SET revision = CASE " +
                "WHEN revision < 9223372036854775807 THEN revision + 1 ELSE revision END WHERE id = 0; END")
        }
    }

    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) { install(db) }
        override fun onOpen(db: SupportSQLiteDatabase) { install(db) }
    }

    /** Additive setup. Repairing missing guards rotates epoch so another reader cannot reuse stale rows. */
    fun install(database: SupportSQLiteDatabase) {
        val ownsTransaction = !database.inTransaction()
        if (ownsTransaction) database.beginTransaction()
        try {
            installInsideTransaction(database)
            if (ownsTransaction) database.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) database.endTransaction()
        }
    }

    private fun installInsideTransaction(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS playback_catalog_revision " +
            "(id INTEGER NOT NULL, epoch TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))")
        database.execSQL("INSERT OR IGNORE INTO playback_catalog_revision (id, epoch, revision) VALUES (0, ?, 0)",
            arrayOf(UUID.randomUUID().toString()))
        var repaired = false
        for ((name, definition) in triggerDefinitions) {
            val exists = database.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = ?",
                arrayOf(name)).use { it.moveToFirst() }
            if (!exists) {
                database.execSQL(definition)
                repaired = true
            }
        }
        if (repaired) database.execSQL("UPDATE playback_catalog_revision SET epoch = ?, revision = 0 WHERE id = 0",
            arrayOf(UUID.randomUUID().toString()))
    }

    /** Use inside the same Room transaction as the projection. Untrusted shape disables the memo. */
    suspend fun readVerified(database: ShimmerDatabase): P1CatalogStamp? {
        check(database.inTransaction()) { "Catalog stamp requires authoritative transaction" }
        val context = currentCoroutineContext()
        context.ensureActive()
        // SQLite's persistent schema cookie changes even if a guard was removed and restored
        // entirely between reads. It is not connection-local PRAGMA data_version/total_changes.
        val schemaVersion = database.query(SimpleSQLiteQuery("PRAGMA schema_version")).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getLong(0)
        }
        if (schemaVersion !in 1 until Int.MAX_VALUE.toLong()) return null
        val names = triggerDefinitions.keys.toTypedArray()
        // Exact full SQL, not just a trigger count: missing or changed guards cannot certify freshness.
        val found = database.query(SimpleSQLiteQuery(
            "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name IN (" +
                names.joinToString(",") { "?" } + ")", names,
        )).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                context.ensureActive()
                if (triggerDefinitions[cursor.getString(0)] != cursor.getString(1)) return null
                count++
            }
            count
        }
        if (found != triggerDefinitions.size) return null
        return database.query(SimpleSQLiteQuery("SELECT id, epoch, revision, typeof(id), typeof(revision), typeof(epoch) FROM playback_catalog_revision")).use { cursor ->
            context.ensureActive()
            if (!cursor.moveToFirst() || cursor.getLong(0) != 0L) return null
            val epoch = cursor.getString(1)
            val revision = cursor.getLong(2)
            if (epoch.isNullOrBlank() || revision < 0 || revision == Long.MAX_VALUE ||
                cursor.getString(3) != "integer" || cursor.getString(4) != "integer" ||
                cursor.getString(5) != "text" || cursor.moveToNext()) return null
            context.ensureActive()
            P1CatalogStamp(epoch, revision, schemaVersion)
        }
    }
}
