package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
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

/** Real generated Room queries are compared with the unchanged full projection, not host timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1PlaybackCompactRoomTest {
    private lateinit var database: ShimmerDatabase
    private lateinit var dao: P1SourceDao
    private val transactionReads = ConcurrentLinkedQueue<Boolean>()

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ ->
                val normalized = sql.trim()
                val sourceRead = normalized == "SELECT * FROM sources ORDER BY createdAt, id" ||
                    normalized == "SELECT * FROM sources WHERE enabled = 1 ORDER BY createdAt, id"
                val compactRead = normalized.startsWith("SELECT i.id AS id") &&
                    !normalized.contains("AS sourceRootUri")
                val packedRead = normalized.startsWith("SELECT CAST(printf(")
                val exclusionRead = normalized.startsWith("SELECT * FROM source_directory_exclusions WHERE sourceId")
                if (sourceRead || compactRead || packedRead || exclusionRead) transactionReads.add(database.inTransaction())
            }, Executor { it.run() })
            .build()
        dao = database.p1SourceDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun emptyCatalogAndEmptyRuleMatchReference() = runBlocking {
        assertFalse(dao.hasSources())
        assertEquals(dao.getEnabledPlaybackLinks().distinctBy { it.id }, dao.getEnabledPlaybackImages())
        assertTrue(dao.getRulePlaybackImages(emptyList()).isEmpty())
        assertTrue(dao.getRulePlaybackImages(listOf("missing")).isEmpty())
    }

    @Test
    fun hasSourcesObservesCreationAndDeletionWithoutWaitingForInvalidation() = runBlocking {
        seed("source", listOf(image("kept")))
        assertTrue(dao.hasSources())
        assertEquals(1, dao.getEnabledPlaybackImages().size)
        dao.deleteSource("source")
        assertFalse(dao.hasSources())
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertEquals(1, dao.getAllImages().size)
    }

    @Test
    fun sourceOrderLinkOrderAndEveryTieBreakMatchTheOldProjection() = runBlocking {
        seed("b", listOf(image("b-z"), image("b-a")))
        seed("a", listOf(image("a-z"), image("a-a")))
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 7")
        for (source in listOf("a", "b")) {
            for (link in dao.getLinks(source)) dao.updateLinkOrder(source, link.imageId, 2)
        }
        val result = dao.getEnabledPlaybackImages()
        assertEquals(listOf("a-a", "a-z", "b-a", "b-z"), result.map { it.id })
        assertEquals(dao.getEnabledPlaybackLinks().distinctBy { it.id }, result)
    }

    @Test
    fun sharedUriUsesItsEarliestEligibleSourceAndImmediatelyChangesWhenStopped() = runBlocking {
        val shared = image("shared")
        seed("a", listOf(shared, image("a-only")))
        seed("b", listOf(shared.copy(id = "different-scan-key"), image("b-only")))
        assertEquals("a", dao.getEnabledPlaybackImages().first { it.uri == shared.uri }.sourceId)
        assertEquivalent()
        dao.setSourceEnabled("a", false, null)
        assertEquals("b", dao.getEnabledPlaybackImages().first { it.uri == shared.uri }.sourceId)
        assertEquivalent()
        dao.setSourceEnabled("a", true, null)
        assertEquals("a", dao.getEnabledPlaybackImages().first { it.uri == shared.uri }.sourceId)
        assertEquivalent()
    }

    @Test
    fun sourceDefinitionAndOrderUpdatesAreReadOnTheNextCall() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        dao.getEnabledPlaybackImages()
        dao.updateSourceDefinition("a", "content://new/root", "MULTIPLE_DOCUMENTS", false, true, "changed")
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 9 WHERE id = 'a'")
        val result = dao.getEnabledPlaybackImages()
        assertEquals(listOf("b", "a"), result.map { it.id })
        val changed = result.last()
        assertEquals("MULTIPLE_DOCUMENTS", changed.sourceKind)
        assertEquals("content://new/root", changed.sourceRootUri)
        assertEquals(9L, changed.sourceOrder)
        assertEquivalent()
    }

    @Test
    fun imageMetadataAndCommittedEligibilityUpdatesAreImmediatelyVisible() = runBlocking {
        seed("source", listOf(image("first"), image("second")))
        dao.getEnabledPlaybackImages()
        dao.updateImage("first", "content://changed/first", "changed.png", "new/path.png",
            null, null, null, "COMMITTED", "changed")
        dao.updateImage("second", "content://images/second", "second.png", "second.png",
            null, null, null, "FAILED", "changed")
        val changed = dao.getEnabledPlaybackImages().single()
        assertEquals("content://changed/first", changed.uri)
        assertEquals("changed.png", changed.displayName)
        assertEquals("new/path.png", changed.relativePath)
        assertEquivalent()
    }

    @Test
    fun reorderingLinksIsNotHiddenByAnyDirectoryCache() = runBlocking {
        seed("source", listOf(image("first"), image("second"), image("third")))
        dao.getEnabledPlaybackImages()
        dao.reorderSource("source", listOf("third", "first", "second"))
        assertEquals(listOf("third", "first", "second"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
    }

    @Test
    fun exclusionsKeepWildcardNamesLiteralAndNeighbouringDirectoriesPlayable() = runBlocking {
        seed("a", listOf(image("a1", "100%_合集/x.png"), image("a2", "100X合集/y.png"),
            image("a3", "1/child/z.png"), image("a4", "10/keep.png")))
        seed("b", listOf(image("b1", "100%_合集/keep.png")))
        dao.setDirectoryExcluded("a", "100%_合集", true, null)
        dao.setDirectoryExcluded("a", "1", true, null)
        assertEquals(listOf("a2", "a4", "b1"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
        dao.setDirectoryExcluded("a", "1", false, null)
        assertEquals(listOf("a2", "a3", "a4", "b1"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
    }

    @Test
    fun ruleCatalogKeepsStoppedSourcesExclusionsAndSourceOrderRatherThanRuleListOrder() = runBlocking {
        seed("a", listOf(image("a-keep"), image("a-stop", "stop/x.png")))
        seed("b", listOf(image("b")))
        dao.setSourceEnabled("a", false, null)
        dao.setDirectoryExcluded("a", "stop", true, null)
        val ids = listOf("b", "missing", "a", "a")
        val result = dao.getRulePlaybackImages(ids)
        assertEquals(listOf("a-keep", "b"), result.map { it.id })
        assertEquals(dao.getRulePlaybackLinks(ids).distinctBy { it.id }, result)
        assertEquals(listOf("b"), dao.getEnabledPlaybackImages().map { it.id })
    }

    @Test
    fun temporarySourceFailureDoesNotHidePreviouslyCommittedImages() = runBlocking {
        seed("source", listOf(image("kept")))
        dao.updateSourceState("source", "METADATA_FAILURE", "DENIED", null, "temporary", "changed")
        assertEquals(listOf("kept"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
    }

    @Test
    fun allStoppedAndResumeIsImmediatelyObservedWithoutDeletingRecords() = runBlocking {
        seed("source", listOf(image("one"), image("two")))
        dao.setSourceEnabled("source", false, null)
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        assertEquals(2, dao.getAllImages().size)
        dao.setSourceEnabled("source", true, null)
        assertEquals(listOf("one", "two"), dao.getEnabledPlaybackImages().map { it.id })
        assertEquivalent()
    }

    @Test
    fun productionSourceAndLinkReadsExecuteInsideTheSameRoomTransaction() = runBlocking {
        seed("source", listOf(image("one")))
        transactionReads.clear()
        dao.getEnabledPlaybackImages()
        assertEquals(3, transactionReads.size)
        assertTrue(transactionReads.all { it })
        transactionReads.clear()
        dao.getRulePlaybackImages(listOf("source"))
        assertEquals(3, transactionReads.size)
        assertTrue(transactionReads.all { it })
    }

    @Test
    fun largeRealRoomProjectionMatchesEveryReferenceFieldAndSharesSourceObjects() = runBlocking {
        seed("source", (0 until 11890).map { image("image-$it", "目录/😀/$it.png") })
        val reference = dao.getEnabledPlaybackLinks().distinctBy { it.id }
        val images = dao.getEnabledPlaybackImages()
        assertEquals(reference, images)
        assertEquals(11890, images.size)
        for (image in images) {
            assertSame(images.first().sourceId, image.sourceId)
            assertSame(images.first().sourceKind, image.sourceKind)
            assertSame(images.first().sourceRootUri, image.sourceRootUri)
        }
        assertEquals(8, database.openHelper.readableDatabase.version)
    }

    private suspend fun assertEquivalent() {
        assertEquals(dao.getEnabledPlaybackLinks().distinctBy { it.id }, dao.getEnabledPlaybackImages())
    }

    private suspend fun seed(source: String, images: List<P1ImageRecordEntity>) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.commitScan(source, images, "APP_OWNED", "2026-10-10T00:00:00Z")
    }

    private fun image(id: String, path: String = "$id.png") = P1ImageRecordEntity(
        id = id, uri = "content://images/$id", displayName = path.substringAfterLast('/'), relativePath = path,
    )
}
