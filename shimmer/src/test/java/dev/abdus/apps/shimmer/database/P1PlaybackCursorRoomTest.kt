package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p4.EffectiveCatalog
import dev.abdus.apps.shimmer.p4.PlaybackRule
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room compatibility Cursor reads; correctness is not a target-device latency claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1PlaybackCursorRoomTest {
    private lateinit var database: ShimmerDatabase
    private lateinit var dao: P1SourceDao
    private lateinit var reader: P1PlaybackCursorReader
    private val reads = ConcurrentLinkedQueue<Read>()
    private var onRead: ((String) -> Unit)? = null

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShimmerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, args ->
                val normalized = sql.trim()
                if (normalized.startsWith("SELECT") && (normalized.contains("FROM sources") ||
                    normalized.contains("FROM source_image_links l") || normalized.contains("FROM source_directory_exclusions"))) {
                    reads.add(Read(normalized, args.toList(), database.inTransaction(), Thread.currentThread().id))
                    onRead?.invoke(normalized)
                }
            }, Executor { it.run() }).build()
        dao = database.p1SourceDao()
        reader = P1PlaybackCursorReader(database)
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun nativeCursorMatchesOldFullSixFiveAndPackedReferenceFields() = runBlocking {
        seed("source", listOf(image("z", "目录/z.png"), image("a", "目录/a.png")))
        dao.updateLinkOrder("source", "z", -5)
        val actual = reader.getEnabledPlaybackImages()
        assertEquals(listOf("z", "a"), actual.map { it.id })
        assertEnabledEquivalent()
        assertEquals(dao.getPlaybackSourceLinkRows("source"),
            P1PlaybackPackedProjection.decode(dao.getPlaybackSourcePackedLinkRowsWithoutExclusions("source")))
    }

    @Test
    fun emptyExclusionBranchReadsOriginalFiveColumnsWithoutPackedOrCorrelatedSql() = runBlocking {
        seed("a", listOf(image("a"), image("b")))
        reads.clear()
        assertEquals(2, reader.getEnabledPlaybackImages().size)
        val links = reads.single { it.sql.contains("FROM source_image_links l") }
        assertTrue(links.sql.startsWith("SELECT i.id AS id"))
        assertFalse(links.sql.contains("NOT EXISTS"))
        assertFalse(links.sql.contains("printf"))
        assertFalse(links.sql.contains("packedFields"))
        assertTrue(links.sql.contains("ORDER BY l.sortOrder ASC, l.imageId ASC"))
        assertEquals(listOf("a"), links.args)
        assertEquals(3, reads.size)
        assertTrue(reads.all { it.inTransaction })
    }

    @Test
    fun nonemptyExclusionsReadOriginalLiteralPredicateAndStayInTransaction() = runBlocking {
        seed("a", listOf(image("skip", "100%_子/😀/a.png"), image("keep", "100X子/a.png")))
        dao.setDirectoryExcluded("a", "100%_子", true, null)
        reads.clear()
        assertEquals(listOf("keep"), reader.getEnabledPlaybackImages().map { it.id })
        val links = reads.single { it.sql.contains("FROM source_image_links l") }
        assertTrue(links.sql.contains("NOT EXISTS"))
        assertTrue(links.sql.contains("substr(i.relativePath, 1, length(x.directoryPath) + 1) = x.directoryPath || '/'"))
        assertFalse(links.sql.contains(" LIKE "))
        assertTrue(reads.all { it.inTransaction })
        assertEnabledEquivalent()
    }

    @Test
    fun emptyAndNonemptyExclusionDecisionIsReadFreshForEveryAction() = runBlocking {
        seed("a", listOf(image("x", "1/x.png"), image("y", "10/y.png")))
        repeat(3) {
            reads.clear()
            assertEquals(2, reader.getEnabledPlaybackImages().size)
            assertFalse(reads.single { it.sql.contains("FROM source_image_links l") }.sql.contains("NOT EXISTS"))
            dao.setDirectoryExcluded("a", "1", true, null)
            reads.clear()
            assertEquals(listOf("y"), reader.getEnabledPlaybackImages().map { it.id })
            assertTrue(reads.single { it.sql.contains("FROM source_image_links l") }.sql.contains("NOT EXISTS"))
            assertEnabledEquivalent()
            dao.setDirectoryExcluded("a", "1", false, null)
        }
    }

    @Test
    fun enabledSourceExclusionsAndEveryCursorReadShareOneRealRoomTransactionThread() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        seed("stopped", listOf(image("stopped")))
        dao.setSourceEnabled("stopped", false, null)
        reads.clear()
        reader.getEnabledPlaybackImages()
        assertEquals(5, reads.size)
        assertTrue(reads.all { it.inTransaction })
        assertEquals(1, reads.map { it.thread }.distinct().size)
        assertEquals(setOf("a", "b"), reads.filter { it.args.size == 1 }.map { it.args.single() }.toSet())
    }

    @Test
    fun ruleSourcesExclusionsAndCursorReadsShareOneRealRoomTransactionThread() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        dao.setSourceEnabled("a", false, null)
        reads.clear()
        assertEquals(listOf("a", "b"), reader.getRulePlaybackImages(listOf("b", "a", "a", "missing")).map { it.id })
        assertEquals(5, reads.size)
        assertTrue(reads.all { it.inTransaction })
        assertEquals(1, reads.map { it.thread }.distinct().size)
        assertRuleEquivalent(listOf("b", "a", "a", "missing"))
    }

    @Test
    fun repeatedCallsPerformAllAuthoritativeReadsRatherThanReusingPriorRows() = runBlocking {
        seed("a", listOf(image("a")))
        reads.clear()
        repeat(3) { assertEquals(1, reader.getEnabledPlaybackImages().size) }
        assertEquals(9, reads.size)
        assertEquals(3, reads.count { it.sql.contains("FROM source_image_links l") })
        assertTrue(reads.all { it.inTransaction })
    }

    @Test
    fun exactEnabledOneAndCommittedMetadataPredicatesRemainUnchanged() = runBlocking {
        seed("a", listOf(image("a"), image("failed").copy(metadataState = "FAILED")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET enabled = 2")
        assertTrue(dao.getSources().single().enabled)
        assertTrue(reader.getEnabledPlaybackImages().isEmpty())
        assertEquals(listOf("a"), reader.getRulePlaybackImages(listOf("a")).map { it.id })
        assertEnabledEquivalent()
        assertRuleEquivalent(listOf("a"))
    }

    @Test
    fun equalSourceOrderInterleavesLinkOrderBeforeSourceIdentityAndImageIdentity() = runBlocking {
        seed("b", listOf(image("b0"), image("b4-z"), image("b4-a")))
        seed("a", listOf(image("a1"), image("a4-z"), image("a4-a")))
        setSourceOrder("a", 9)
        setSourceOrder("b", 9)
        for (id in listOf("a4-z", "a4-a")) dao.updateLinkOrder("a", id, 4)
        for (id in listOf("b4-z", "b4-a")) dao.updateLinkOrder("b", id, 4)
        dao.updateLinkOrder("a", "a1", 1)
        assertEquals(listOf("b0", "a1", "a4-a", "a4-z", "b4-a", "b4-z"), reader.getEnabledPlaybackImages().map { it.id })
        assertEnabledEquivalent()
    }

    @Test
    fun sourceAndImageIdentityTiesKeepSqliteUtf8BinaryOrder() = runBlocking {
        seed("😀", listOf(image("supplementary")))
        seed("\uE000", listOf(image("bmp")))
        seed("a", listOf(image("😀"), image("\uE000"), image("ascii")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 0")
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = 0")
        assertEquals(listOf("ascii", "\uE000", "😀", "bmp", "supplementary"), reader.getEnabledPlaybackImages().map { it.id })
        assertEnabledEquivalent()
    }

    @Test
    fun long64SortKeysAreMergedBeforeLegacyIntConversion() = runBlocking {
        seed("a", listOf(image("small")))
        seed("b", listOf(image("huge")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 9223372036854775807")
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = 2147483648 WHERE imageId = 'huge'")
        assertEquals(listOf("small", "huge"), reader.getEnabledPlaybackImages().map { it.id })
        assertEquals(Int.MIN_VALUE, reader.getEnabledPlaybackImages().last().sortOrder)
        assertEnabledEquivalent()
    }

    @Test
    fun sharedFirstImageOwnerChangesImmediatelyWhenOneSourceIsStopped() = runBlocking {
        val shared = image("shared")
        seed("a", listOf(image("a"), shared))
        seed("b", listOf(shared, image("b")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 5")
        dao.updateLinkOrder("a", "shared", 20)
        assertEquals("b", reader.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        assertEnabledEquivalent()
        dao.setSourceEnabled("b", false, null)
        assertEquals("a", reader.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        assertEnabledEquivalent()
        dao.setSourceEnabled("b", true, null)
        assertEquals("b", reader.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
    }

    @Test
    fun sourceMetadataAndLinkReorderAreFreshOnEveryNextCall() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        seed("b", listOf(image("three")))
        reader.getEnabledPlaybackImages()
        dao.updateSourceDefinition("a", "content://new/root", "MULTIPLE_DOCUMENTS", false, true, null)
        setSourceOrder("a", 99)
        dao.reorderSource("a", listOf("two", "one"))
        val result = reader.getEnabledPlaybackImages()
        assertEquals(listOf("three", "two", "one"), result.map { it.id })
        assertEquals("content://new/root", result.last().sourceRootUri)
        assertEquals("MULTIPLE_DOCUMENTS", result.last().sourceKind)
        assertEquals(99L, result.last().sourceOrder)
        assertEnabledEquivalent()
    }

    @Test
    fun imageUriNamePathAndMetadataUpdatesAreFreshWithoutListenerOrCacheWait() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        reader.getEnabledPlaybackImages()
        dao.updateImage("one", "content://changed/😀", "改名.png", "新/路/径.png", null, null, null, "COMMITTED", null)
        dao.updateImage("two", "content://two", "two.png", "two.png", null, null, null, "FAILED", null)
        val result = reader.getEnabledPlaybackImages().single()
        assertEquals("content://changed/😀", result.uri)
        assertEquals("改名.png", result.displayName)
        assertEquals("新/路/径.png", result.relativePath)
        assertEnabledEquivalent()
    }

    @Test
    fun emptyStoppedMissingAndDeletedCatalogsKeepCommittedRecords() = runBlocking {
        assertTrue(reader.getEnabledPlaybackImages().isEmpty())
        assertTrue(reader.getRulePlaybackImages(emptyList()).isEmpty())
        assertTrue(reader.getRulePlaybackImages(listOf("missing")).isEmpty())
        seed("a", listOf(image("kept")))
        dao.setSourceEnabled("a", false, null)
        assertTrue(reader.getEnabledPlaybackImages().isEmpty())
        assertEquals(1, reader.getRulePlaybackImages(listOf("a")).size)
        dao.deleteSource("a")
        assertEquals(1, dao.getAllImages().size)
        assertTrue(reader.getEnabledPlaybackImages().isEmpty())
        assertTrue(reader.getRulePlaybackImages(listOf("a")).isEmpty())
    }

    @Test
    fun temporaryScanOrAuthorizationFailureStateDoesNotDeleteCommittedRows() = runBlocking {
        seed("a", listOf(image("kept")))
        dao.updateSourceState("a", "METADATA_FAILURE", "DENIED", null, "temporary", null)
        assertEquals(listOf("kept"), reader.getEnabledPlaybackImages().map { it.id })
        assertEnabledEquivalent()
    }

    @Test
    fun nativeTextReadMatchesOldDecoderForAllFourNulFields() = runBlocking {
        for (field in 0..3) {
            val values = listOf("id-$field", "content://images/$field", "图$field.png", "目录/$field.png")
                .toMutableList().also { it[field] += "\u0000after" }
            seed("source-$field", listOf(P1ImageRecordEntity(values[0], values[1], values[2], values[3]), image("good-$field")))
        }
        assertEquals(8, reader.getEnabledPlaybackImages().size)
        assertEnabledEquivalent()
        assertRuleEquivalent(listOf("source-0", "source-2"))
    }

    @Test
    fun nativeTextReadMatchesOldDecoderForAllUnicodeNoncharacters() = runBlocking {
        val points = (0xfdd0..0xfdef).toList() + (0..16).flatMap { listOf((it shl 16) + 0xfffe, (it shl 16) + 0xffff) }
        for (point in points) {
            val value = String(Character.toChars(point))
            seed("source-$point", listOf(P1ImageRecordEntity("id-$point$value", "content://image/$point$value", "name$value.png", "path/$value.png")))
        }
        assertEquals(66, reader.getEnabledPlaybackImages().size)
        assertEnabledEquivalent()
        assertRuleEquivalent(listOf("source-65535", "source-65534", "source-64976"))
    }

    @Test
    fun malformedUtf8TextUsesTheSamePlatformStringDecoderWithoutIdentityGuess() = runBlocking {
        seed("base", listOf(image("regular")))
        val expressions = listOf("CAST(X'61C08062' AS TEXT)", "CAST(X'61EDA08062' AS TEXT)",
            "CAST(X'61F490808062' AS TEXT)", "CAST(X'61FF62' AS TEXT)")
        for (field in listOf("uri", "displayName", "relativePath")) for (expression in expressions) {
            database.openHelper.writableDatabase.execSQL("UPDATE image_records SET $field = $expression WHERE id = 'regular'")
            assertEnabledEquivalent()
            assertRuleEquivalent(listOf("base"))
            dao.updateImage("regular", "content://regular", "regular.png", "regular.png", null, null, null, "COMMITTED", null)
        }
        database.openHelper.writableDatabase.execSQL("INSERT INTO image_records(id,uri,displayName,relativePath,metadataState) " +
            "VALUES(CAST(X'61FF62' AS TEXT),'content://raw/id','name.png','path.png','COMMITTED')")
        database.openHelper.writableDatabase.execSQL("INSERT INTO source_image_links(sourceId,imageId,sortOrder) VALUES('base',CAST(X'61FF62' AS TEXT),9)")
        assertEnabledEquivalent()
        assertRuleEquivalent(listOf("base"))
    }

    @Test
    fun emptyStringsAndUnicodeScalarEdgesKeepExactOldProjection() = runBlocking {
        seed("empty", listOf(P1ImageRecordEntity("", "", "", "")))
        seed("edge", listOf(image("\u0001\u007f\u0080\u07ff\u0800\uD7FF\uE000\uFFFF𐀀􏿿")))
        assertEquals(2, reader.getEnabledPlaybackImages().size)
        assertEnabledEquivalent()
    }

    @Test
    fun sourceIdWithQuoteIsBoundNotInterpolatedAndExclusionsStayLiteral() = runBlocking {
        seed("a' OR 1=1 --", listOf(image("a", "100%_子/a.png")))
        seed("other", listOf(image("other")))
        assertEquals(listOf("a"), reader.getRulePlaybackImages(listOf("a' OR 1=1 --")).map { it.id })
        assertRuleEquivalent(listOf("a' OR 1=1 --"))
        dao.setDirectoryExcluded("a' OR 1=1 --", "100%_子", true, null)
        assertTrue(reader.getRulePlaybackImages(listOf("a' OR 1=1 --")).isEmpty())
        assertEnabledEquivalent()
    }

    @Test
    fun emptyRuleDoesNotPerformAnUnnecessaryFullSourceRead() = runBlocking {
        seed("a", listOf(image("a")))
        reads.clear()
        assertTrue(reader.getRulePlaybackImages(emptyList()).isEmpty())
        assertTrue(reads.isEmpty())
    }

    @Test
    fun effectiveCatalogFirstNonemptyRuleAndDisabledSourceFallbackMatchDaoBaseline() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b"), image("excluded", "stop/x.png")))
        seed("c", listOf(image("c")))
        dao.setSourceEnabled("b", false, null)
        dao.setDirectoryExcluded("b", "stop", true, null)
        val settings = ScheduleSettings(rules = listOf(PlaybackRule("gone", sourceIds = listOf("missing")),
            PlaybackRule("second", sourceIds = listOf("c", "b")), PlaybackRule("third", sourceIds = listOf("a"))))
        val instant = Instant.parse("2026-10-02T04:00:00Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val result = EffectiveCatalog.resolve(reader, settings, instant, zone)
        assertEquals("second", result.rule?.id)
        assertEquals(listOf("b", "c"), result.images.map { it.id })
        assertEquals(EffectiveCatalog.resolve(dao, settings, instant, zone), result)
        assertEquals(EffectiveCatalog.resolve(dao, settings.copy(pauseAll = true), instant, zone),
            EffectiveCatalog.resolve(reader, settings.copy(pauseAll = true), instant, zone))
        val emptySettings = ScheduleSettings(rules = listOf(PlaybackRule("gone", sourceIds = listOf("missing"))))
        assertEquals(EffectiveCatalog.resolve(dao, emptySettings, instant, zone), EffectiveCatalog.resolve(reader, emptySettings, instant, zone))
    }

    @Test
    fun cancellationDuringRealCursorQueryPropagatesAndNextTransactionStillReadsAllRows() = runBlocking {
        seed("a", (0 until 60).map { image("id-$it") })
        var taskJob: Job? = null
        var cancelledAtQuery = false
        onRead = { sql ->
            if (!cancelledAtQuery && sql.contains("FROM source_image_links l")) {
                cancelledAtQuery = true
                taskJob!!.cancel(CancellationException("cancel actual cursor read"))
            }
        }
        val parent = SupervisorJob()
        val task = async(parent) {
            taskJob = currentCoroutineContext()[Job]!!
            reader.getEnabledPlaybackImages()
        }
        try {
            task.await()
            fail("Cancelled query must not publish a partial catalog")
        } catch (cancelled: CancellationException) {
            assertTrue(cancelled.message.orEmpty().contains("cancel actual cursor read"))
        } finally {
            onRead = null
            parent.cancel()
        }
        assertTrue(cancelledAtQuery)
        assertEquals(60, reader.getEnabledPlaybackImages().size)
        assertEnabledEquivalent()
    }

    @Test
    fun databaseReadFailureIsNotSwallowedAsEmptyOrPreviousCatalog() = runBlocking {
        seed("a", listOf(image("a")))
        assertEquals(1, reader.getEnabledPlaybackImages().size)
        // Room's support open helper can reopen a closed in-memory database as a new empty one;
        // close() alone does not inject a query failure. Rename only this synthetic table so the
        // real production five-column query fails, then restore it with all rows intact.
        database.openHelper.writableDatabase.execSQL("ALTER TABLE source_image_links RENAME TO temporarily_unavailable_links")
        try {
            reader.getEnabledPlaybackImages()
            fail("A failed real source-link query must not return a cached or empty catalog")
        } catch (failed: SQLiteException) {
            assertTrue(failed.message.orEmpty().contains("source_image_links"))
        } finally {
            database.openHelper.writableDatabase.execSQL("ALTER TABLE temporarily_unavailable_links RENAME TO source_image_links")
        }
        assertEquals(1, reader.getEnabledPlaybackImages().size)
        assertEnabledEquivalent()
    }

    @Test
    fun large11890CatalogMatchesAllOldReferencesAndSharesCanonicalSourceObjects() = runBlocking {
        for (index in 0 until 4) {
            seed("source-$index", (index until 11890 step 4).map { image("image-$it", "目录/😀/$it.png") })
            setSourceOrder("source-$index", (index / 2).toLong())
        }
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = sortOrder / 3")
        dao.setDirectoryExcluded("source-1", "目录/不存在", true, null)
        val result = reader.getEnabledPlaybackImages()
        assertEquals(11890, result.size)
        assertEnabledEquivalent()
        assertRuleEquivalent(listOf("source-3", "source-1"))
        for (group in result.groupBy { it.sourceId }.values) for (item in group) {
            assertSame(group.first().sourceId, item.sourceId)
            assertSame(group.first().sourceKind, item.sourceKind)
            assertSame(group.first().sourceRootUri, item.sourceRootUri)
        }
        assertEquals(8, database.openHelper.readableDatabase.version)
    }

    @Test
    fun deterministicSharedTieAndExclusionCorpusIsEquivalentAfterEveryRealMutation() = runBlocking {
        val random = Random(49)
        val images = (0 until 80).map { image("图😀-$it", if (it % 2 == 0) "100%_子/$it.png" else "100X子/$it.png") }
        for (source in 0..4) {
            val id = "source-$source"
            seed(id, images.filter { random.nextBoolean() })
            setSourceOrder(id, (source % 2).toLong())
            for (link in dao.getLinks(id)) dao.updateLinkOrder(id, link.imageId, random.nextInt(-2, 4))
        }
        assertEnabledEquivalent()
        for (source in 0..4) {
            dao.setDirectoryExcluded("source-$source", "100%_子", true, null)
            assertEnabledEquivalent()
            assertRuleEquivalent(listOf("source-4", "source-1", "source-1", "missing"))
            dao.setSourceEnabled("source-$source", false, null)
            assertEnabledEquivalent()
            dao.setDirectoryExcluded("source-$source", "100%_子", false, null)
            assertRuleEquivalent(listOf("source-4", "source-1"))
        }
    }

    private suspend fun assertEnabledEquivalent() {
        val actual = reader.getEnabledPlaybackImages()
        assertEquals(dao.getEnabledPlaybackLinks().distinctBy { it.id }, actual)
        assertEquals(P1PlaybackProjection.resolve(dao.getSources(), dao.getEnabledPlaybackLinkRows()), actual)
        assertEquals(dao.getEnabledPlaybackImages(), actual)
    }

    private suspend fun assertRuleEquivalent(ids: List<String>) {
        val actual = reader.getRulePlaybackImages(ids)
        assertEquals(dao.getRulePlaybackLinks(ids).distinctBy { it.id }, actual)
        assertEquals(P1PlaybackProjection.resolve(dao.getSources(), dao.getRulePlaybackLinkRows(ids)), actual)
        assertEquals(dao.getRulePlaybackImages(ids), actual)
    }

    private suspend fun seed(source: String, images: List<P1ImageRecordEntity>) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.insertImages(images)
        dao.insertLinks(images.mapIndexed { index, image -> P1SourceImageLinkEntity(source, image.id, index) })
    }

    private fun setSourceOrder(sourceId: String, order: Long) {
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = ? WHERE id = ?", arrayOf<Any>(order, sourceId))
    }

    private fun image(id: String, path: String = "$id.png") =
        P1ImageRecordEntity(id, "content://images/$id", path.substringAfterLast('/'), path)

    private data class Read(val sql: String, val args: List<Any?>, val inTransaction: Boolean, val thread: Long)
}
