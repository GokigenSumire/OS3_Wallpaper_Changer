package dev.abdus.apps.shimmer.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p2.FileTimestamps
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P1FileTimeRoomTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var owned: ShimmerDatabase? = null
    private fun memory() = Room.inMemoryDatabaseBuilder(app, ShimmerDatabase::class.java)
        .allowMainThreadQueries().addCallback(P1CatalogRevision.callback).build().also { owned = it }
    @After fun close() { owned?.close() }
    private suspend fun seed(db: ShimmerDatabase) {
        db.p1SourceDao().registerSource(P1SourceEntity("s", "file:///s", "DIRECTORY_TREE"))
        db.p1SourceDao().commitScan("s", listOf(P1ImageRecordEntity("i", "file:///s/a/i.jpg", "i.jpg", "a/i.jpg")), "APP_OWNED", "now")
    }
    @Test fun overlappingTreeGrantRoutesKeepTheSameOriginalFileTime() {
        val first = "content://com.android.externalstorage.documents/tree/primary%3APictures/document/primary%3APictures%2Fa%2Fi.jpg"
        val second = "content://com.android.externalstorage.documents/tree/primary%3APictures%2Fa/document/primary%3APictures%2Fa%2Fi.jpg"
        val index = P1FileTimeIndex(listOf(P1FileTimeEntity("s", first, 10, 20)))
        assertEquals(FileTimestamps(10, 20), index.file(second))
        assertEquals(FileTimestamps(10, 20), index.file(first))
        assertEquals(FileTimestamps(), index.file("content://com.android.externalstorage.documents/document/primary%3APictures%2Fother.jpg"))
    }
    @Test fun metadataFlowsThroughBothAuthoritativeReadersAndItemMapping() = runBlocking {
        val db = memory(); seed(db)
        db.p1FileTimeDao().put(listOf(P1FileTimeEntity("s", "file:///s/a/i.jpg", 10, 20),
            P1FileTimeEntity("s", "dir:", 1, 2), P1FileTimeEntity("s", "dir:a", 3, 4)))
        val dao = db.p1SourceDao().getEnabledPlaybackImages().single()
        val cursor = P1PlaybackCursorReader(db).getEnabledPlaybackImages().single()
        assertEquals(dao, cursor); assertEquals(FileTimestamps(10, 20), dao.fileTimes)
        assertEquals(listOf("", "a"), dao.directoryTimes.map { it.path })
        assertEquals(listOf(1L, 3L), dao.directoryTimes.map { it.times.createdMillis })
        val mapped = P1PlaybackItemMappingMemo().map(listOf(dao), emptySet()).single()
        assertEquals(dao.fileTimes, mapped.fileTimes); assertEquals(dao.directoryTimes, mapped.directoryTimes)
    }
    @Test fun newMetadataChangesCatalogRevisionAndRollbackRestoresEverything() = runBlocking {
        val db = memory(); seed(db)
        suspend fun stamp() = db.withTransaction { P1CatalogRevision.readVerified(db)!! }
        val reader = P1RevisionCatalogReader(db)
        val beforeRows = reader.getEnabledPlaybackImages()
        val original = stamp()
        db.p1FileTimeDao().put(listOf(P1FileTimeEntity("s", "file:///s/a/i.jpg", 1, 2)))
        val changed = stamp(); assertTrue(changed.revision > original.revision)
        val afterRows = reader.getEnabledPlaybackImages()
        assertNotSame(beforeRows, afterRows)
        assertEquals(1L, afterRows.single().fileTimes.createdMillis)
        try {
            db.withTransaction {
                db.p1FileTimeDao().put(listOf(P1FileTimeEntity("s", "file:///s/a/i.jpg", 90, 90)))
                error("simulate cancelled transaction")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(changed, stamp())
        assertEquals(1L, db.p1FileTimeDao().getForSource("s").single().createdMillis)
        db.p1SourceDao().deleteSource("s")
        assertTrue(db.p1FileTimeDao().getForSource("s").isEmpty())
    }
    @Test fun metadataRemainsSourceScopedAndUnknownDoesNotAcquireAnImportDate() = runBlocking {
        val db = memory(); seed(db)
        db.p1SourceDao().registerSource(P1SourceEntity("other", "file:///other", "DIRECTORY_TREE"))
        db.p1FileTimeDao().put(listOf(P1FileTimeEntity("other", "file:///s/a/i.jpg", 100, 100)))
        val image = db.p1SourceDao().getEnabledPlaybackImages().single()
        assertNull(image.fileTimes.createdMillis); assertNull(image.fileTimes.modifiedMillis)
    }
    @Test fun actualVersion8SchemaMigratesTo9AndPreservesSourceImageAndLink() = runBlocking {
        val name = "m89-${UUID.randomUUID().toString().take(8)}.db"
        if (File.separatorChar == '\\') assertTrue(app.getDatabasePath(name).absolutePath.length <= 240)
        val schema = JSONObject(File("schemas/dev.abdus.apps.shimmer.database.ShimmerDatabase/8.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (j in 0 until setup.length()) db.execSQL(setup.getString(j))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.use {
            val db = it.writableDatabase
            db.execSQL("INSERT INTO sources(id,rootUri,kind) VALUES ('s','file:///s','DIRECTORY_TREE')")
            db.execSQL("INSERT INTO image_records(id,uri,displayName,relativePath) VALUES ('i','file:///s/i.jpg','i.jpg','i.jpg')")
            db.execSQL("INSERT INTO source_image_links(sourceId,imageId,sortOrder) VALUES ('s','i',42)")
        }
        val db = Room.databaseBuilder(app, ShimmerDatabase::class.java, name).allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(ShimmerDatabase.MIGRATION_8_9, ShimmerDatabase.MIGRATION_9_10).addCallback(P1CatalogRevision.callback).build().also { owned = it }
        assertEquals(10, db.openHelper.writableDatabase.version)
        assertEquals(42, db.p1SourceDao().getLinks("s").single().sortOrder)
        assertEquals("i", db.p1SourceDao().getAllImages().single().id)
        assertTrue(db.p1FileTimeDao().getForSource("s").isEmpty())
        assertNull(db.p1SourceDao().getEnabledPlaybackImages().single().fileTimes.createdMillis)
        assertNotNull(db.withTransaction { P1CatalogRevision.readVerified(db) })
        db.p1FileTimeDao().put(listOf(P1FileTimeEntity("s", "file:///s/i.jpg", 2, 3)))
        assertEquals(2L, db.p1SourceDao().getEnabledPlaybackImages().single().fileTimes.createdMillis)
    }
}
