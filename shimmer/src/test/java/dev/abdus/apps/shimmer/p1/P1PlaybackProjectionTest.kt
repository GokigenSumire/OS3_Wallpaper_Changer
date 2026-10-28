package dev.abdus.apps.shimmer.p1

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.P1SourceImageLinkEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P1PlaybackProjectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.p1SourceDao()

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun sharedImageAppearsOnceUnderItsFirstEnabledSourceInGlobalOrder() = runBlocking {
        seed("a", "x", "y")
        seed("b", "y", "z")

        val playback = dao.getEnabledPlaybackImages()
        assertEquals(listOf("x", "y", "z"), playback.map { it.id })
        assertEquals(listOf("a", "a", "b"), playback.map { it.sourceId })
        assertEquals(listOf("DIRECTORY_TREE", "DIRECTORY_TREE", "DIRECTORY_TREE"), playback.map { it.sourceKind })
        assertEquals("file:///b", playback.last().sourceRootUri)
    }

    @Test
    fun disabledSourceIsExcludedAndSharedImageFallsBackToAnotherEnabledSource() = runBlocking {
        seed("a", "x", "y")
        seed("b", "y", "z")
        dao.setSourceEnabled("a", false, null)

        val playback = dao.getEnabledPlaybackImages()
        assertEquals(listOf("y", "z"), playback.map { it.id })
        assertEquals(listOf("b", "b"), playback.map { it.sourceId })
    }

    @Test
    fun linkSortOrderWithinSourceAndUncommittedImagesAreRespected() = runBlocking {
        seed("a", "first", "second", "third")
        dao.reorderSource("a", listOf("third", "first", "second"))
        dao.insertImages(listOf(P1ImageRecordEntity("broken", "file:///broken.png", "broken.png",
            "broken.png", metadataState = "FAILED")))
        dao.insertLinks(listOf(P1SourceImageLinkEntity("a", "broken", -1)))

        assertEquals(listOf("third", "first", "second"), dao.getEnabledPlaybackImages().map { it.id })
    }

    @Test
    fun stoppingAFolderRemovesItsWholeSubtreeAndLeavesSiblingsPlaying() = runBlocking {
        seedTree("a", "1/1-1/x.png", "1/1-2/y.png", "2/z.png", "top.png")

        dao.setDirectoryExcluded("a", "1", true, null)
        assertEquals(listOf("2/z.png", "top.png"), playbackPaths())

        // A level below an already stopped parent adds nothing, and re-enabling the parent restores
        // the whole subtree: an exclusion row never deletes image records.
        dao.setDirectoryExcluded("a", "1/1-1", true, null)
        assertEquals(listOf("2/z.png", "top.png"), playbackPaths())
        dao.setDirectoryExcluded("a", "1", false, null)
        assertEquals(listOf("1/1-2/y.png", "2/z.png", "top.png"), playbackPaths())
    }

    @Test
    fun aStoppedFolderOnlyAffectsItsOwnSourceAndNeighbouringNamesSurvive() = runBlocking {
        seedTree("a", "1/x.png", "10/y.png")
        seedTree("b", "1/shared-b.png")

        dao.setDirectoryExcluded("a", "1", true, null)

        // "10" shares the prefix "1" but is a different folder, and source b keeps its own "1".
        assertEquals(listOf("10/y.png", "1/shared-b.png"), playbackPaths())
    }

    @Test
    fun percentAndUnderscoreInFolderNamesAreMatchedLiterally() = runBlocking {
        seedTree("a", "100%_合集/x.png", "100X合集/y.png")

        dao.setDirectoryExcluded("a", "100%_合集", true, null)

        // A LIKE-based exclusion would treat % and _ as wildcards and stop 100X合集 as well.
        assertEquals(listOf("100X合集/y.png"), playbackPaths())
        assertEquals(setOf("100%_合集"), dao.getExclusions("a").map { it.directoryPath }.toSet())
    }

    @Test
    fun theSourceRootCannotBeStoppedThroughADirectoryRow() = runBlocking {
        seedTree("a", "top.png")

        // The root is controlled by the source enabled flag; a row for it would be unreachable.
        val rejected = runCatching { dao.setDirectoryExcluded("a", "/", true, null) }
        assertEquals(true, rejected.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf("top.png"), playbackPaths())
    }

    private suspend fun playbackPaths(): List<String> =
        dao.getEnabledPlaybackImages().map { it.relativePath }

    private suspend fun seed(source: String, vararg ids: String) {
        dao.registerSource(P1SourceEntity(source, "file:///$source", "DIRECTORY_TREE"))
        dao.commitScan(source, ids.map { id ->
            P1ImageRecordEntity(id = id, uri = "file:///$id.png", displayName = "$id.png", relativePath = "$id.png")
        }, "APP_OWNED", "2026-09-27T00:00:00Z")
    }

    private suspend fun seedTree(source: String, vararg paths: String) {
        dao.registerSource(P1SourceEntity(source, "file:///$source", "DIRECTORY_TREE"))
        dao.commitScan(source, paths.map { path ->
            val name = path.substringAfterLast('/')
            P1ImageRecordEntity(
                id = "$source:$path",
                uri = "file:///$source/$path",
                displayName = name,
                relativePath = path,
            )
        }, "APP_OWNED", "2026-09-27T00:00:00Z")
    }
}
