package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room invalidation for the four catalog tables; no Service, Surface or EGL is created. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1CatalogInvalidationTest {
    private lateinit var database: ShimmerDatabase
    private lateinit var dao: P1SourceDao
    private val observedTables = setOf(
        "sources", "image_records", "source_image_links", "source_directory_exclusions",
    )

    @Before fun openDatabase() {
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(), ShimmerDatabase::class.java,
            ).allowMainThreadQueries().build()
            dao = database.p1SourceDao()
            dao.registerSource(P1SourceEntity("source", "file:///source", "DIRECTORY_TREE"))
            dao.commitScan("source", listOf(
                P1ImageRecordEntity("one", "file:///one.png", "one.png", "child/one.png"),
                P1ImageRecordEntity("two", "file:///two.png", "two.png", "other/two.png"),
            ), "APP_OWNED", "2026-10-10T00:00:00Z")
        }
    }

    @After fun closeDatabase() { database.close() }

    @Test fun sourceEnabledChangeEmitsSourceInvalidationAndChangesEffectiveCatalog() = runBlocking {
        withCatalogObserver { notifications ->
            dao.setSourceEnabled("source", false, "2026-10-10T01:00:00Z")
            awaitTables(notifications, setOf("sources"))
            assertFalse(dao.getSource("source")!!.enabled)
            assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        }
    }

    @Test fun imageMetadataChangeEmitsImageInvalidationAndReadsFreshMetadata() = runBlocking {
        withCatalogObserver { notifications ->
            dao.updateImage("one", "file:///one.png", "changed.png", "child/one.png",
                null, null, null, "COMMITTED", "2026-10-10T01:00:00Z")
            awaitTables(notifications, setOf("image_records"))
            assertEquals("changed.png", dao.getImage("one")!!.displayName)
            assertEquals("changed.png", dao.getEnabledPlaybackImages().first().displayName)
        }
    }

    @Test fun linkOrderChangeEmitsLinkInvalidationAndReadsNewOrder() = runBlocking {
        withCatalogObserver { notifications ->
            dao.updateLinkOrder("source", "one", 3)
            awaitTables(notifications, setOf("source_image_links"))
            assertEquals(listOf("two", "one"), dao.getEnabledPlaybackImages().map { it.id })
        }
    }

    @Test fun exclusionInsertAndDeleteBothEmitAndRestoreTheCatalog() = runBlocking {
        withCatalogObserver { notifications ->
            dao.setDirectoryExcluded("source", "child", true, "2026-10-10T01:00:00Z")
            awaitTables(notifications, setOf("source_directory_exclusions"))
            assertEquals(listOf("two"), dao.getEnabledPlaybackImages().map { it.id })
            dao.setDirectoryExcluded("source", "child", false, "2026-10-10T02:00:00Z")
            awaitTables(notifications, setOf("source_directory_exclusions"))
            assertEquals(listOf("one", "two"), dao.getEnabledPlaybackImages().map { it.id })
        }
    }

    @Test fun oneRealTransactionEmitsAllChangedCatalogTablesWithoutInventingExactEventCount() = runBlocking {
        withCatalogObserver { notifications ->
            database.withTransaction {
                dao.setSourceEnabled("source", false, "2026-10-10T01:00:00Z")
                dao.updateImage("one", "file:///one.png", "transaction.png", "child/one.png",
                    null, null, null, "COMMITTED", "2026-10-10T01:00:00Z")
                dao.updateLinkOrder("source", "one", 4)
                dao.setDirectoryExcluded("source", "child", true, "2026-10-10T01:00:00Z")
            }
            awaitTables(notifications, observedTables)
            assertFalse(dao.getSource("source")!!.enabled)
            assertEquals("transaction.png", dao.getImage("one")!!.displayName)
            assertEquals(4, dao.getLinks("source").single { it.imageId == "one" }.sortOrder)
            assertEquals(listOf("child"), dao.getExclusions("source").map { it.directoryPath })
            assertTrue(dao.getEnabledPlaybackImages().isEmpty())
        }
    }

    private suspend fun withCatalogObserver(block: suspend (Channel<Set<String>>) -> Unit) = coroutineScope {
        val notifications = Channel<Set<String>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.IO) {
            database.invalidationTracker.createFlow(
                "sources", "image_records", "source_image_links", "source_directory_exclusions",
            ).collect { notifications.send(it) }
        }
        try {
            // Receiving the actual initial emission establishes registration before the DAO edit.
            assertEquals(observedTables, withTimeout(5_000) { notifications.receive() })
            block(notifications)
        } finally {
            observer.cancelAndJoin()
            notifications.close()
        }
    }

    private suspend fun awaitTables(notifications: Channel<Set<String>>, expected: Set<String>) {
        withTimeout(5_000) {
            val observed = mutableSetOf<String>()
            while (!observed.containsAll(expected)) observed += notifications.receive()
            assertTrue(observed.containsAll(expected))
        }
    }
}
