package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the generated Room DAO and actual SQLite transactions, not just the planner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1BatchCommitTest {
    private lateinit var database: ShimmerDatabase
    private lateinit var dao: P1SourceDao

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.p1SourceDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun register(id: String = "source") = dao.registerSource(
        P1SourceEntity(id, "file:///$id", "DIRECTORY_TREE", createdAt = "source-created"),
    )

    private fun image(id: String, uri: String = "file:///$id.png") = P1ImageRecordEntity(
        id = id,
        uri = uri,
        displayName = "$id.png",
        relativePath = "folder/$id.png",
        width = 30,
        height = 40,
        sizeBytes = 50L,
        createdAt = "image-created",
    )

    @Test
    fun moreThan999RowsCommitAndRescanAcrossEveryQueryChunkBoundary() = runBlocking {
        register()
        val original = (0 until 1005).map { image("image-$it") }
        assertEquals(1005, dao.commitScan("source", original, "APP_OWNED", "initial"))
        val linksBefore = dao.getLinks("source")
        assertEquals((0 until 1005).toList(), linksBefore.map { it.sortOrder })

        // Changed caller keys exercise URI lookup chunks as well as ID lookup chunks.
        val rescanned = original.reversed().mapIndexed { index, row ->
            row.copy(id = "new-key-$index", displayName = "rescanned-${row.displayName}",
                width = null, height = null, sizeBytes = null, createdAt = "should-not-replace")
        }
        assertEquals(1005, dao.commitScan("source", rescanned, "PERSISTED", "rescan"))

        assertEquals(1005, dao.getAllImages().size)
        assertEquals(linksBefore, dao.getLinks("source"))
        for (index in listOf(0, 399, 400, 799, 800, 1004)) {
            val row = requireNotNull(dao.getImage("image-$index"))
            assertEquals("rescanned-image-$index.png", row.displayName)
            assertEquals("image-created", row.createdAt)
            assertEquals("rescan", row.updatedAt)
            assertEquals(30, row.width)
            assertEquals(40, row.height)
            assertEquals(50L, row.sizeBytes)
        }
        assertEquals("COMMITTED", dao.getSource("source")?.scanState)
    }

    @Test
    fun partialRescanRetainsManualLinksDisabledSourceAndDirectoryExclusions() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("a"), image("b"), image("c")), "APP_OWNED", "initial")
        dao.reorderSource("source", listOf("b", "c", "a"))
        dao.setSourceEnabled("source", false, "disabled")
        dao.setDirectoryExcluded("source", "folder", true, "excluded")
        val exclusionsBefore = dao.getExclusions("source")

        assertEquals(3, dao.commitScan("source", listOf(image("c"), image("new"), image("a")),
            "APP_OWNED", "rescan"))

        assertEquals(listOf("b", "c", "a", "new"), dao.getLinks("source").map { it.imageId })
        assertEquals(listOf(0, 1, 2, 3), dao.getLinks("source").map { it.sortOrder })
        assertEquals(4, dao.getSourceImages("source").size)
        assertFalse(requireNotNull(dao.getSource("source")).enabled)
        assertEquals(exclusionsBefore, dao.getExclusions("source"))
    }

    @Test
    fun overlappingSourceKeepsLegacyUriIdentityAndAllExistingLinks() = runBlocking {
        register("older-source")
        register("newer-source")
        val legacy = image("legacy-id", "content://images/document/shared").copy(createdAt = "legacy-created")
        dao.commitScan("older-source", listOf(legacy), "PERSISTED", "initial")
        val olderLinks = dao.getLinks("older-source")

        assertEquals(2, dao.commitScan("newer-source", listOf(
            image("canonical-id", legacy.uri).copy(displayName = "new-name.png", createdAt = "new-created"),
            image("another-image"),
        ), "PERSISTED", "second"))

        assertEquals(2, dao.getAllImages().size)
        assertNull(dao.getImage("canonical-id"))
        assertEquals(olderLinks, dao.getLinks("older-source"))
        assertEquals(listOf("legacy-id", "another-image"), dao.getLinks("newer-source").map { it.imageId })
        assertEquals("legacy-created", dao.getImage("legacy-id")?.createdAt)
        assertEquals("new-name.png", dao.getImage("legacy-id")?.displayName)
        assertEquals(1, dao.getEnabledPlaybackImages().count { it.id == "legacy-id" })
    }

    @Test
    fun invalidMetadataRejectsWholeTransactionWithoutChangingPreviouslyCommittedRows() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("kept")), "APP_OWNED", "initial")
        val sourceBefore = dao.getSource("source")
        val rowsBefore = dao.getAllImages()
        val linksBefore = dao.getAllLinks()

        try {
            dao.commitScan("source", listOf(
                image("kept").copy(displayName = "must-not-change.png"),
                image("new-valid"),
                image("new-invalid").copy(metadataState = "FAILED"),
            ), "PERSISTED", "should-not-publish")
            fail("An invalid item must reject the entire commit")
        } catch (_: IllegalArgumentException) {
            // Expected: validation happens before the generated Room DAO writes rows.
        }

        assertEquals(sourceBefore, dao.getSource("source"))
        assertEquals(rowsBefore, dao.getAllImages())
        assertEquals(linksBefore, dao.getAllLinks())
    }

    @Test
    fun blankIdentityCannotCommitEarlierValidItemsOrChangeSourceState() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("kept")), "APP_OWNED", "initial")
        val sourceBefore = dao.getSource("source")
        val rowsBefore = dao.getAllImages()
        val linksBefore = dao.getAllLinks()

        try {
            dao.commitScan("source", listOf(image("new-valid"), image("blank-uri").copy(uri = " ")),
                "PERSISTED", "should-not-publish")
            fail("A blank URI must reject the entire commit")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals(sourceBefore, dao.getSource("source"))
        assertEquals(rowsBefore, dao.getAllImages())
        assertEquals(linksBefore, dao.getAllLinks())
    }

    @Test
    fun repeatedUriWithinOneBatchCreatesOneRowAndOneLinkWithoutLosingDimensions() = runBlocking {
        register()
        val sharedUri = "content://images/document/shared"
        assertEquals(1, dao.commitScan("source", listOf(
            image("first-id", sharedUri),
            image("second-id", sharedUri).copy(width = null, height = null, sizeBytes = null),
        ), "PERSISTED", "batch"))

        assertEquals(listOf("first-id"), dao.getAllImages().map { it.id })
        assertEquals(listOf(P1SourceImageLinkEntity("source", "first-id", 0)), dao.getLinks("source"))
        val accepted = requireNotNull(dao.getImage("first-id"))
        assertEquals(30, accepted.width)
        assertEquals(40, accepted.height)
        assertEquals(50L, accepted.sizeBytes)
        assertEquals("second-id.png", accepted.displayName)
    }

    @Test
    fun newIdCollisionAcrossDifferentUrisRejectsWholeBatchAndPreservesConfiguration() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("kept")), "APP_OWNED", "initial")
        val sourceBefore = dao.getSource("source")
        val rowsBefore = dao.getAllImages()
        val linksBefore = dao.getAllLinks()

        try {
            dao.commitScan("source", listOf(
                image("collision", "file:///first.png"),
                image("collision", "file:///different.png"),
            ), "APP_OWNED", "should-not-publish")
            fail("A new ID must not silently change its document identity within one batch")
        } catch (_: IllegalArgumentException) {
            // Expected: this guard already existed in the sequential v42 transaction.
        }

        assertEquals(sourceBefore, dao.getSource("source"))
        assertEquals(rowsBefore, dao.getAllImages())
        assertEquals(linksBefore, dao.getAllLinks())
    }

    @Test
    fun existingIdCanUpdateItsAccessUriWithoutCascadeDeletingSharedLinks() = runBlocking {
        register("one")
        register("two")
        dao.commitScan("one", listOf(image("stable")), "PERSISTED", "initial")
        dao.commitScan("two", listOf(image("stable")), "PERSISTED", "shared")
        val linksBefore = dao.getAllLinks()

        assertEquals(1, dao.commitScan("one", listOf(
            image("stable", "content://images/document/new-access").copy(createdAt = "not-original"),
        ), "PERSISTED", "updated"))

        assertEquals(linksBefore, dao.getAllLinks())
        assertEquals("content://images/document/new-access", dao.getImage("stable")?.uri)
        assertEquals("image-created", dao.getImage("stable")?.createdAt)
        assertEquals(1, dao.getAllImages().size)
    }

    @Test
    fun newImageCanReuseUriReleasedByEarlierExistingImageUpdate() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("existing", "file:///old.png")), "APP_OWNED", "initial")

        assertEquals(2, dao.commitScan("source", listOf(
            image("existing", "file:///new-access.png"),
            image("new-image", "file:///old.png"),
        ), "APP_OWNED", "rescan"))

        assertEquals("file:///new-access.png", dao.getImage("existing")?.uri)
        assertEquals("file:///old.png", dao.getImage("new-image")?.uri)
        assertEquals(listOf("existing", "new-image"), dao.getLinks("source").map { it.imageId })
        assertEquals(2, dao.getAllImages().size)
        assertEquals("COMMITTED", dao.getSource("source")?.scanState)
    }

    @Test
    fun repeatedExistingIdRetainsSequentialUriReleaseOrderBeforeCoalescingFinalRows() = runBlocking {
        register()
        dao.commitScan("source", listOf(
            image("a", "file:///x.png"),
            image("b", "file:///y.png"),
        ), "APP_OWNED", "initial")
        val linksBefore = dao.getLinks("source")

        // A releases x, B then releases y, and only then can A move to y. Coalescing first
        // would UPDATE A->y while B still owns y, violating the unique URI constraint.
        assertEquals(2, dao.commitScan("source", listOf(
            image("a", "file:///z.png"),
            image("b", "file:///x.png"),
            image("a", "file:///y.png"),
        ), "APP_OWNED", "rescan"))

        assertEquals("file:///y.png", dao.getImage("a")?.uri)
        assertEquals("file:///x.png", dao.getImage("b")?.uri)
        assertEquals(linksBefore, dao.getLinks("source"))
        assertEquals(2, dao.getAllImages().size)
        assertEquals("COMMITTED", dao.getSource("source")?.scanState)
    }

    @Test
    fun emptyCommitChangesOnlyStatusAndNeverRemovesPreviouslyCommittedImages() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("kept")), "APP_OWNED", "initial")
        val rowsBefore = dao.getAllImages()
        val linksBefore = dao.getAllLinks()

        assertEquals(0, dao.commitScan("source", emptyList(), "PERSISTED", "empty"))

        assertEquals(rowsBefore, dao.getAllImages())
        assertEquals(linksBefore, dao.getAllLinks())
        assertEquals("EMPTY", dao.getSource("source")?.scanState)
        assertEquals("EMPTY_RESULT", dao.getSource("source")?.lastError)
        assertTrue(dao.getEnabledPlaybackImages().any { it.id == "kept" })
    }

    @Test
    fun enclosingTransactionCancellationRollsBackBatchRowsLinksAndSourceState() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("kept")), "APP_OWNED", "initial")
        val sourceBefore = dao.getSource("source")
        val rowsBefore = dao.getAllImages()
        val linksBefore = dao.getAllLinks()

        try {
            database.withTransaction {
                assertEquals(2, dao.commitScan("source", listOf(
                    image("kept").copy(displayName = "must-roll-back.png"),
                    image("new-valid"),
                ), "PERSISTED", "should-not-publish"))
                // Both image writes and the state write have reached SQLite in this transaction.
                val inTransactionCount = dao.getSourceImages("source").size
                assertEquals(2, inTransactionCount)
                if (inTransactionCount == 2) {
                    throw CancellationException("Cancel before the enclosing transaction publishes")
                }
            }
            fail("The enclosing transaction must propagate cancellation")
        } catch (_: CancellationException) {
            // Expected: nested Room transactions must not publish the completed inner batch.
        }

        assertEquals(sourceBefore, dao.getSource("source"))
        assertEquals(rowsBefore, dao.getAllImages())
        assertEquals(linksBefore, dao.getAllLinks())
    }

    @Test
    fun unregisteredSourceCannotWriteAnyRowsOrAffectOtherSources() = runBlocking {
        register()
        dao.commitScan("source", listOf(image("kept")), "APP_OWNED", "initial")
        val sourcesBefore = dao.getSources()
        val rowsBefore = dao.getAllImages()
        val linksBefore = dao.getAllLinks()

        try {
            dao.commitScan("missing", listOf(image("must-not-write")), "APP_OWNED", "rejected")
            fail("A source must be registered before accepting a batch")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals(sourcesBefore, dao.getSources())
        assertEquals(rowsBefore, dao.getAllImages())
        assertEquals(linksBefore, dao.getAllLinks())
    }
}
