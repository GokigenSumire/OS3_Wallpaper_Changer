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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual generated Room reads, exact reference equivalence, and transaction checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1PlaybackSourceRoomTest {
    private lateinit var database: ShimmerDatabase
    private lateinit var dao: P1SourceDao
    private val reads = ConcurrentLinkedQueue<Read>()

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, args ->
                val normalized = sql.trim()
                if (normalized == "SELECT * FROM sources ORDER BY createdAt, id" ||
                    normalized == "SELECT * FROM sources WHERE enabled = 1 ORDER BY createdAt, id" ||
                    normalized.startsWith("SELECT i.id AS id") || normalized.startsWith("SELECT CAST(printf(") ||
                    normalized.startsWith("SELECT * FROM source_directory_exclusions WHERE sourceId")) {
                    reads.add(Read(normalized, args.toList(), database.inTransaction()))
                }
            }, Executor { it.run() })
            .build()
        dao = database.p1SourceDao()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun oneSourceProductionMatchesBothOldQueriesFieldForField() = runBlocking {
        seed("source", listOf(image("z", "目录/z.png"), image("a", "目录/a.png")))
        dao.updateLinkOrder("source", "z", -5)
        assertEquivalent()
        val rows = dao.getPlaybackSourceLinkRows("source")
        assertEquals(listOf("z", "a"), rows.map { it.id })
        assertEquals(listOf(-5L, 1L), rows.map { it.sortOrder })
    }

    @Test
    fun equalSourceOrdersInterleaveLinkOrdersBeforeSourceIdAndImageId() = runBlocking {
        seed("b", listOf(image("b0"), image("b4-z"), image("b4-a")))
        seed("a", listOf(image("a1"), image("a4-z"), image("a4-a")))
        setSourceOrder("a", 9)
        setSourceOrder("b", 9)
        for (id in listOf("a4-z", "a4-a")) dao.updateLinkOrder("a", id, 4)
        for (id in listOf("b4-z", "b4-a")) dao.updateLinkOrder("b", id, 4)
        dao.updateLinkOrder("a", "a1", 1)
        val result = dao.getEnabledPlaybackImages()
        assertEquals(listOf("b0", "a1", "a4-a", "a4-z", "b4-a", "b4-z"), result.map { it.id })
        assertEquivalent()
    }

    @Test
    fun sourceIdUtf8BinaryOrderMatchesRealSqlForSupplementaryAndBmpCharacters() = runBlocking {
        seed("😀", listOf(image("supplementary")))
        seed("\uE000", listOf(image("bmp")))
        seed("a", listOf(image("ascii")))
        for (source in dao.getSources()) setSourceOrder(source.id, 0)
        val result = dao.getEnabledPlaybackImages()
        assertEquals(listOf("ascii", "bmp", "supplementary"), result.map { it.id })
        assertEquivalent()
    }

    @Test
    fun sameSourceImageIdUtf8BinaryTieBreakRemainsTheActualSqlOrder() = runBlocking {
        seed("source", listOf(image("😀"), image("\uE000"), image("a")))
        for (link in dao.getLinks("source")) dao.updateLinkOrder("source", link.imageId, 5)
        assertEquals(listOf("a", "\uE000", "😀"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
    }

    @Test
    fun rawSqliteIntegerLinkOrderIsMergedBeforeTheLegacyIntConversion() = runBlocking {
        seed("a", listOf(image("small")))
        seed("b", listOf(image("large")))
        setSourceOrder("a", 0)
        setSourceOrder("b", 0)
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = ? WHERE imageId = ?",
            arrayOf<Any>(Int.MAX_VALUE.toLong() + 1L, "large"))
        assertEquals(listOf("small", "large"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquals(Int.MIN_VALUE, dao.getEnabledPlaybackImages().last().sortOrder)
        assertEquivalent()
    }

    @Test
    fun sharedImageFirstLinkFollowsTheMergedKeyAndChangesImmediatelyOnSourceStop() = runBlocking {
        val shared = image("shared")
        seed("a", listOf(image("a-only"), shared))
        seed("b", listOf(shared.copy(id = "temporary-scan-id"), image("b-only")))
        setSourceOrder("a", 4)
        setSourceOrder("b", 4)
        dao.updateLinkOrder("a", "shared", 8)
        assertEquals("b", dao.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        assertEquivalent()
        dao.setSourceEnabled("b", false, null)
        assertEquals("a", dao.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        assertEquivalent()
        dao.setSourceEnabled("b", true, null)
        assertEquals("b", dao.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        assertEquivalent()
    }

    @Test
    fun rulesKeepStoppedSourcesAndIgnoreInputIdOrderDuplicatesAndMissingIds() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        dao.setSourceEnabled("a", false, null)
        val selected = listOf("b", "missing", "a", "a")
        assertEquals(listOf("b"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquals(listOf("a", "b"), dao.getRulePlaybackImages(selected).map { it.id })
        assertRuleEquivalent(selected)
    }

    @Test
    fun rawNoncanonicalBooleanKeepsTheOldExactEnabledPredicate() = runBlocking {
        seed("source", listOf(image("one")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET enabled = 2 WHERE id = 'source'")
        assertTrue(dao.getSources().single().enabled)
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertEquivalent()
        assertEquals(listOf("one"), dao.getRulePlaybackImages(listOf("source")).map { it.id })
        assertRuleEquivalent(listOf("source"))
    }

    @Test
    fun literalPercentUnderscoreParentAndUnicodeExclusionsKeepReferenceSemantics() = runBlocking {
        seed("a", listOf(image("literal", "100%_合集/😀/a.png"), image("nearby", "100X合集/a.png"),
            image("child", "1/child/a.png"), image("sibling", "10/a.png")))
        seed("b", listOf(image("other", "100%_合集/😀/b.png")))
        dao.setDirectoryExcluded("a", "100%_合集", true, null)
        dao.setDirectoryExcluded("a", "1", true, null)
        assertEquals(listOf("nearby", "sibling", "other"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
        dao.setDirectoryExcluded("a", "1", false, null)
        assertEquivalent()
        dao.setSourceEnabled("a", false, null)
        assertRuleEquivalent(listOf("a", "b"))
    }

    @Test
    fun sourceMetadataAndLinkOrderUpdatesAreRealOnEverySubsequentCall() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        seed("b", listOf(image("three")))
        dao.getEnabledPlaybackImages()
        dao.updateSourceDefinition("a", "content://new/root", "MULTIPLE_DOCUMENTS", false, true, "changed")
        setSourceOrder("a", 99)
        dao.reorderSource("a", listOf("two", "one"))
        val result = dao.getEnabledPlaybackImages()
        assertEquals(listOf("three", "two", "one"), result.map { it.id })
        assertEquals("content://new/root", result.last().sourceRootUri)
        assertEquals("MULTIPLE_DOCUMENTS", result.last().sourceKind)
        assertEquals(99L, result.last().sourceOrder)
        assertEquivalent()
    }

    @Test
    fun imageMetadataStateAndUriChangesAreNeverHiddenByAProjectionCache() = runBlocking {
        seed("source", listOf(image("one"), image("two")))
        dao.getEnabledPlaybackImages()
        dao.updateImage("one", "content://changed/one", "renamed.jpg", "new/path/one.jpg",
            9L, 1, 2, "COMMITTED", "changed")
        dao.updateImage("two", "content://images/two", "two.png", "two.png",
            null, null, null, "FAILED", "changed")
        val result = dao.getEnabledPlaybackImages().single()
        assertEquals("content://changed/one", result.uri)
        assertEquals("renamed.jpg", result.displayName)
        assertEquals("new/path/one.jpg", result.relativePath)
        assertEquivalent()
    }

    @Test
    fun emptyAllStoppedAndDeleteRetainRecordsAndNextCallObservesChanges() = runBlocking {
        assertFalse(dao.hasSources())
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertTrue(dao.getRulePlaybackImages(emptyList()).isEmpty())
        assertTrue(dao.getRulePlaybackImages(listOf("missing")).isEmpty())
        seed("source", listOf(image("kept")))
        dao.setSourceEnabled("source", false, null)
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertEquals(1, dao.getRulePlaybackImages(listOf("source")).size)
        dao.deleteSource("source")
        assertFalse(dao.hasSources())
        assertEquals(1, dao.getAllImages().size)
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertRuleEquivalent(listOf("source"))
    }

    @Test
    fun sourceAndEverySelectedLinkQueryRunInTheSameProductionRoomTransaction() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        seed("stopped", listOf(image("stopped")))
        dao.setSourceEnabled("stopped", false, null)
        reads.clear()
        dao.getEnabledPlaybackImages()
        assertEquals(5, reads.size)
        assertTrue(reads.all { it.inTransaction })
        assertEquals(setOf("a", "b"), reads.filter { it.args.size == 1 }.map { it.args.single() }.toSet())
        assertTrue(reads.filter { it.sql.startsWith("SELECT CAST(printf(") }.all { !it.sql.contains("INNER JOIN sources") &&
            !it.sql.contains("AS sourceId") && it.sql.contains("ORDER BY l.sortOrder ASC, l.imageId ASC") })
        reads.clear()
        dao.getRulePlaybackImages(listOf("stopped", "b", "b"))
        assertEquals(5, reads.size)
        assertTrue(reads.all { it.inTransaction })
        assertEquals(setOf("stopped", "b"), reads.filter { it.args.size == 1 }.map { it.args.single() }.toSet())
    }

    @Test
    fun repeatedProductionCallsAlwaysPerformFreshRealSourceAndLinkReads() = runBlocking {
        seed("source", listOf(image("one")))
        reads.clear()
        repeat(3) { assertEquals(1, dao.getEnabledPlaybackImages().size) }
        assertEquals(9, reads.size)
        assertTrue(reads.all { it.inTransaction })
    }

    @Test
    fun temporaryFailureStatusDoesNotDiscardCommittedConfiguration() = runBlocking {
        seed("source", listOf(image("one")))
        dao.updateSourceState("source", "METADATA_FAILURE", "DENIED", null, "temporary", "changed")
        assertEquals(listOf("one"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
    }

    @Test
    fun large11890CatalogWithEqualSourceOrdersMatchesBothReferencesAndCanonicalObjects() = runBlocking {
        for (index in 0 until 4) {
            val source = "source-$index"
            seed(source, (index until 11890 step 4).map { image("image-$it", "目录/😀/$it.png") })
            setSourceOrder(source, (index / 2).toLong())
        }
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = sortOrder / 3")
        val result = dao.getEnabledPlaybackImages()
        assertEquals(11890, result.size)
        assertEquivalent()
        for (group in result.groupBy { it.sourceId }.values) {
            for (item in group) {
                assertSame(group.first().sourceId, item.sourceId)
                assertSame(group.first().sourceKind, item.sourceKind)
                assertSame(group.first().sourceRootUri, item.sourceRootUri)
            }
        }
        assertEquals(8, database.openHelper.readableDatabase.version)
    }

    @Test
    fun deterministicSharedAndTieCorpusMatchesFullSqlBeforeAndAfterEveryUpdate() = runBlocking {
        val random = Random(47)
        val images = (0 until 80).map { image("image-$it") }
        for (index in 0 until 6) {
            val source = "source-$index"
            seed(source, images.filter { random.nextBoolean() })
            setSourceOrder(source, (index % 2).toLong())
            for (link in dao.getLinks(source)) dao.updateLinkOrder(source, link.imageId, random.nextInt(-2, 4))
        }
        assertEquivalent()
        assertRuleEquivalent(listOf("source-4", "source-1", "source-1", "missing"))
        for (index in 0 until 6) {
            dao.setSourceEnabled("source-$index", false, null)
            assertEquivalent()
            assertRuleEquivalent(listOf("source-5", "source-3", "source-1"))
        }
    }

    private suspend fun assertEquivalent() {
        val actual = dao.getEnabledPlaybackImages()
        assertEquals(dao.getEnabledPlaybackLinks().distinctBy { it.id }, actual)
        assertEquals(P1PlaybackProjection.resolve(dao.getSources(), dao.getEnabledPlaybackLinkRows()), actual)
    }

    private suspend fun assertRuleEquivalent(ids: List<String>) {
        val actual = dao.getRulePlaybackImages(ids)
        assertEquals(dao.getRulePlaybackLinks(ids).distinctBy { it.id }, actual)
        assertEquals(P1PlaybackProjection.resolve(dao.getSources(), dao.getRulePlaybackLinkRows(ids)), actual)
    }

    private suspend fun seed(source: String, images: List<P1ImageRecordEntity>) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.commitScan(source, images, "APP_OWNED", "2026-10-10T00:00:00Z")
    }

    private fun setSourceOrder(sourceId: String, order: Long) {
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = ? WHERE id = ?", arrayOf<Any>(order, sourceId))
    }

    private fun image(id: String, path: String = "$id.png") =
        P1ImageRecordEntity(id, "content://images/$id", path.substringAfterLast('/'), path)

    private data class Read(val sql: String, val args: List<Any?>, val inTransaction: Boolean)
}
