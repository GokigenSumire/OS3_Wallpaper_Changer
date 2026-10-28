package dev.abdus.apps.shimmer.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side Room contract checks. These are executable Android/SQLite tests; no target OEM
 * behavior is inferred from them. The P1 test card records them as NOT_RUN until installed.
 */
@RunWith(AndroidJUnit4::class)
class P1DatabaseContractTest {
    private var database: ShimmerDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun commitReusesUriAcrossSourcesAndPreservesAssociations() = runBlocking {
        val dao = openDatabase().p1SourceDao()
        val now = "2026-09-23T00:00:00.000Z"
        dao.registerSource(source("source-a", "content://tree/a"))
        dao.registerSource(source("source-b", "content://tree/b"))

        val first = image("doc-id", "content://provider/document/1")
        assertEquals(1, dao.commitScan("source-a", listOf(first), "PERSISTED", now))
        // The second scan has a provider-specific access URI but the same document URI. The
        // global URI contract must retain one image row and associate it with both sources.
        val sameDocument = first.copy(id = "other-id", displayName = "renamed.jpg")
        assertEquals(1, dao.commitScan("source-b", listOf(sameDocument), "PERSISTED", now))

        assertEquals(1, dao.getAllImages().size)
        assertEquals(setOf("source-a", "source-b"), dao.getAllLinks().map { it.sourceId }.toSet())
        assertEquals("doc-id", dao.getAllImages().single().id)
    }

    @Test
    fun commitReusesCanonicalIdWhenProviderAccessRouteChanges() = runBlocking {
        val dao = openDatabase().p1SourceDao()
        val now = "2026-09-23T00:00:00.000Z"
        dao.registerSource(source("source-a", "content://tree/a"))
        dao.registerSource(source("source-b", "content://tree/b"))
        val canonical = image("content://authority/document/doc-1", "content://tree/a/document/doc-1")
        dao.commitScan("source-a", listOf(canonical), "PERSISTED", now)
        dao.commitScan(
            "source-b",
            listOf(canonical.copy(uri = "content://tree/b/document/doc-1")),
            "PERSISTED",
            now,
        )

        assertEquals(1, dao.getAllImages().size)
        assertEquals("content://authority/document/doc-1", dao.getAllImages().single().id)
        assertEquals(setOf("source-a", "source-b"), dao.getAllLinks().map { it.sourceId }.toSet())
    }

    @Test
    fun emptyCommitKeepsExistingLinksAndOnlyUpdatesState() = runBlocking {
        val dao = openDatabase().p1SourceDao()
        val now = "2026-09-23T00:00:00.000Z"
        dao.registerSource(source("source-a", "content://tree/a"))
        dao.commitScan("source-a", listOf(image("doc-id", "content://provider/document/1")), "PERSISTED", now)

        assertEquals(0, dao.commitScan("source-a", emptyList(), "PERSISTED", now))
        assertEquals(1, dao.getLinks("source-a").size)
        assertEquals("EMPTY", dao.getSource("source-a")?.scanState)
    }

    @Test
    fun invalidScanRollsBackAllChanges() = runBlocking {
        val dao = openDatabase().p1SourceDao()
        val now = "2026-09-23T00:00:00.000Z"
        dao.registerSource(source("source-a", "content://tree/a"))

        try {
            dao.commitScan(
                "source-a",
                listOf(image("doc-id", "content://provider/document/1").copy(metadataState = "DECODE_FAILED")),
                "PERSISTED",
                now,
            )
        } catch (_: IllegalArgumentException) {
            // Expected: incomplete metadata is outside the commit contract.
        }

        assertTrue(dao.getAllImages().isEmpty())
        assertTrue(dao.getAllLinks().isEmpty())
        assertEquals("NOT_SCANNED", dao.getSource("source-a")?.scanState)
    }

    @Test
    fun enabledPlaybackProjectionUsesP1ImagesAndHonorsSourceEnabled() = runBlocking {
        val dao = openDatabase().p1SourceDao()
        val now = "2026-09-23T00:00:00.000Z"
        dao.registerSource(source("source-a", "content://tree/a"))
        dao.commitScan("source-a", listOf(image("doc-id", "content://provider/document/1")), "PERSISTED", now)

        assertEquals(1, dao.getEnabledPlaybackImages().size)
        assertEquals("content://provider/document/1", dao.getEnabledPlaybackImages().single().uri)

        dao.setSourceEnabled("source-a", false, now)
        assertTrue(dao.getEnabledPlaybackImages().isEmpty())
    }

    private fun openDatabase(): ShimmerDatabase {
        return database ?: Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ShimmerDatabase::class.java,
        ).allowMainThreadQueries().build().also { database = it }
    }

    private fun source(id: String, rootUri: String) = P1SourceEntity(
        id = id,
        rootUri = rootUri,
        kind = "DIRECTORY_TREE",
        createdAt = "2026-09-23T00:00:00.000Z",
        updatedAt = "2026-09-23T00:00:00.000Z",
    )

    private fun image(id: String, uri: String) = P1ImageRecordEntity(
        id = id,
        uri = uri,
        displayName = "one.jpg",
        relativePath = "one.jpg",
        sizeBytes = 10,
        width = 100,
        height = 200,
    )
}
