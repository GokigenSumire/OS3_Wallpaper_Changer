package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.UUID
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SQLite revision reads are the authority; a hot hit is not a target-device speed claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1RevisionCatalogReaderTest {
    private lateinit var database: ShimmerDatabase
    private lateinit var dao: P1SourceDao
    private lateinit var reader: P1RevisionCatalogReader
    private val reads = ConcurrentLinkedQueue<Read>()
    private var onRead: ((String) -> Unit)? = null
    private val namedDirectory = Files.createTempDirectory("p9rev-").toFile()

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShimmerDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(P1CatalogRevision.callback)
            .setQueryCallback({ sql, _ ->
                val normalized = sql.trim()
                if (normalized.startsWith("SELECT") || normalized.startsWith("PRAGMA schema_version")) {
                    reads.add(Read(normalized, database.inTransaction(), Thread.currentThread().id))
                    onRead?.invoke(normalized)
                }
            }, Executor { it.run() }).build()
        dao = database.p1SourceDao()
        reader = P1RevisionCatalogReader(database)
        // Open/install before tracing application reads rather than mixing setup SQL with hits.
        database.openHelper.writableDatabase
        reads.clear()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun repeatedUnchangedActionsVerifyStampAndCompleteTriggersButAvoidFullCatalogRows() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        reads.clear()
        val first = reader.getEnabledPlaybackImages()
        repeat(3) { assertSame(first, reader.getEnabledPlaybackImages()) }
        assertEquals(1, fullReads())
        assertEquals(4, stampReads())
        assertEquals(4, guardReads())
        assertTrue(reads.all { it.inTransaction })
        assertEquals(4, reads.count { it.sql.startsWith("PRAGMA schema_version") })
        // QueryCallback is an observation interceptor, not a transaction identity/ordering seam:
        // separate Room transactions may choose different executor workers and callback work may
        // be reported on another worker. Keep the strict real query counts and transaction checks.
        // Original full-reader tests independently cover source/link queries on one actual thread.
        assertEquals(dao.getEnabledPlaybackImages(), first)
    }

    @Test
    fun sourceEnableUpdatesAreFreshWithoutAnyListenerDelivery() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        assertEquals(listOf("a", "b"), reader.getEnabledPlaybackImages().map { it.id })
        dao.setSourceEnabled("a", false, null)
        assertEquals(listOf("b"), reader.getEnabledPlaybackImages().map { it.id })
        dao.setSourceEnabled("a", true, null)
        assertEquals(listOf("a", "b"), reader.getEnabledPlaybackImages().map { it.id })
        assertEquals(dao.getEnabledPlaybackImages(), reader.getEnabledPlaybackImages())
    }

    @Test
    fun everySourceMetadataFieldAndGlobalOrderChangeInvalidatesExactProjection() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        reader.getEnabledPlaybackImages()
        dao.updateSourceDefinition("a", "content://new/根", "MULTIPLE_DOCUMENTS", false, true, null)
        database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = 999 WHERE id = 'a'")
        val actual = reader.getEnabledPlaybackImages()
        assertEquals(listOf("b", "a"), actual.map { it.id })
        assertEquals("content://new/根", actual.last().sourceRootUri)
        assertEquals("MULTIPLE_DOCUMENTS", actual.last().sourceKind)
        assertEquals(999L, actual.last().sourceOrder)
        assertEquals(dao.getEnabledPlaybackImages(), actual)
    }

    @Test
    fun rawSqlImageMutationImmediatelyChangesUriNamePathAndCommittedPredicate() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        reader.getEnabledPlaybackImages()
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET uri = 'content://改/😀', " +
            "displayName = '改名.png', relativePath = '新/目录.png' WHERE id = 'one'")
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET metadataState = 'FAILED' WHERE id = 'two'")
        val actual = reader.getEnabledPlaybackImages().single()
        assertEquals("content://改/😀", actual.uri)
        assertEquals("改名.png", actual.displayName)
        assertEquals("新/目录.png", actual.relativePath)
        assertEquals(dao.getEnabledPlaybackImages().single(), actual)
    }

    @Test
    fun linkReorderAndNewLinksImmediatelyChangeTheOriginalCompleteKeyOrder() = runBlocking {
        seed("a", listOf(image("one"), image("two")))
        reader.getEnabledPlaybackImages()
        dao.updateLinkOrder("a", "two", -1)
        dao.insertImages(listOf(image("three")))
        dao.insertLinks(listOf(P1SourceImageLinkEntity("a", "three", 10)))
        assertEquals(listOf("two", "one", "three"), reader.getEnabledPlaybackImages().map { it.id })
        assertEquals(dao.getEnabledPlaybackImages(), reader.getEnabledPlaybackImages())
    }

    @Test
    fun subtreeExclusionInsertDeleteAndRawUpdateAreAuthoritative() = runBlocking {
        seed("a", listOf(image("one", "1/a.png"), image("ten", "10/b.png"), image("percent", "100%_/c.png")))
        assertEquals(3, reader.getEnabledPlaybackImages().size)
        dao.setDirectoryExcluded("a", "1", true, null)
        assertEquals(listOf("ten", "percent"), reader.getEnabledPlaybackImages().map { it.id })
        database.openHelper.writableDatabase.execSQL("UPDATE source_directory_exclusions SET directoryPath = '100%_' WHERE sourceId = 'a'")
        assertEquals(listOf("one", "ten"), reader.getEnabledPlaybackImages().map { it.id })
        dao.setDirectoryExcluded("a", "100%_", false, null)
        assertEquals(3, reader.getEnabledPlaybackImages().size)
    }

    @Test
    fun sourceDeleteCascadesLinksWithoutDeletingSharedCommittedImageRecords() = runBlocking {
        val shared = image("shared")
        seed("a", listOf(shared, image("a")))
        seed("b", listOf(shared, image("b")))
        assertEquals("a", reader.getEnabledPlaybackImages().first { it.id == "shared" }.sourceId)
        dao.deleteSource("a")
        val actual = reader.getEnabledPlaybackImages()
        assertEquals("b", actual.first { it.id == "shared" }.sourceId)
        assertEquals(3, dao.getAllImages().size)
        assertEquals(dao.getEnabledPlaybackImages(), actual)
    }

    @Test
    fun rawDeleteOfCommittedImageImmediatelyRemovesItsCascadeLinks() = runBlocking {
        seed("a", listOf(image("a"), image("b")))
        reader.getEnabledPlaybackImages()
        database.openHelper.writableDatabase.execSQL("DELETE FROM image_records WHERE id = 'a'")
        assertEquals(listOf("b"), reader.getEnabledPlaybackImages().map { it.id })
        assertEquals(dao.getEnabledPlaybackImages(), reader.getEnabledPlaybackImages())
    }

    @Test
    fun canonicalRuleMembershipSharesOnlyExactEquivalentKeysAndNotEnabledMode() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        dao.setSourceEnabled("b", false, null)
        reads.clear()
        val selected = reader.getRulePlaybackImages(listOf("b", "a", "a", "missing"))
        assertSame(selected, reader.getRulePlaybackImages(listOf("missing", "a", "b")))
        assertEquals(listOf("a", "b"), selected.map { it.id })
        assertEquals(listOf("a"), reader.getEnabledPlaybackImages().map { it.id })
        assertEquals(2, fullReads())
        assertEquals(dao.getRulePlaybackImages(listOf("a", "b", "missing")), selected)
    }

    @Test
    fun atMostTwoExactSelectionKeysAreRetainedWithLeastRecentlyUsedEviction() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        reads.clear()
        val enabled = reader.getEnabledPlaybackImages()
        val a = reader.getRulePlaybackImages(listOf("a"))
        reader.getRulePlaybackImages(listOf("b"))
        assertSame(a, reader.getRulePlaybackImages(listOf("a")))
        assertNotSame(enabled, reader.getEnabledPlaybackImages())
        assertEquals(4, fullReads())
    }

    @Test
    fun callersCannotMutateMemoRowsOrItsCanonicalRuleMembership() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        val ids = arrayListOf("a")
        val actual = reader.getRulePlaybackImages(ids)
        ids.add("b")
        try {
            (actual as MutableList<P1PlaybackImage>).clear()
            fail("A caller must not mutate authoritative memo rows")
        } catch (expected: UnsupportedOperationException) { /* Immutable result contract. */ }
        assertSame(actual, reader.getRulePlaybackImages(listOf("a")))
        assertEquals(2, reader.getRulePlaybackImages(ids).size)
    }

    @Test
    fun deletingAGuardDisablesHitsAndRestoringItCannotResurrectOldRows() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        val (name, sql) = imageUpdateGuard()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'unguarded.png' WHERE id = 'a'")
        reads.clear()
        assertEquals("unguarded.png", reader.getEnabledPlaybackImages().single().displayName)
        assertEquals("unguarded.png", reader.getEnabledPlaybackImages().single().displayName)
        assertEquals(2, fullReads())
        database.openHelper.writableDatabase.execSQL(sql)
        assertEquals("unguarded.png", reader.getEnabledPlaybackImages().single().displayName)
        assertEquals(3, fullReads())
        assertEquals("unguarded.png", reader.getEnabledPlaybackImages().single().displayName)
        assertEquals(3, fullReads())
    }

    @Test
    fun sameNamedNoOpGuardCannotCertifyFreshnessAndItsRecoveryStartsWithFullRead() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        val (name, sql) = imageUpdateGuard()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER $name AFTER UPDATE ON image_records BEGIN SELECT 1; END")
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'changed.png' WHERE id = 'a'")
        reads.clear()
        assertEquals("changed.png", reader.getEnabledPlaybackImages().single().displayName)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        database.openHelper.writableDatabase.execSQL(sql)
        assertEquals("changed.png", reader.getEnabledPlaybackImages().single().displayName)
        assertEquals(2, fullReads())
    }

    @Test
    fun guardDropWriteExactRecreateWithoutIntermediateReadCannotRevalidateOldMemo() = runBlocking {
        seed("a", listOf(image("a")))
        val before = reader.getEnabledPlaybackImages()
        val beforeStamp = database.withTransaction { P1CatalogRevision.readVerified(database)!! }
        val (name, sql) = imageUpdateGuard()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'gap-write.png' WHERE id = 'a'")
        database.openHelper.writableDatabase.execSQL(sql)
        // The reader never observed a missing/invalid guard. Revision/epoch remain the same, but
        // the persisted schema cookie changed with both DDL statements and defeats the old memo.
        val afterStamp = database.withTransaction { P1CatalogRevision.readVerified(database)!! }
        assertEquals(beforeStamp.epoch, afterStamp.epoch)
        assertEquals(beforeStamp.revision, afterStamp.revision)
        assertFalse(beforeStamp.schemaVersion == afterStamp.schemaVersion)
        reads.clear()
        val actual = reader.getEnabledPlaybackImages()
        assertNotSame(before, actual)
        assertEquals("gap-write.png", actual.single().displayName)
        assertEquals(1, fullReads())
        assertSame(actual, reader.getEnabledPlaybackImages())
        assertEquals(1, fullReads())
    }

    @Test
    fun renamedRevisionTableDisablesMemoButOriginalFullProjectionStillSucceeds() = runBlocking {
        seed("a", listOf(image("a")))
        val before = reader.getEnabledPlaybackImages()
        database.openHelper.writableDatabase.execSQL("ALTER TABLE ${P1CatalogRevision.TABLE} RENAME TO temporary_revision")
        reads.clear()
        try {
            repeat(2) {
                val actual = reader.getEnabledPlaybackImages()
                assertNotSame(before, actual)
                assertEquals(before, actual)
            }
            assertEquals(2, fullReads())
        } finally {
            database.openHelper.writableDatabase.execSQL("ALTER TABLE temporary_revision RENAME TO ${P1CatalogRevision.TABLE}")
        }
        assertEquals(dao.getEnabledPlaybackImages(), reader.getEnabledPlaybackImages())
    }

    @Test
    fun aRealMarkerOnlySqlExceptionFallsBackToFullRowsAndDoesNotKeepOldMemo() = runBlocking {
        seed("a", listOf(image("a")))
        val before = reader.getEnabledPlaybackImages()
        val support = database.openHelper.writableDatabase
        support.execSQL("ALTER TABLE ${P1CatalogRevision.TABLE} RENAME TO temporary_revision")
        support.execSQL("CREATE TABLE ${P1CatalogRevision.TABLE}(id INTEGER, epoch TEXT)")
        // SQLite rewrites trigger targets when a table is renamed. Restore the exact expected
        // SQL so the actual marker SELECT, not an earlier guard mismatch, is what fails here.
        for ((name, sql) in P1CatalogRevision.triggerDefinitions) {
            support.execSQL("DROP TRIGGER $name")
            support.execSQL(sql)
        }
        try {
            val markerFailure = runCatching {
                support.query("SELECT id, epoch, revision FROM ${P1CatalogRevision.TABLE}").use { it.moveToFirst() }
            }.exceptionOrNull()
            assertTrue(markerFailure is SQLiteException)
            assertTrue(markerFailure!!.message.orEmpty().contains("revision"))
            val fallback = reader.getEnabledPlaybackImages()
            assertNotSame(before, fallback)
            assertEquals(before, fallback)
        } finally {
            for (name in P1CatalogRevision.triggerDefinitions.keys) support.execSQL("DROP TRIGGER $name")
            support.execSQL("DROP TABLE ${P1CatalogRevision.TABLE}")
            support.execSQL("ALTER TABLE temporary_revision RENAME TO ${P1CatalogRevision.TABLE}")
            for (sql in P1CatalogRevision.triggerDefinitions.values) support.execSQL(sql)
        }
        val after = reader.getEnabledPlaybackImages()
        assertNotSame(before, after)
        assertSame(after, reader.getEnabledPlaybackImages())
    }

    @Test
    fun cancellationDuringStampVerificationPropagatesRatherThanAttemptingFallbackRows() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        var taskJob: Job? = null
        var cancelled = false
        onRead = { sql ->
            if (!cancelled && sql.contains("FROM sqlite_master") && sql.contains("name IN")) {
                cancelled = true
                taskJob!!.cancel(CancellationException("cancel marker verification"))
            }
        }
        reads.clear()
        val parent = SupervisorJob()
        val task = async(parent) { taskJob = currentCoroutineContext()[Job]!!; reader.getEnabledPlaybackImages() }
        try { task.await(); fail("Cancellation must not be swallowed as an unverified marker") }
        catch (expected: CancellationException) { assertTrue(expected.message.orEmpty().contains("cancel marker")) }
        finally { onRead = null; parent.cancel() }
        assertTrue(cancelled)
        assertEquals(0, fullReads())
        assertEquals(listOf("a"), reader.getEnabledPlaybackImages().map { it.id })
    }

    @Test
    fun epochChangesInvalidateSameNumberRevisionWithoutContentHashes() = runBlocking {
        seed("a", listOf(image("a")))
        val first = reader.getEnabledPlaybackImages()
        database.openHelper.writableDatabase.execSQL("UPDATE ${P1CatalogRevision.TABLE} SET epoch = 'new-database-epoch'")
        assertNotSame(first, reader.getEnabledPlaybackImages())
        assertEquals(first, reader.getEnabledPlaybackImages())
    }

    @Test
    fun negativeRevisionAndSaturatedMaximumAlwaysUseFullReads() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        for (revision in listOf(-1L, Long.MAX_VALUE)) {
            database.openHelper.writableDatabase.execSQL("UPDATE ${P1CatalogRevision.TABLE} SET revision = ?", arrayOf(revision))
            reads.clear()
            assertEquals(1, reader.getEnabledPlaybackImages().size)
            assertEquals(1, reader.getEnabledPlaybackImages().size)
            assertEquals(2, fullReads())
        }
    }

    @Test
    fun missingStampRowsAndExtraRowsDoNotBecomeEmptyOrTrustedHits() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        database.openHelper.writableDatabase.execSQL("DELETE FROM ${P1CatalogRevision.TABLE}")
        reads.clear()
        assertEquals(1, reader.getEnabledPlaybackImages().size)
        assertEquals(1, reader.getEnabledPlaybackImages().size)
        assertEquals(2, fullReads())
        P1CatalogRevision.install(database.openHelper.writableDatabase)
        database.openHelper.writableDatabase.execSQL("INSERT INTO ${P1CatalogRevision.TABLE}(id,epoch,revision) VALUES(1,'extra',0)")
        reads.clear()
        assertEquals(1, reader.getEnabledPlaybackImages().size)
        assertEquals(1, reader.getEnabledPlaybackImages().size)
        assertEquals(2, fullReads())
    }

    @Test
    fun callerOwnedRollbackNeverPublishesAProvisionalSameNumberRevision() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        try {
            database.withTransaction {
                dao.updateImage("a", "content://images/a", "provisional.png", "a.png", null, null, null, "COMMITTED", null)
                assertTrue(database.inTransaction())
                assertEquals("provisional.png", reader.getEnabledPlaybackImages().single().displayName)
                throw Rollback()
            }
        } catch (expected: Rollback) { /* Real SQLite transaction rollback. */ }
        // Exactly one update reuses the provisional revision number with different committed data.
        dao.updateImage("a", "content://images/a", "committed.png", "a.png", null, null, null, "COMMITTED", null)
        assertEquals("committed.png", reader.getEnabledPlaybackImages().single().displayName)
        assertEquals(dao.getEnabledPlaybackImages(), reader.getEnabledPlaybackImages())
    }

    @Test
    fun aReadInsideAnOuterTransactionBypassesAndInvalidatesPreexistingMemos() = runBlocking {
        seed("a", listOf(image("a")))
        val before = reader.getEnabledPlaybackImages()
        reads.clear()
        database.withTransaction {
            assertNotSame(before, reader.getEnabledPlaybackImages())
            assertNotSame(before, reader.getEnabledPlaybackImages())
        }
        assertNotSame(before, reader.getEnabledPlaybackImages())
        assertEquals(3, fullReads())
        val reloaded = reader.getEnabledPlaybackImages()
        assertSame(reloaded, reader.getEnabledPlaybackImages())
        assertEquals(3, fullReads())
    }

    @Test
    fun inheritedTransactionAcrossDispatcherNeverPublishesProvisionalRollbackRows() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        try {
            database.withTransaction {
                dao.updateImage("a", "content://images/a", "dispatcher-provisional.png", "a.png", null, null, null, "COMMITTED", null)
                withContext(Dispatchers.Default) {
                    // SQLite's platform transaction check is thread-local. The inherited Room
                    // transaction marker must still bypass the hot memo and publication here.
                    assertFalse(database.inTransaction())
                    assertEquals("dispatcher-provisional.png", reader.getEnabledPlaybackImages().single().displayName)
                }
                throw Rollback()
            }
        } catch (expected: Rollback) { /* Actual transaction rollback, not a mocked stamp. */ }
        dao.updateImage("a", "content://images/a", "dispatcher-committed.png", "a.png", null, null, null, "COMMITTED", null)
        assertEquals("dispatcher-committed.png", reader.getEnabledPlaybackImages().single().displayName)
    }

    @Test
    fun inheritedOuterTransactionDoesNotWaitOnMutexOwnedByReaderAwaitingItsSQLiteLock() = runBlocking {
        seed("a", listOf(image("a")))
        val independent = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val mutex = reader.javaClass.getDeclaredField("readMutex").apply { isAccessible = true }.get(reader) as Mutex
        try {
            lateinit var waiting: kotlinx.coroutines.Deferred<List<P1PlaybackImage>>
            database.withTransaction {
                waiting = independent.async { reader.getEnabledPlaybackImages() }
                withTimeout(10_000) { while (!mutex.isLocked) yield() }
                // The independent reader now owns its mutex but cannot start SQLite until this
                // outer transaction exits. The nested caller must bypass that mutex entirely.
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) { assertEquals(listOf("a"), reader.getEnabledPlaybackImages().map { it.id }) }
                }
            }
            withTimeout(10_000) { assertEquals(listOf("a"), waiting.await().map { it.id }) }
            assertEquals(dao.getEnabledPlaybackImages(), reader.getEnabledPlaybackImages())
        } finally { independent.cancel() }
    }

    @Test
    fun cancelledRealFullQueryPublishesNeitherPartialRowsNorANewRevisionMemo() = runBlocking {
        seed("a", (0 until 60).map { image("id-$it") })
        reader.getEnabledPlaybackImages()
        dao.updateImage("id-0", "content://images/id-0", "latest.png", "id-0.png", null, null, null, "COMMITTED", null)
        var taskJob: Job? = null
        var cancelled = false
        onRead = { sql ->
            if (!cancelled && sql.contains("FROM source_image_links l")) {
                cancelled = true
                taskJob!!.cancel(CancellationException("cancel real revision full read"))
            }
        }
        val parent = SupervisorJob()
        val task = async(parent) { taskJob = currentCoroutineContext()[Job]!!; reader.getEnabledPlaybackImages() }
        try { task.await(); fail("A cancelled full read must not publish its memo") }
        catch (expected: CancellationException) { assertTrue(expected.message.orEmpty().contains("cancel real")) }
        finally { onRead = null; parent.cancel() }
        assertTrue(cancelled)
        reads.clear()
        val actual = reader.getEnabledPlaybackImages()
        assertEquals(60, actual.size)
        assertEquals("latest.png", actual.first { it.id == "id-0" }.displayName)
        assertEquals(1, fullReads())
        assertSame(actual, reader.getEnabledPlaybackImages())
        assertEquals(1, fullReads())
    }

    @Test
    fun multipleConcurrentFreshCallsCannotPublishInconsistentOrMutableRows() = runBlocking {
        seed("a", (0 until 100).map { image("id-$it") })
        val results = (0 until 8).map { async(Dispatchers.Default) { reader.getEnabledPlaybackImages() } }.awaitAll()
        assertTrue(results.all { it.size == 100 })
        val baseline = dao.getEnabledPlaybackImages()
        results.forEach { assertEquals(baseline, it) }
        // Concurrent callers may observe another transaction and safely bypass; count is not a
        // mandatory speed result. After they settle, sequential calls must hit the verified memo.
        val settled = reader.getEnabledPlaybackImages()
        reads.clear()
        repeat(3) { assertSame(settled, reader.getEnabledPlaybackImages()) }
        assertEquals(0, fullReads())
        assertEquals(3, stampReads())
    }

    @Test
    fun separateReaderInstancesDoNotRelyOnEachOthersCachesOrRoomNotifications() = runBlocking {
        seed("a", listOf(image("a")))
        val other = P1RevisionCatalogReader(database)
        val first = reader.getEnabledPlaybackImages()
        assertNotSame(first, other.getEnabledPlaybackImages())
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'new.png' WHERE id = 'a'")
        assertEquals("new.png", other.getEnabledPlaybackImages().single().displayName)
        assertEquals("new.png", reader.getEnabledPlaybackImages().single().displayName)
    }

    @Test
    fun separateDatabaseConnectionsObserveRawWriterRevisionWithoutCrossInstanceListeners() = runBlocking {
        val name = "revision-reader-${UUID.randomUUID()}.db"
        val first = namedDatabase(name)
        first.openHelper.writableDatabase
        val second = namedDatabase(name)
        try {
            val localDao = first.p1SourceDao()
            localDao.registerSource(P1SourceEntity("a", "content://roots/a", "DIRECTORY_TREE"))
            localDao.insertImages(listOf(image("a")))
            localDao.insertLinks(listOf(P1SourceImageLinkEntity("a", "a", 0)))
            val localReader = P1RevisionCatalogReader(first)
            val before = localReader.getEnabledPlaybackImages()
            assertSame(before, localReader.getEnabledPlaybackImages())
            // No multi-instance invalidation and no Room DAO call: persistent SQLite triggers
            // make this writer visible to the next reader's actual transaction marker read.
            second.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'other-connection.png' WHERE id = 'a'")
            val after = localReader.getEnabledPlaybackImages()
            assertNotSame(before, after)
            assertEquals("other-connection.png", after.single().displayName)
            assertSame(after, localReader.getEnabledPlaybackImages())
        } finally { second.close(); first.close() }
    }

    @Test
    fun anotherConnectionRepairRotatesEpochAndCannotRevalidateOldMissedWriteMemo() = runBlocking {
        val name = "revision-repair-${UUID.randomUUID()}.db"
        val first = namedDatabase(name)
        first.openHelper.writableDatabase
        var other = namedDatabase(name)
        try {
            val localDao = first.p1SourceDao()
            localDao.registerSource(P1SourceEntity("a", "content://roots/a", "DIRECTORY_TREE"))
            localDao.insertImages(listOf(image("a")))
            localDao.insertLinks(listOf(P1SourceImageLinkEntity("a", "a", 0)))
            val localReader = P1RevisionCatalogReader(first)
            val before = localReader.getEnabledPlaybackImages()
            val beforeStamp = first.withTransaction { P1CatalogRevision.readVerified(first)!! }
            val (guard, _) = imageUpdateGuard()
            other.openHelper.writableDatabase.execSQL("DROP TRIGGER $guard")
            other.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'missed-write.png' WHERE id = 'a'")
            // The first reader never sees the missing guard. Another normal database open must
            // atomically repair all guards AND rotate epoch before it can certify any hot hit.
            other.close()
            other = namedDatabase(name)
            other.openHelper.writableDatabase
            val afterStamp = first.withTransaction { P1CatalogRevision.readVerified(first)!! }
            assertFalse(beforeStamp.epoch == afterStamp.epoch)
            val after = localReader.getEnabledPlaybackImages()
            assertNotSame(before, after)
            assertEquals("missed-write.png", after.single().displayName)
        } finally { other.close(); first.close() }
    }

    @Test
    fun failedAuthoritativeSqlIsNeverReturnedAsEmptyOrLastGoodMemo() = runBlocking {
        seed("a", listOf(image("a")))
        reader.getEnabledPlaybackImages()
        database.openHelper.writableDatabase.execSQL("ALTER TABLE source_image_links RENAME TO temporary_links")
        try {
            reader.getEnabledPlaybackImages()
            fail("Real SQL failure must not be replaced by a hot memo or empty catalog")
        } catch (failed: SQLiteException) {
            assertTrue(failed.message.orEmpty().contains("source_image_links"))
        } finally {
            database.openHelper.writableDatabase.execSQL("ALTER TABLE temporary_links RENAME TO source_image_links")
        }
        assertEquals(1, reader.getEnabledPlaybackImages().size)
        assertEquals(dao.getEnabledPlaybackImages(), reader.getEnabledPlaybackImages())
    }

    @Test
    fun full11890ProjectionRetainsOldBinaryTiesSharedOwnershipAndFieldsButHotReadSkipsRows() = runBlocking {
        for (source in 0 until 4) {
            seed("source-$source", (source until 11890 step 4).map { image("image-$it", "目录/😀/$it.png") })
            database.openHelper.writableDatabase.execSQL("UPDATE sources SET sourceOrder = ? WHERE id = ?", arrayOf<Any>(source / 2, "source-$source"))
        }
        database.openHelper.writableDatabase.execSQL("UPDATE source_image_links SET sortOrder = sortOrder / 3")
        dao.setDirectoryExcluded("source-1", "目录/不存在", true, null)
        reads.clear()
        val result = reader.getEnabledPlaybackImages()
        assertEquals(11890, result.size)
        assertEquals(dao.getEnabledPlaybackImages(), result)
        reads.clear()
        assertSame(result, reader.getEnabledPlaybackImages())
        assertEquals(0, fullReads())
        assertEquals(1, stampReads())
        assertEquals(1, guardReads())
        assertEquals(8, database.openHelper.readableDatabase.version)
    }

    @Test
    fun scanFailureStateCannotDeleteCommittedRowsOrReuseOldSourceMetadata() = runBlocking {
        seed("a", listOf(image("a")))
        val before = reader.getEnabledPlaybackImages()
        dao.updateSourceState("a", "METADATA_FAILURE", "DENIED", null, "temporary", null)
        val after = reader.getEnabledPlaybackImages()
        assertNotSame(before, after)
        assertEquals(before, after)
        assertEquals(1, dao.getAllImages().size)
    }

    @Test
    fun emptyRuleInputKeepsOriginalNoQueryContractRatherThanPretendingAnAuthorityHit() = runBlocking {
        seed("a", listOf(image("a")))
        // Acquire the real SQLite transaction after setup. A seed's asynchronous Room
        // invalidation SELECT cannot enter this no-query observation window from another worker.
        // QueryCallback remains an observation interceptor, not proof of SQL execution threading.
        database.withTransaction {
            reads.clear()
            assertTrue(reader.getRulePlaybackImages(emptyList()).isEmpty())
            assertTrue(reads.isEmpty())
        }
    }

    @Test
    fun aValidStampNeverPermitsDifferentExactRuleMembershipToReusePreviousRows() = runBlocking {
        seed("a", listOf(image("a")))
        seed("b", listOf(image("b")))
        val a = reader.getRulePlaybackImages(listOf("a"))
        val b = reader.getRulePlaybackImages(listOf("b"))
        assertFalse(a == b)
        assertSame(a, reader.getRulePlaybackImages(listOf("a", "a")))
        assertSame(b, reader.getRulePlaybackImages(listOf("b", "b")))
    }

    private suspend fun seed(source: String, images: List<P1ImageRecordEntity>) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.insertImages(images)
        dao.insertLinks(images.mapIndexed { index, image -> P1SourceImageLinkEntity(source, image.id, index) })
    }

    private fun image(id: String, path: String = "$id.png") =
        P1ImageRecordEntity(id, "content://images/$id", path.substringAfterLast('/'), path)

    private fun namedDatabase(name: String): ShimmerDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Windows sqlite4java cannot open Robolectric's long test-method sandbox path (>260).
        // A real short system temporary directory still gives both Room instances one physical
        // SQLite file; no mocked connection or file/database deletion is used.
        val path = File(namedDirectory, name).absolutePath
        return Room.databaseBuilder(context, ShimmerDatabase::class.java, path)
            .allowMainThreadQueries().addCallback(P1CatalogRevision.callback).build()
    }

    private fun imageUpdateGuard() = P1CatalogRevision.triggerDefinitions.entries.single {
        it.key.contains("image_records") && it.key.endsWith("update")
    }.let { it.key to it.value }

    private fun fullReads() = reads.count { it.sql.startsWith("SELECT * FROM sources") }
    private fun stampReads() = reads.count { it.sql.contains("FROM ${P1CatalogRevision.TABLE}") }
    private fun guardReads() = reads.count { it.sql.contains("FROM sqlite_master") && it.sql.contains("name IN") }
    private data class Read(val sql: String, val inTransaction: Boolean, val thread: Long)
    private class Rollback : RuntimeException()
}
