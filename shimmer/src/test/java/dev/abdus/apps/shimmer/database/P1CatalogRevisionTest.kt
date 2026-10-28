package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1CatalogRevisionTest {
    private lateinit var database: ShimmerDatabase
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(app, ShimmerDatabase::class.java)
            .allowMainThreadQueries().addCallback(P1CatalogRevision.callback).build()
        database.openHelper.writableDatabase
    }
    @After fun close() { database.close() }
    private suspend fun stamp(): P1CatalogStamp? = database.withTransaction { P1CatalogRevision.readVerified(database) }

    @Test fun newDatabaseHasExactTwelveGuardsAndPersistentIdentity() = runBlocking {
        val first = stamp()!!
        assertEquals(12, P1CatalogRevision.triggerDefinitions.size)
        P1CatalogRevision.install(database.openHelper.writableDatabase)
        assertEquals(first, stamp())
        assertEquals(8, database.openHelper.readableDatabase.version)
    }

    @Test fun everyInsertUpdateAndDeleteOnAllFourTablesChangesRevision() = runBlocking {
        val statements = listOf(
            "INSERT INTO sources(id,rootUri,kind) VALUES ('s','file:///s','DIRECTORY_TREE')",
            "UPDATE sources SET enabled=0 WHERE id='s'",
            "INSERT INTO image_records(id,uri,displayName,relativePath) VALUES ('i','file:///i','i','i')",
            "UPDATE image_records SET displayName='new' WHERE id='i'",
            "INSERT INTO source_image_links(sourceId,imageId) VALUES ('s','i')",
            "UPDATE source_image_links SET sortOrder=2 WHERE sourceId='s'",
            "INSERT INTO source_directory_exclusions(sourceId,directoryPath) VALUES ('s','child')",
            "UPDATE source_directory_exclusions SET directoryPath='other' WHERE sourceId='s'",
            "DELETE FROM source_directory_exclusions WHERE sourceId='s'",
            "DELETE FROM source_image_links WHERE sourceId='s'",
            "DELETE FROM image_records WHERE id='i'",
            "DELETE FROM sources WHERE id='s'",
        )
        for (sql in statements) {
            val before = stamp()!!
            database.openHelper.writableDatabase.execSQL(sql)
            val after = stamp()!!
            assertEquals(before.epoch, after.epoch)
            assertEquals(sql, before.revision + 1, after.revision)
        }
    }

    @Test fun cascadesAndRollbackShareTheSameRevisionTransaction() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO sources(id,rootUri,kind) VALUES ('s','file:///s','DIRECTORY_TREE')")
        sql.execSQL("INSERT INTO image_records(id,uri,displayName,relativePath) VALUES ('i','file:///i','i','i')")
        sql.execSQL("INSERT INTO source_image_links(sourceId,imageId) VALUES ('s','i')")
        sql.execSQL("INSERT INTO source_directory_exclusions(sourceId,directoryPath) VALUES ('s','child')")
        val before = stamp()!!
        try { database.withTransaction { sql.execSQL("DELETE FROM sources WHERE id='s'"); error("rollback") } }
        catch (expected: IllegalStateException) { assertEquals("rollback", expected.message) }
        assertEquals(before, stamp())
        sql.execSQL("DELETE FROM sources WHERE id='s'")
        assertEquals(before.revision + 3, stamp()!!.revision)
        assertEquals(1, database.p1SourceDao().getAllImages().size)
    }

    @Test fun missingOrChangedTriggerCannotCertifyFreshness() = runBlocking {
        val sql = database.openHelper.writableDatabase
        val name = P1CatalogRevision.triggerDefinitions.keys.first()
        sql.execSQL("DROP TRIGGER $name")
        assertNull(stamp())
        sql.execSQL("CREATE TRIGGER $name AFTER INSERT ON sources BEGIN SELECT 1; END")
        assertNull(stamp())
    }

    @Test fun repairingMissingGuardRotatesEpochAtomicallyAndLeavesSourceIntact() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO sources(id,rootUri,kind) VALUES ('s','file:///s','DIRECTORY_TREE')")
        val before = stamp()!!
        sql.execSQL("DROP TRIGGER ${P1CatalogRevision.triggerDefinitions.keys.first()}")
        P1CatalogRevision.install(sql)
        val after = stamp()!!
        assertNotEquals(before.epoch, after.epoch)
        assertEquals(0L, after.revision)
        assertNotNull(database.p1SourceDao().getSource("s"))
    }

    @Test fun missingDuplicateNegativeSaturatedAndNonIntegerMarkersDisableMemo() = runBlocking {
        val sql = database.openHelper.writableDatabase
        val original = stamp()!!
        for (value in listOf("-1", "9223372036854775807", "1.5", "'invalid'")) {
            sql.execSQL("UPDATE playback_catalog_revision SET revision=$value WHERE id=0")
            assertNull(value, stamp())
        }
        sql.execSQL("UPDATE playback_catalog_revision SET revision=0 WHERE id=0")
        sql.execSQL("INSERT INTO playback_catalog_revision(id,epoch,revision) VALUES(1,'extra',0)")
        assertNull(stamp())
        sql.execSQL("DELETE FROM playback_catalog_revision WHERE id=1")
        sql.execSQL("DELETE FROM playback_catalog_revision WHERE id=0")
        assertNull(stamp())
        P1CatalogRevision.install(sql)
        assertNotEquals(original.epoch, stamp()!!.epoch)
    }

    @Test fun saturationDoesNotOverflowOrPretendThatFutureWritesAreFresh() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("UPDATE playback_catalog_revision SET revision=9223372036854775806 WHERE id=0")
        sql.execSQL("INSERT INTO sources(id,rootUri,kind) VALUES ('s','file:///s','DIRECTORY_TREE')")
        assertNull(stamp())
        sql.execSQL("UPDATE sources SET enabled=0")
        sql.query("SELECT revision,typeof(revision) FROM playback_catalog_revision").use {
            assertTrue(it.moveToFirst()); assertEquals(Long.MAX_VALUE, it.getLong(0)); assertEquals("integer", it.getString(1))
        }
    }

    @Test fun integerSingletonIdentityMustNotTruncateLong64ToZero() = runBlocking {
        val sql = database.openHelper.writableDatabase
        for (id in listOf(4294967296L, -4294967296L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            sql.execSQL("UPDATE playback_catalog_revision SET id=?", arrayOf(id))
            assertNull(stamp())
        }
    }

    @Test fun invalidAndMaximumSchemaCookiesCannotCertifyMemo() = runBlocking {
        val sql = database.openHelper.writableDatabase
        for (version in listOf(0, -1, Int.MAX_VALUE)) {
            sql.execSQL("PRAGMA schema_version=$version")
            assertNull(stamp())
        }
    }

    @Test fun migrationRunsAgainstActualExportedV7SchemaAndRoomValidatesV8() = runBlocking {
        val name = "v51-migrate-${UUID.randomUUID()}"
        val schema = JSONObject(File("schemas/dev.abdus.apps.shimmer.database.ShimmerDatabase/7.json").readText())
            .getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indexes = entity.getJSONArray("indices")
                        for (j in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase.execSQL("INSERT INTO sources(id,rootUri,kind) VALUES ('s','file:///s','DIRECTORY_TREE')")
        helper.writableDatabase.execSQL("INSERT INTO image_records(id,uri,displayName,relativePath) VALUES ('i','file:///i','i','i')")
        helper.writableDatabase.execSQL("INSERT INTO source_image_links(sourceId,imageId,sortOrder) VALUES ('s','i',42)")
        helper.close()
        val migrated = Room.databaseBuilder(app, ShimmerDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(ShimmerDatabase.MIGRATION_7_8).addCallback(P1CatalogRevision.callback).build()
        try {
            assertEquals(8, migrated.openHelper.writableDatabase.version)
            assertEquals(42, migrated.p1SourceDao().getLinks("s").single().sortOrder)
            assertEquals("i", migrated.p1SourceDao().getAllImages().single().id)
            assertNotNull(migrated.withTransaction { P1CatalogRevision.readVerified(migrated) })
        } finally { migrated.close() }
    }
}
