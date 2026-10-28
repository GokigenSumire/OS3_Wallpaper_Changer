package dev.abdus.apps.shimmer.database

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Device-side migration contract. It constructs the historical table shapes explicitly, so it can
 * execute without generated schema fixtures. APK upgrade and OEM/provider behavior remain separate
 * target-device tests in the P1 card.
 */
@RunWith(AndroidJUnit4::class)
class P1MigrationTest {
    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ShimmerDatabase::class.java,
    )

    @Test
    fun roomHelperValidatesGeneratedV3ToV5Schema() {
        val name = "p1-room-schema-v3"
        val v3 = migrationHelper.createDatabase(name, 3)
        v3.execSQL("INSERT INTO folders(id, uri, isEnabled, createdAt) VALUES (1, 'content://fixture/tree', 1, 'fixture')")
        v3.execSQL("INSERT INTO images(id, folderId, uri, favoriteRank, width, height, fileSize) VALUES (7, 1, 'content://fixture/image', 0, 100, 200, 12)")
        v3.close()

        val v5 = migrationHelper.runMigrationsAndValidate(
            name,
            5,
            true,
            ShimmerDatabase.MIGRATION_3_4,
            ShimmerDatabase.MIGRATION_4_5,
        )
        v5.query("SELECT COUNT(*) FROM sources").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        v5.query("SELECT COUNT(*) FROM image_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        v5.close()
    }

    @Test
    fun migrateV3ToV5PreservesLegacyAndSourceGraph() {
        val helper = openHelper("p1-migration-v3")
        val database = helper.writableDatabase
        database.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY NOT NULL, uri TEXT NOT NULL, isEnabled INTEGER NOT NULL, lastScannedAt TEXT, createdAt TEXT, lastPickedAt TEXT)")
        database.execSQL("CREATE TABLE images (id INTEGER PRIMARY KEY NOT NULL, folderId INTEGER NOT NULL, uri TEXT NOT NULL, lastShownAt TEXT, createdAt TEXT, favoriteRank INTEGER NOT NULL, width INTEGER, height INTEGER, fileSize INTEGER)")
        database.execSQL("INSERT INTO folders(id, uri, isEnabled, createdAt) VALUES (1, 'content://tree/pictures', 1, 't0')")
        database.execSQL("INSERT INTO folders(id, uri, isEnabled, createdAt) VALUES (2, 'content://tree/other', 1, 't0')")
        database.execSQL("INSERT INTO images(id, folderId, uri, favoriteRank, width, height, fileSize) VALUES (7, 1, 'content://image/one', 0, 100, 200, 12)")
        // A historical v4 database could contain duplicate URI rows. Migration must retain both
        // source associations before enforcing the v5 global URI identity.
        database.execSQL("INSERT INTO images(id, folderId, uri, favoriteRank, width, height, fileSize) VALUES (8, 2, 'content://image/one', 0, 100, 200, 12)")

        ShimmerDatabase.MIGRATION_3_4.migrate(database)
        ShimmerDatabase.MIGRATION_4_5.migrate(database)

        database.query("SELECT rootUri FROM sources WHERE id = 'legacy-folder:1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("content://tree/pictures", cursor.getString(0))
        }
        database.query("SELECT imageId FROM source_image_links WHERE sourceId = 'legacy-folder:1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-image:7", cursor.getString(0))
        }
        database.query("SELECT COUNT(*) FROM image_records WHERE uri = 'content://image/one'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM source_image_links WHERE imageId = 'legacy-image:7'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        database.query("PRAGMA index_list(image_records)").use { cursor ->
            var uniqueUriIndex = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "index_image_records_uri") uniqueUriIndex = cursor.getInt(2) == 1
            }
            assertTrue(uniqueUriIndex)
        }
        database.query("PRAGMA table_info(sources)").use { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(1)
            assertTrue(names.containsAll(listOf("scanState", "permissionState", "lastScanAt", "lastError")))
        }
        helper.close()
    }

    @Test
    fun migrateV6ToV7AddsDirectoryExclusionsAndLeavesExistingPlaybackEnabled() {
        val name = "p1-room-schema-v6"
        val v6 = migrationHelper.createDatabase(name, 6)
        v6.execSQL(
            "INSERT INTO sources(id, rootUri, kind, includeSubdirectories, enabled, scanState," +
                " permissionState, sourceOrder) VALUES ('s1', 'content://tree/s1'," +
                " 'DIRECTORY_TREE', 1, 1, 'IDLE', 'GRANTED', 0)",
        )
        v6.execSQL(
            "INSERT INTO image_records(id, uri, displayName, relativePath, metadataState)" +
                " VALUES ('i1', 'content://image/1', '1.jpg', '100%_合集/1.jpg', 'COMMITTED')",
        )
        v6.execSQL("INSERT INTO source_image_links(sourceId, imageId, sortOrder) VALUES ('s1', 'i1', 0)")
        v6.close()

        val v7 = migrationHelper.runMigrationsAndValidate(name, 7, true, ShimmerDatabase.MIGRATION_6_7)

        // An upgraded database holds no exclusion rows, so every folder keeps playing.
        v7.query("SELECT COUNT(*) FROM source_directory_exclusions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        v7.query("PRAGMA table_info(source_directory_exclusions)").use { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(1)
            assertEquals(setOf("sourceId", "directoryPath", "updatedAt"), names)
        }

        // Folder names really do contain % and _; the projection compares them literally.
        v7.execSQL(
            "INSERT INTO source_directory_exclusions(sourceId, directoryPath) VALUES ('s1', '100%_合集')",
        )
        assertEquals(0, remainingImages(v7))
        v7.execSQL("DELETE FROM source_directory_exclusions")
        v7.execSQL(
            "INSERT INTO source_directory_exclusions(sourceId, directoryPath) VALUES ('s1', '100X合集')",
        )
        assertEquals(1, remainingImages(v7))
        v7.close()
    }

    /** The exclusion half of the enabled-playback projection, kept in sync with the DAO query. */
    @Test
    fun roomHelperValidatesV7ToV8AndPreservesCatalogRows() {
        val name = "p9-room-schema-v7-v8"
        val old = migrationHelper.createDatabase(name, 7)
        old.execSQL("INSERT INTO sources(id,rootUri,kind) VALUES ('s','content://tree/s','DIRECTORY_TREE')")
        old.execSQL("INSERT INTO image_records(id,uri,displayName,relativePath) VALUES ('i','content://image/i','1.jpg','child/1.jpg')")
        old.execSQL("INSERT INTO source_image_links(sourceId,imageId,sortOrder) VALUES ('s','i',123)")
        old.execSQL("INSERT INTO source_directory_exclusions(sourceId,directoryPath) VALUES ('s','excluded')")
        old.close()
        val upgraded = migrationHelper.runMigrationsAndValidate(name, 8, true, ShimmerDatabase.MIGRATION_7_8)
        upgraded.query("SELECT sortOrder FROM source_image_links WHERE sourceId='s' AND imageId='i'").use {
            assertTrue(it.moveToFirst()); assertEquals(123, it.getInt(0))
        }
        assertEquals(1, remainingImages(upgraded))
        val before = upgraded.query("SELECT revision FROM playback_catalog_revision WHERE id=0").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        upgraded.execSQL("UPDATE sources SET enabled=0 WHERE id='s'")
        upgraded.query("SELECT revision FROM playback_catalog_revision WHERE id=0").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getLong(0) > before)
        }
        upgraded.close()
    }

    private fun remainingImages(database: SupportSQLiteDatabase): Int {
        val sql = "SELECT COUNT(*) FROM image_records i" +
            " JOIN source_image_links l ON l.imageId = i.id" +
            " JOIN sources s ON s.id = l.sourceId" +
            " WHERE i.metadataState = 'COMMITTED' AND s.enabled = 1" +
            " AND NOT EXISTS (SELECT 1 FROM source_directory_exclusions x WHERE x.sourceId = s.id" +
            " AND substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/')"
        return database.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun openHelper(name: String): SupportSQLiteOpenHelper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("$name-${System.nanoTime()}")
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }
}
