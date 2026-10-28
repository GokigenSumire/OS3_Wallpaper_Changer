package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual SQLite BLOB extraction, fallback TEXT reads and complete old SQL equivalence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1PlaybackPackedRoomTest {
    private lateinit var database: ShimmerDatabase
    private lateinit var dao: P1SourceDao
    private val reads = ConcurrentLinkedQueue<Read>()

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, args ->
                val value = sql.trim()
                if (value.startsWith("SELECT") && (value.contains("FROM sources") ||
                    value.contains("FROM source_image_links l") || value.contains("FROM source_directory_exclusions"))) {
                    reads.add(Read(value, args.toList(), database.inTransaction()))
                }
            }, Executor { it.run() }).build()
        dao = database.p1SourceDao()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun emptyExclusionsUsePackedQueryWithoutCorrelatedSubqueryAndNoTextFallback() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        reads.clear()
        val result = dao.getEnabledPlaybackImages()
        assertEquals(listOf("one", "two"), result.map { it.id })
        val query = reads.single { it.sql.startsWith("SELECT CAST(printf(") }
        assertFalse(query.sql.contains("NOT EXISTS"))
        assertTrue(query.sql.contains("i.metadataState = 'COMMITTED'"))
        assertTrue(query.sql.contains("ORDER BY l.sortOrder ASC, l.imageId ASC"))
        assertFalse(reads.any { it.sql.startsWith("SELECT i.id AS id") })
        assertEquals(3, reads.size)
        assertTrue(reads.all { it.inTransaction })
        assertEquivalent()
    }

    @Test
    fun nonemptyExclusionsUseTheSameLiteralSqlPredicateWithoutLikeWildcards() = runBlocking {
        seed("a", listOf(image("excluded", "100%_图/😀/x.png"), image("keep", "100X图/x.png")))
        dao.setDirectoryExcluded("a", "100%_图", true, null)
        reads.clear()
        assertEquals(listOf("keep"), dao.getEnabledPlaybackImages().map { it.id })
        val query = reads.single { it.sql.startsWith("SELECT CAST(printf(") }
        assertTrue(query.sql.contains("NOT EXISTS"))
        assertTrue(query.sql.contains("substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'"))
        assertFalse(query.sql.contains(" LIKE "))
        assertTrue(reads.all { it.inTransaction })
        assertEquivalent()
    }

    @Test
    fun exclusionsAreReadFreshEveryActionAndBranchChangesImmediately() = runBlocking {
        seed("a", listOf(image("x", "1/x.png"), image("y", "10/y.png")))
        repeat(3) { index ->
            reads.clear()
            assertEquals(2, dao.getEnabledPlaybackImages().size)
            assertEquals(1, reads.count { it.sql.startsWith("SELECT * FROM source_directory_exclusions") })
            assertFalse(reads.single { it.sql.startsWith("SELECT CAST(printf(") }.sql.contains("NOT EXISTS"))
            dao.setDirectoryExcluded("a", "1", true, index.toString())
            reads.clear()
            assertEquals(listOf("y"), dao.getEnabledPlaybackImages().map { it.id })
            assertTrue(reads.single { it.sql.startsWith("SELECT CAST(printf(") }.sql.contains("NOT EXISTS"))
            assertEquivalent()
            dao.setDirectoryExcluded("a", "1", false, null)
        }
        assertEquivalent()
    }

    @Test
    fun everyByteLengthFieldEqualsOldSqlForUnicodeAndEmbeddedProtocolCharacters() = runBlocking {
        val record = P1ImageRecordEntity("图😀|:ID", "content://图😀/%2F/'|", "é\u0301\uFEFF|:图.png", "目录/𐀀/\uE000/图.png")
        seed("a", listOf(record))
        val decoded = P1PlaybackPackedProjection.decode(dao.getPlaybackSourcePackedLinkRowsWithoutExclusions("a"))!!
        assertEquals(dao.getPlaybackSourceLinkRows("a"), decoded)
        assertEquivalent()
    }

    @Test
    fun everyNulFieldTriggersWholeSourceReferenceReadInsideTheTransaction() = runBlocking {
        for (field in 0..3) {
            val fields = listOf("id-$field", "content://images/$field", "图$field.png", "目录/$field.png")
                .toMutableList().also { it[field] += "\u0000after" }
            seed("source-$field", listOf(P1ImageRecordEntity(fields[0], fields[1], fields[2], fields[3]), image("good-$field")))
            assertNull(P1PlaybackPackedProjection.decode(dao.getPlaybackSourcePackedLinkRowsWithoutExclusions("source-$field")))
        }
        reads.clear()
        val actual = dao.getEnabledPlaybackImages()
        assertEquals(8, actual.size)
        assertEquals(4, reads.count { it.sql.startsWith("SELECT i.id AS id") })
        assertTrue(reads.all { it.inTransaction })
        assertEquivalent()
        assertRuleEquivalent(listOf("source-0", "source-2"))
    }

    @Test
    fun malformedUtf8InEachSqlTextFieldFallsBackToActualPlatformTextDecoding() = runBlocking {
        seed("base", listOf(image("regular")))
        val expressions = listOf("CAST(X'61C08062' AS TEXT)", "CAST(X'61EDA08062' AS TEXT)",
            "CAST(X'61F490808062' AS TEXT)", "CAST(X'61FF62' AS TEXT)")
        for (field in listOf("uri", "displayName", "relativePath")) for ((index, expression) in expressions.withIndex()) {
            database.openHelper.writableDatabase.execSQL("UPDATE image_records SET $field = $expression WHERE id = 'regular'")
            assertNull(P1PlaybackPackedProjection.decode(dao.getPlaybackSourcePackedLinkRowsWithoutExclusions("base")))
            reads.clear()
            assertEquals(1, dao.getEnabledPlaybackImages().size)
            assertEquals(1, reads.count { it.sql.startsWith("SELECT i.id AS id") })
            assertTrue(reads.all { it.inTransaction })
            assertEquivalent()
            assertRuleEquivalent(listOf("base"))
            dao.updateImage("regular", "content://regular/$index", "regular.png", "regular.png", null, null, null, "COMMITTED", null)
        }
    }

    @Test
    fun malformedSqlTextIdentityUsesTheReferenceRatherThanAReplacementIdentityGuess() = runBlocking {
        dao.registerSource(P1SourceEntity("a", "content://root/a", "DIRECTORY_TREE"))
        database.openHelper.writableDatabase.execSQL("INSERT INTO image_records(id,uri,displayName,relativePath,metadataState) " +
            "VALUES(CAST(X'61FF62' AS TEXT),'content://raw/id','name.png','path.png','COMMITTED')")
        database.openHelper.writableDatabase.execSQL("INSERT INTO source_image_links(sourceId,imageId,sortOrder) " +
            "VALUES('a',CAST(X'61FF62' AS TEXT),0)")
        assertNull(P1PlaybackPackedProjection.decode(dao.getPlaybackSourcePackedLinkRowsWithoutExclusions("a")))
        assertEquivalent()
        assertRuleEquivalent(listOf("a"))
    }

    @Test
    fun emptyStringsAndUnicodeScalarEdgesAreNotDroppedByPackedDecode() = runBlocking {
        seed("empty", listOf(P1ImageRecordEntity("", "", "", "")))
        seed("edge", listOf(image("\u0001\u007f\u0080\u07ff\u0800\uD7FF\uE000\uFFFF𐀀􏿿")))
        assertEquals(2, dao.getEnabledPlaybackImages().size)
        assertEquivalent()
    }

    @Test
    fun everyUnicodeNoncharacterUsesActualSqlTextFallbackWithoutDroppingOrGuessingIdentity() = runBlocking {
        val points = (0xfdd0..0xfdef).toList() + (0..16).flatMap { listOf((it shl 16) + 0xfffe, (it shl 16) + 0xffff) }
        for (point in points) {
            val value = String(Character.toChars(point))
            seed("source-$point", listOf(P1ImageRecordEntity("id-$point$value", "content://image/$point$value",
                "name$point$value.png", "path/$point$value.png")))
            assertNull(P1PlaybackPackedProjection.decode(dao.getPlaybackSourcePackedLinkRowsWithoutExclusions("source-$point")))
        }
        reads.clear()
        assertEquals(points.size, dao.getEnabledPlaybackImages().size)
        assertEquals(points.size, reads.count { it.sql.startsWith("SELECT i.id AS id") })
        assertTrue(reads.all { it.inTransaction })
        assertEquivalent()
        assertRuleEquivalent(listOf("source-65535", "source-65534", "source-64976"))
    }

    @Test
    fun sharedRecordsKeepFirstCompleteSortKeyAndOwnerAfterSourceStop() = runBlocking {
        val shared = image("shared")
        seed("a", listOf(image("a"), shared))
        seed("b", listOf(shared, image("b")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 5")
        dao.updateLinkOrder("a", "shared", 20)
        assertEquals("b", dao.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        assertEquivalent()
        dao.setSourceEnabled("b", false, null)
        assertEquals("a", dao.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        assertEquivalent()
        dao.setSourceEnabled("b", true, null)
        assertEquivalent()
    }

    @Test
    fun ruleTargetsDisabledSourcesButStillFreshlyReadsTheirExclusions() = runBlocking {
        seed("a", listOf(image("x", "skip/x.png"), image("a")))
        seed("b", listOf(image("b")))
        dao.setSourceEnabled("a", false, null)
        val ids = listOf("a", "b", "missing", "a")
        assertRuleEquivalent(ids)
        dao.setDirectoryExcluded("a", "skip", true, null)
        reads.clear()
        assertEquals(listOf("a", "b"), dao.getRulePlaybackImages(ids).map { it.id })
        assertEquals(5, reads.size)
        assertTrue(reads.all { it.inTransaction })
        assertRuleEquivalent(ids)
    }

    @Test
    fun exactEnabledOnePredicateAndMetadataCommittedAreNotRelaxed() = runBlocking {
        seed("a", listOf(image("a"), image("failed").copy(metadataState = "FAILED")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET enabled = 2")
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertEquals(listOf("a"), dao.getRulePlaybackImages(listOf("a")).map { it.id })
        assertEquivalent()
        assertRuleEquivalent(listOf("a"))
    }

    @Test
    fun sourceAndLinkIntegerOrderingStays64BitBeforeLegacyProjectionConversion() = runBlocking {
        seed("😀", listOf(image("huge")))
        seed("\uE000", listOf(image("small"), image("tie")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 9223372036854775807")
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = 2147483648 WHERE imageId = 'huge'")
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = 2147483647 WHERE imageId IN ('small','tie')")
        assertEquals(listOf("small", "tie", "huge"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquals(Int.MIN_VALUE, dao.getEnabledPlaybackImages().last().sortOrder)
        assertEquivalent()
    }

    @Test
    fun currentImageAndSourceMetadataAreFullyReadAgainOnEveryCall() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        dao.getEnabledPlaybackImages()
        dao.updateImage("one", "content://changed/😀", "改名.png", "新/路/径.png", null, null, null, "COMMITTED", null)
        dao.updateSourceDefinition("a", "content://new/root\u0000suffix", "MULTIPLE_DOCUMENTS", false, true, null)
        dao.reorderSource("a", listOf("two", "one"))
        val result = dao.getEnabledPlaybackImages()
        assertEquals(listOf("two", "one"), result.map { it.id })
        assertEquals("content://changed/😀", result.last().uri)
        assertEquals("改名.png", result.last().displayName)
        assertEquals("新/路/径.png", result.last().relativePath)
        assertEquals("content://new/root\u0000suffix", result.last().sourceRootUri)
        assertEquivalent()
    }

    @Test
    fun emptyStoppedMissingAndDeletedSourcesReturnNoPartialOrDefaultCatalog() = runBlocking {
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertTrue(dao.getRulePlaybackImages(emptyList()).isEmpty())
        assertTrue(dao.getRulePlaybackImages(listOf("missing")).isEmpty())
        seed("a", listOf(image("kept")))
        dao.setSourceEnabled("a", false, null)
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        dao.deleteSource("a")
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertTrue(dao.getRulePlaybackImages(listOf("a")).isEmpty())
        assertEquals(1, dao.getAllImages().size)
    }

    @Test
    fun temporaryScanFailureNeverRemovesCommittedRowsOrHidesTheirActualReads() = runBlocking {
        seed("a", listOf(image("kept")))
        dao.updateSourceState("a", "METADATA_FAILURE", "DENIED", null, "temporary", null)
        reads.clear()
        assertEquals(listOf("kept"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquals(1, reads.count { it.sql.startsWith("SELECT CAST(printf(") })
        assertEquivalent()
    }

    @Test
    fun large11890RealRoomPackedCatalogMatchesBothExactOldQueriesAndUsesVersionSeven() = runBlocking {
        for (source in 0..3) {
            seed("source-$source", (source until 11890 step 4).map { image("id-$it", "目录/😀/$it.png") })
        }
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 8")
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = sortOrder / 3")
        dao.setDirectoryExcluded("source-1", "目录/不存在", true, null)
        assertEquals(11890, dao.getEnabledPlaybackImages().size)
        assertEquivalent()
        assertRuleEquivalent(listOf("source-3", "source-1"))
        assertEquals(8, database.openHelper.readableDatabase.version)
    }

    @Test
    fun deterministicSharedUnicodeTieAndExclusionCorpusMatchesAfterEveryMutation() = runBlocking {
        val random = Random(48)
        val images = (0 until 100).map { image("图😀-$it", if (it % 2 == 0) "100%_子/$it.png" else "100X子/$it.png") }
        for (source in 0..4) {
            val id = "source-$source"
            seed(id, images.filter { random.nextBoolean() })
            database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = ? WHERE id = ?", arrayOf<Any>(source % 2, id))
            for (link in dao.getLinks(id)) dao.updateLinkOrder(id, link.imageId, random.nextInt(-2, 4))
        }
        assertEquivalent()
        for (source in 0..4) {
            dao.setDirectoryExcluded("source-$source", "100%_子", true, null)
            assertEquivalent()
            assertRuleEquivalent(listOf("source-4", "source-1", "source-1", "missing"))
            dao.setSourceEnabled("source-$source", false, null)
            assertEquivalent()
            dao.setDirectoryExcluded("source-$source", "100%_子", false, null)
            assertRuleEquivalent(listOf("source-4", "source-1"))
        }
    }

    private suspend fun assertEquivalent() {
        val result = dao.getEnabledPlaybackImages()
        assertEquals(dao.getEnabledPlaybackLinks().distinctBy { it.id }, result)
        assertEquals(P1PlaybackProjection.resolve(dao.getSources(), dao.getEnabledPlaybackLinkRows()), result)
    }

    private suspend fun assertRuleEquivalent(ids: List<String>) {
        val result = dao.getRulePlaybackImages(ids)
        assertEquals(dao.getRulePlaybackLinks(ids).distinctBy { it.id }, result)
        assertEquals(P1PlaybackProjection.resolve(dao.getSources(), dao.getRulePlaybackLinkRows(ids)), result)
    }

    private suspend fun seed(source: String, images: List<P1ImageRecordEntity>) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.insertImages(images)
        dao.insertLinks(images.mapIndexed { index, image -> P1SourceImageLinkEntity(source, image.id, index) })
    }

    private fun image(id: String, path: String = "$id.png") =
        P1ImageRecordEntity(id, "content://images/$id", path.substringAfterLast('/'), path)

    private data class Read(val sql: String, val args: List<Any?>, val inTransaction: Boolean)
}
