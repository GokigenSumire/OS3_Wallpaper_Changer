package dev.abdus.apps.shimmer.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p1.P1MediaTypes
import dev.abdus.apps.shimmer.p1.P1SourceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P1SourceMediaRoomTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(app, ShimmerDatabase::class.java)
        .allowMainThreadQueries().addCallback(P1CatalogRevision.callback).build()
    private val dao = db.p1SourceDao()
    @After fun close() = db.close()
    private suspend fun seed(id: String = "s", order: Long = 0) {
        dao.registerSource(P1SourceEntity(id, "file:///$id", "DIRECTORY_TREE", sourceOrder = order))
        dao.commitScan(id, listOf(
            P1ImageRecordEntity("photo", "file:///photo.jpg", "photo.jpg", "a/photo.jpg"),
            P1ImageRecordEntity("motion", "file:///motion.heic", "motion.heic", "a/motion.heic"),
            P1ImageRecordEntity("video", "file:///video.mp4", "video.mp4", "a/video.mp4"),
            P1ImageRecordEntity("unknown", "content://provider/no-extension", "no-extension", "b/no-extension"),
        ), "APP_OWNED", "now")
    }
    private suspend fun ids() = dao.getEnabledPlaybackImages().map { it.id }
    private suspend fun flags(images: Boolean, video: Boolean) {
        dao.setSourceMediaEnabled("s", false, images, "now")
        dao.setSourceMediaEnabled("s", true, video, "now")
    }
    @Test fun fourCombinationsKeepBothKindsByDefaultAndNeverDeleteRows() = runBlocking {
        seed(); assertEquals(setOf("photo", "motion", "video", "unknown"), ids().toSet())
        flags(true, false); assertEquals(setOf("photo", "motion"), ids().toSet())
        flags(false, true); assertEquals(listOf("video"), ids())
        flags(false, false); assertTrue(ids().isEmpty())
        assertEquals(4, dao.getSourceImages("s").size)
        assertEquals(listOf(0, 1, 2, 3), dao.getLinks("s").map { it.sortOrder })
        flags(true, true); assertEquals(4, ids().size)
    }
    @Test fun cursorPackedCompactAndReferenceQueriesAgreeWithFilters() = runBlocking {
        seed()
        for ((images, videos) in listOf(true to true, false to true, true to false, false to false)) {
            flags(images, videos)
            val expected = dao.getEnabledPlaybackLinks().map { it.id }
            assertEquals(expected, ids())
            assertEquals(expected, P1PlaybackCursorReader(db).getEnabledPlaybackImages().map { it.id })
            assertEquals(expected, dao.getEnabledPlaybackLinkRows().map { it.id })
            assertEquals(expected, dao.getPlaybackSourceLinkRows("s").map { it.id })
        }
    }
    @Test fun filtersApplyBeforeOverlappingSourceDeduplication() = runBlocking {
        seed(); seed("second", 1)
        dao.setSourceMediaEnabled("s", true, false, "now")
        val video = dao.getEnabledPlaybackImages().single { it.id == "video" }
        assertEquals("second", video.sourceId)
        dao.setSourceMediaEnabled("second", false, false, "now")
        assertEquals(setOf("s", "second"), dao.getEnabledPlaybackImages().map { it.sourceId }.toSet())
    }
    @Test fun ruleCanSelectStoppedSourceButHonoursTypesAndDirectoryExclusions() = runBlocking {
        seed(); flags(false, true)
        dao.setSourceEnabled("s", false, "now")
        assertTrue(ids().isEmpty())
        assertEquals(listOf("video"), dao.getRulePlaybackImages(listOf("s")).map { it.id })
        assertEquals(listOf("video"), dao.getRulePlaybackLinks(listOf("s")).map { it.id })
        assertEquals(listOf("video"), dao.getRulePlaybackLinkRows(listOf("s")).map { it.id })
        assertEquals(listOf("video"), P1PlaybackCursorReader(db).getRulePlaybackImages(listOf("s")).map { it.id })
        dao.setDirectoryExcluded("s", "a", true, "now")
        assertTrue(dao.getRulePlaybackImages(listOf("s")).isEmpty())
    }
    @Test fun changingOneFlagPreservesOtherFlagAndMasterSwitch() = runBlocking {
        seed(); dao.setSourceEnabled("s", false, "now")
        dao.setSourceMediaEnabled("s", false, false, "now")
        dao.setSourceMediaEnabled("s", true, false, "now")
        dao.setSourceMediaEnabled("s", false, true, "now")
        val state = dao.getSource("s")!!
        assertTrue(state.imagesEnabled); assertFalse(state.videosEnabled); assertFalse(state.enabled)
    }
    @Test fun sourceReselectionAndRescanDoNotResetTypeChoices() = runBlocking {
        seed(); flags(false, true)
        dao.registerSource(P1SourceEntity("s", "file:///s", "DIRECTORY_TREE"))
        seed()
        assertFalse(dao.getSource("s")!!.imagesEnabled)
        assertTrue(dao.getSource("s")!!.videosEnabled)
        assertEquals(listOf("video"), ids())
    }
    @Test fun persistentRevisionInvalidatesWarmCatalogAndRollbackRestoresPolicy() = runBlocking {
        seed(); val reader = P1RevisionCatalogReader(db)
        val original = reader.getEnabledPlaybackImages()
        flags(false, true)
        assertEquals(listOf("video"), reader.getEnabledPlaybackImages().map { it.id })
        try {
            db.withTransaction {
                flags(false, false)
                assertTrue(reader.getEnabledPlaybackImages().isEmpty())
                throw IllegalStateException("rollback")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("video"), reader.getEnabledPlaybackImages().map { it.id })
        assertEquals(4, original.size)
    }
    @Test fun unknownMetadataCannotOverwriteANewerUriOrKnownKind() = runBlocking {
        seed()
        assertEquals(0, dao.classifyUnknownMedia("unknown", "content://stale", P1MediaTypes.VIDEO))
        assertEquals(1, dao.classifyUnknownMedia("unknown", "content://provider/no-extension", P1MediaTypes.VIDEO))
        assertEquals(0, dao.classifyUnknownMedia("unknown", "content://provider/no-extension", P1MediaTypes.IMAGE))
        flags(false, true); assertEquals(setOf("video", "unknown"), ids().toSet())
    }
    @Test fun repositoryWritesPolicyWithoutChangingAnySourceLinks() = runBlocking {
        seed(); val links = dao.getLinks("s")
        val repository = P1SourceRepository(app, db)
        assertEquals(1, repository.setMediaEnabled("s", false, false))
        assertFalse(dao.getSource("s")!!.imagesEnabled)
        assertEquals(links, dao.getLinks("s"))
    }
    @Test fun realVersionNineDatabaseMigratesWithChoicesDefaultOnAndAllDataPreserved() = runBlocking {
        val name = "m116-${java.util.UUID.randomUUID().toString().take(8)}.db"
        val schema = org.json.JSONObject(java.io.File("schemas/dev.abdus.apps.shimmer.database.ShimmerDatabase/9.json").readText())
            .getJSONObject("database")
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (index in 0 until entities.length()) {
                            val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                            sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                            for (i in 0 until indices.length()) sql.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                    }
                    override fun onUpgrade(sql: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        helper.use {
            val sql = it.writableDatabase
            sql.execSQL("INSERT INTO sources(id,rootUri,kind,enabled) VALUES ('old','file:///old','DIRECTORY_TREE',0)")
            for ((id, name) in listOf("p" to "live.HEIC", "v" to "movie.MP4", "u" to "opaque")) {
                sql.execSQL("INSERT INTO image_records(id,uri,displayName,relativePath) VALUES (?,?,?,?)", arrayOf(id,"file:///$name",name,name))
                sql.execSQL("INSERT INTO source_image_links(sourceId,imageId,sortOrder) VALUES ('old',?,17)", arrayOf(id))
            }
        }
        val migrated = Room.databaseBuilder(app, ShimmerDatabase::class.java, name).allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(ShimmerDatabase.MIGRATION_9_10).addCallback(P1CatalogRevision.callback).build()
        try {
            assertEquals(10, migrated.openHelper.writableDatabase.version)
            val old = migrated.p1SourceDao().getSource("old")!!
            assertFalse(old.enabled); assertTrue(old.imagesEnabled); assertTrue(old.videosEnabled)
            assertEquals(mapOf("p" to "IMAGE", "v" to "VIDEO", "u" to "UNKNOWN"),
                migrated.p1SourceDao().getSourceImages("old").associate { it.id to it.mediaType })
            assertTrue(migrated.p1SourceDao().getLinks("old").all { it.sortOrder == 17 })
            assertEquals(3, migrated.p1SourceDao().getRulePlaybackImages(listOf("old")).size)
            assertNotNull(migrated.withTransaction { P1CatalogRevision.readVerified(migrated) })
        } finally { migrated.close(); app.deleteDatabase(name) }
    }
    @Test fun slowMimeLookupCannotMakeAnOlderClickOverwriteTheLaterClick() = runBlocking {
        seed()
        dao.updateImage("unknown", "content://slow/no-extension", "no-extension", "b/no-extension",
            null, null, null, "COMMITTED", null)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri): String? {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return null
            }
            override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
            override fun insert(u: android.net.Uri, v: android.content.ContentValues?) = null
            override fun delete(u: android.net.Uri, s: String?, a: Array<out String>?) = 0
            override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
        }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("slow", provider)
        val repository = P1SourceRepository(app, db)
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repository.setMediaEnabled("s", false, false)
        }
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repository.setMediaEnabled("s", false, true)
        }
        try {
            // 第二个请求不应越过尚未完成的第一次MIME读取。
            assertNull(kotlinx.coroutines.withTimeoutOrNull(150) { second.await() })
        } finally { release.countDown() }
        first.await(); second.await()
        assertTrue(dao.getSource("s")!!.imagesEnabled)
        assertTrue(dao.getSource("s")!!.videosEnabled)
    }
}
