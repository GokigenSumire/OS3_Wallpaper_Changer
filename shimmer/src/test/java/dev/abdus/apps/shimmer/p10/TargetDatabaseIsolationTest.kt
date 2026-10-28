package dev.abdus.apps.shimmer.p10

import android.app.Application
import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.FolderEntity
import dev.abdus.apps.shimmer.database.ImageEntity
import dev.abdus.apps.shimmer.database.P1CatalogRevision
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1RevisionCatalogReader
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p1.P1ImportState
import dev.abdus.apps.shimmer.p1.P1SourceRepository
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p4.PlaybackRule
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import dev.abdus.apps.shimmer.p4.ScheduleStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import org.robolectric.shadows.ShadowContentResolver

/** Actual named Room databases, production repositories, transactional guards and persisted files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TargetDatabaseIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val home = ShimmerDatabase.getInstance(context)
    private val lock = ShimmerDatabase.getInstance(context, WallpaperTarget.LOCK)
    private val stoppedScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private fun repository(target: WallpaperTarget) = ImageFolderRepository(context, stoppedScope, target)

    @After fun closeOnlyTestDatabases() {
        stoppedScope.cancel()
        home.close()
        lock.close()
        listOf("INSTANCE", "LOCK_INSTANCE").forEach { field ->
            ShimmerDatabase::class.java.getDeclaredField(field).apply { isAccessible = true; set(null, null) }
        }
    }

    @Test fun defaultHomePreservesOriginalDatabaseNameAndEachTargetHasOneDistinctInstance() = runBlocking {
        assertEquals("shimmer_database", ShimmerDatabase.nameFor(WallpaperTarget.HOME))
        assertEquals("p10_lock_database", ShimmerDatabase.nameFor(WallpaperTarget.LOCK))
        assertSame(home, ShimmerDatabase.getInstance(context))
        assertSame(home, ShimmerDatabase.getInstance(context, WallpaperTarget.HOME))
        assertSame(lock, ShimmerDatabase.getInstance(context, WallpaperTarget.LOCK))
        assertNotSame(home, lock)
        assertEquals(8, home.openHelper.writableDatabase.version)
        assertEquals(8, lock.openHelper.writableDatabase.version)
        assertNotEquals(home.openHelper.writableDatabase.path, lock.openHelper.writableDatabase.path)
    }

    @Test fun newLockGraphIsEmptyAndDoesNotCopyDisabledHomeSourcesOrExclusions() = runBlocking {
        seed(home, "source", "home", enabled = false)
        home.p1SourceDao().setDirectoryExcluded("source", "private", true, "original")
        val original = home.p1SourceDao().getSources()
        assertTrue(lock.p1SourceDao().getSources().isEmpty())
        assertTrue(lock.p1SourceDao().getAllImages().isEmpty())
        assertTrue(lock.p1SourceDao().getAllLinks().isEmpty())
        assertTrue(lock.p1SourceDao().getAllExclusions().isEmpty())
        assertTrue(repository(WallpaperTarget.LOCK).getPlaybackItems().isEmpty())
        assertEquals(original, home.p1SourceDao().getSources())
        assertFalse(home.p1SourceDao().getSource("source")!!.enabled)
        assertEquals("private", home.p1SourceDao().getAllExclusions().single().directoryPath)
    }

    @Test fun sameSourceIdentityHasIndependentEnableDirectoryExclusionsAndInsertionOrder() = runBlocking {
        seed(home, "same", "home", path = "child/p.png")
        seed(home, "home-second", "other-home")
        seed(lock, "lock-first", "other-lock")
        seed(lock, "same", "lock", path = "child/p.png")
        val homeRepository = P1SourceRepository(context)
        val lockRepository = P1SourceRepository(context, target = WallpaperTarget.LOCK)
        lockRepository.setEnabled("same", false)
        lockRepository.setDirectoryEnabled("same", "child", false)
        assertTrue(home.p1SourceDao().getSource("same")!!.enabled)
        assertFalse(lock.p1SourceDao().getSource("same")!!.enabled)
        assertTrue(homeRepository.excludedDirectories("same").isEmpty())
        assertEquals(setOf("child"), lockRepository.excludedDirectories("same"))
        assertEquals(listOf("same", "home-second"), home.p1SourceDao().getSources().sortedBy { it.sourceOrder }.map { it.id })
        assertEquals(listOf("lock-first", "same"), lock.p1SourceDao().getSources().sortedBy { it.sourceOrder }.map { it.id })
        lockRepository.setEnabled("same", true)
        assertEquals(listOf("other-lock"), repository(WallpaperTarget.LOCK).getPlaybackItems().map { it.displayName.removeSuffix(".png") })
        assertEquals(2, repository(WallpaperTarget.HOME).getPlaybackItems().size)
    }

    @Test fun deletingLockMemberOnlyForgetsLockResumePointAndPreservesHomeSourceAndImages() = runBlocking {
        seed(home, "same", "home")
        seed(lock, "same", "lock")
        val state = PlaybackSnapshot(sourcePositions = mapOf("same" to "image"),
            sourceHistoryCursors = mapOf("same" to 3), resumeSourceIds = listOf("same"))
        PlaybackSnapshotStore(context).save(state)
        PlaybackSnapshotStore(context, WallpaperTarget.LOCK).save(state)
        assertTrue(P1SourceRepository(context, target = WallpaperTarget.LOCK).deleteSource("same"))
        assertFalse(P1SourceRepository(context, target = WallpaperTarget.LOCK).deleteSource("same"))
        assertNotNull(home.p1SourceDao().getSource("same"))
        assertEquals(1, home.p1SourceDao().getSourceImages("same").size)
        assertTrue(lock.p1SourceDao().getSources().isEmpty())
        assertTrue(lock.p1SourceDao().getAllLinks().isEmpty())
        assertEquals(1, lock.p1SourceDao().getAllImages().size)
        assertEquals(state, PlaybackSnapshotStore(context).load())
        val lockState = PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load()!!
        assertTrue(lockState.sourcePositions.isEmpty())
        assertTrue(lockState.sourceHistoryCursors.isEmpty())
        assertTrue(lockState.resumeSourceIds.isEmpty())
    }

    @Test fun globalSpecialFoldersAreNeverAutoInsertedOrExplicitlySyncedIntoLock() = runBlocking {
        WallpaperPreferences.create(context).setFavoritesFolderUri(Uri.parse("content://home-special/favorites"))
        WallpaperPreferences.create(context).setSharedFolderUri(Uri.parse("content://home-special/shared"))
        repository(WallpaperTarget.LOCK).registerSpecialFolders()
        assertTrue(lock.imageDao().getAllFoldersFlow().first().isEmpty())
        assertTrue(lock.p1SourceDao().getSources().isEmpty())
        repository(WallpaperTarget.HOME).registerSpecialFolders()
        assertEquals(2, home.imageDao().getAllFoldersFlow().first().size)
        assertTrue(lock.imageDao().getAllFoldersFlow().first().isEmpty())
    }

    @Test fun lockNeverFallsBackToLegacyRowsEvenWhenItsOwnP1GraphIsEmpty() = runBlocking {
        legacy(home, "home")
        legacy(lock, "lock-legacy")
        assertEquals(listOf("file:///legacy/home.png"), repository(WallpaperTarget.HOME).getPlaybackItems().map { it.id })
        val lockRepository = repository(WallpaperTarget.LOCK)
        val before = lock.imageDao().getLatestShownImage()
        assertTrue(lockRepository.getPlaybackItems().isEmpty())
        assertNull(lockRepository.nextImageUri())
        assertEquals(before, lock.imageDao().getLatestShownImage())
    }

    @Test fun lockCurrentIsItsCommittedSnapshotNotLastShownLegacyMetadata() = runBlocking {
        legacy(home, "home")
        legacy(lock, "lock-legacy")
        home.imageDao().updateLastShownByUri(Uri.parse("file:///legacy/home.png"), "2026-10-17")
        lock.imageDao().updateLastShownByUri(Uri.parse("file:///legacy/lock-legacy.png"), "2026-10-17")
        val lockRepository = repository(WallpaperTarget.LOCK)
        assertNull(lockRepository.getCurrentImageUri())
        assertNull(lockRepository.currentImageUriFlow.first())
        PlaybackSnapshotStore(context, WallpaperTarget.LOCK).save(PlaybackSnapshot(
            historyIds = listOf("content://lock/committed"), historyCursor = 0))
        assertEquals(Uri.parse("content://lock/committed"), lockRepository.getCurrentImageUri())
        assertEquals(Uri.parse("content://lock/committed"), lockRepository.currentImageUriFlow.first())
        assertEquals(Uri.parse("file:///legacy/home.png"), repository(WallpaperTarget.HOME).getCurrentImageUri())
    }

    @Test fun lockCatalogReadsOwnDisabledSetWithoutAffectingSameUriAtHome() = runBlocking {
        seed(home, "same", "image", uri = "content://shared/image")
        seed(lock, "same", "image", uri = "content://shared/image")
        WallpaperPreferences.create(context, WallpaperTarget.LOCK).setImageEnabled("content://shared/image", false)
        assertTrue(repository(WallpaperTarget.LOCK).getPlaybackItems().isEmpty())
        assertEquals(listOf("content://shared/image"), repository(WallpaperTarget.HOME).getPlaybackItems().map { it.id })
        WallpaperPreferences.create(context, WallpaperTarget.LOCK).clearDisabledImages()
        WallpaperPreferences.create(context).setImageEnabled("content://shared/image", false)
        assertEquals(1, repository(WallpaperTarget.LOCK).getPlaybackItems().size)
        assertTrue(repository(WallpaperTarget.HOME).getPlaybackItems().isEmpty())
    }

    @Test fun effectiveRulesResolveOnlyTheirOwnSourceGraphAndScheduleFile() = runBlocking {
        seed(home, "a", "home-a"); seed(home, "b", "home-b")
        seed(lock, "a", "lock-a"); seed(lock, "b", "lock-b")
        ScheduleStore(context).saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("home-rule", sourceIds = listOf("a")))))
        ScheduleStore(context, WallpaperTarget.LOCK).saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("lock-rule", sourceIds = listOf("b")))))
        val homeCatalog = repository(WallpaperTarget.HOME).getPlaybackCatalog()
        val lockCatalog = repository(WallpaperTarget.LOCK).getPlaybackCatalog()
        assertEquals("home-rule", homeCatalog.activeRule!!.id)
        assertEquals("lock-rule", lockCatalog.activeRule!!.id)
        assertEquals(listOf("home-a.png"), homeCatalog.items.map { it.displayName })
        assertEquals(listOf("lock-b.png"), lockCatalog.items.map { it.displayName })
    }

    @Test fun twoRevisionReadersCannotShareMemoEvenWithIdenticalEpochRevisionAndIds() = runBlocking {
        seed(home, "same", "image", uri = "content://home/image")
        seed(lock, "same", "image", uri = "content://lock/image")
        listOf(home, lock).forEach {
            it.openHelper.writableDatabase.execSQL("UPDATE playback_catalog_revision SET epoch = 'same-fixture-epoch', revision = 7 WHERE id = 0")
        }
        val homeReader = P1RevisionCatalogReader(home)
        val lockReader = P1RevisionCatalogReader(lock)
        val homeRows = homeReader.getEnabledPlaybackImages()
        val lockRows = lockReader.getEnabledPlaybackImages()
        assertSame(homeRows, homeReader.getEnabledPlaybackImages())
        assertSame(lockRows, lockReader.getEnabledPlaybackImages())
        assertNotSame(homeRows, lockRows)
        assertEquals("content://home/image", homeRows.single().uri)
        assertEquals("content://lock/image", lockRows.single().uri)
        lock.p1SourceDao().setSourceEnabled("same", false, null)
        assertTrue(lockReader.getEnabledPlaybackImages().isEmpty())
        assertSame(homeRows, homeReader.getEnabledPlaybackImages())
    }

    @Test fun missingLockTriggerAndRolledBackRowsNeverInvalidateOrPolluteHomeMemo() = runBlocking {
        seed(home, "same", "home")
        seed(lock, "same", "lock")
        val homeReader = P1RevisionCatalogReader(home)
        val lockReader = P1RevisionCatalogReader(lock)
        val originalHome = homeReader.getEnabledPlaybackImages()
        val trigger = P1CatalogRevision.triggerDefinitions.keys.first { it.contains("image_records") && it.endsWith("update") }
        lock.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
        val first = lockReader.getEnabledPlaybackImages()
        assertNotSame(first, lockReader.getEnabledPlaybackImages())
        try {
            lock.withTransaction {
                lock.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'uncommitted.png'")
                assertEquals("uncommitted.png", lockReader.getEnabledPlaybackImages().single().displayName)
                throw Rollback()
            }
        } catch (_: Rollback) { }
        assertEquals("lock.png", lockReader.getEnabledPlaybackImages().single().displayName)
        assertSame(originalHome, homeReader.getEnabledPlaybackImages())
    }

    @Test fun successfulDirectoryImportUsesOnlyExplicitTargetAndKeepsSameUriIndependent() = runBlocking {
        val directory = directory("both")
        File(directory, "image.png").writeBytes(PNG)
        val uri = Uri.fromFile(directory)
        val homeResult = P1SourceRepository(context).importDirectory(uri)
        assertEquals(P1ImportState.COMMITTED, homeResult.state)
        assertTrue(lock.p1SourceDao().getSources().isEmpty())
        val lockResult = P1SourceRepository(context, target = WallpaperTarget.LOCK).importDirectory(uri)
        assertEquals(P1ImportState.COMMITTED, lockResult.state)
        assertEquals(homeResult.sourceId, lockResult.sourceId)
        assertEquals(1, home.p1SourceDao().getSourceImages(homeResult.sourceId).size)
        assertEquals(1, lock.p1SourceDao().getSourceImages(lockResult.sourceId).size)
        P1SourceRepository(context, target = WallpaperTarget.LOCK).setEnabled(lockResult.sourceId, false)
        assertTrue(home.p1SourceDao().getSource(homeResult.sourceId)!!.enabled)
    }

    @Test fun cancelledLockRescanPreservesBothCommittedGraphsAndOnlyMarksLockCancelled() = runBlocking {
        val directory = directory("cancel")
        File(directory, "image.png").writeBytes(PNG)
        val uri = Uri.fromFile(directory)
        val homeRepository = P1SourceRepository(context)
        val lockRepository = P1SourceRepository(context, target = WallpaperTarget.LOCK)
        val homeResult = homeRepository.importDirectory(uri)
        val lockResult = lockRepository.importDirectory(uri)
        val homeBefore = home.p1SourceDao().getSources()
        val lockImages = lock.p1SourceDao().getSourceImages(lockResult.sourceId)
        val result = lockRepository.rescan(lockResult.sourceId) { throw CancellationException("fixture cancellation") }
        assertEquals(P1ImportState.CANCELLED, result.state)
        assertEquals(homeBefore, home.p1SourceDao().getSources())
        assertEquals(1, home.p1SourceDao().getSourceImages(homeResult.sourceId).size)
        assertEquals(lockImages, lock.p1SourceDao().getSourceImages(lockResult.sourceId))
        assertEquals("CANCELLED", lock.p1SourceDao().getSource(lockResult.sourceId)!!.scanState)
    }

    @Test fun failedLockAuthorizationDoesNotDeleteEitherDomainsCommittedLinks() = runBlocking {
        val root = "content://no-test-provider/tree/root"
        seed(home, "same", "home", root = root)
        seed(lock, "same", "lock", root = root)
        val homeBefore = home.p1SourceDao().getSources()
        val lockBefore = lock.p1SourceDao().getSourceImages("same")
        val result = P1SourceRepository(context, target = WallpaperTarget.LOCK).rescan("same")
        assertEquals(P1ImportState.PERMISSION_REQUIRED, result.state)
        assertEquals(homeBefore, home.p1SourceDao().getSources())
        assertEquals(lockBefore, lock.p1SourceDao().getSourceImages("same"))
        assertEquals(1, home.p1SourceDao().getAllLinks().size)
        assertEquals(1, lock.p1SourceDao().getAllLinks().size)
    }

    @Test fun temporarilyUndecodableLockImagePreservesCommittedMetadataAndHome() = runBlocking {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val directory = directory("decode")
        val image = File(directory, "image.png").apply { writeBytes(PNG) }
        val uri = Uri.fromFile(directory)
        val homeRepository = P1SourceRepository(context)
        val lockRepository = P1SourceRepository(context, target = WallpaperTarget.LOCK)
        val homeResult = homeRepository.importDirectory(uri)
        val lockResult = lockRepository.importDirectory(uri)
        val homeRows = home.p1SourceDao().getSourceImages(homeResult.sourceId)
        val lockRows = lock.p1SourceDao().getSourceImages(lockResult.sourceId)
        image.writeBytes("temporarily not an image".toByteArray())
        val result = lockRepository.rescan(lockResult.sourceId)
        assertTrue(result.state != P1ImportState.COMMITTED)
        assertEquals(homeRows, home.p1SourceDao().getSourceImages(homeResult.sourceId))
        assertEquals(lockRows, lock.p1SourceDao().getSourceImages(lockResult.sourceId))
        assertEquals("COMMITTED", home.p1SourceDao().getSource(homeResult.sourceId)!!.scanState)
    }

    @Test fun reauthorizingSameDocumentIntoLockCannotReplaceCommittedHomeCopyBytes() = runBlocking {
        val (selected, supplied) = selectedDocument("copied-both")
        val homeRepository = P1SourceRepository(context)
        val lockRepository = P1SourceRepository(context, target = WallpaperTarget.LOCK)
        val homeResult = homeRepository.importSingleDocument(selected)
        assertEquals(P1ImportState.COMMITTED, homeResult.state)
        val homeRecord = home.p1SourceDao().getSourceImages(homeResult.sourceId).single()
        val homeCopy = File(requireNotNull(Uri.parse(homeRecord.uri).path))
        assertEquals("p1-images", homeCopy.parentFile!!.name)
        val original = homeCopy.readBytes()
        supplied.writeBytes(PNG + byteArrayOf(0, 1, 2))
        val lockResult = lockRepository.importSingleDocument(selected)
        assertEquals(P1ImportState.COMMITTED, lockResult.state)
        val lockRecord = lock.p1SourceDao().getSourceImages(lockResult.sourceId).single()
        val lockCopy = File(requireNotNull(Uri.parse(lockRecord.uri).path))
        assertEquals("p10-lock-images", lockCopy.parentFile!!.name)
        assertNotEquals(homeCopy.absolutePath, lockCopy.absolutePath)
        assertArrayEquals(original, homeCopy.readBytes())
        assertArrayEquals(supplied.readBytes(), lockCopy.readBytes())
        assertEquals(homeRecord, home.p1SourceDao().getSourceImages(homeResult.sourceId).single())
        assertTrue(lockRepository.deleteSource(lockResult.sourceId))
        assertTrue(homeCopy.isFile)
        assertArrayEquals(original, homeCopy.readBytes())
    }

    @Test fun invalidLockCopyReplacementPreservesBothCommittedCopiesAndRecords() = runBlocking {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val (selected, supplied) = selectedDocument("copied-invalid")
        val homeRepository = P1SourceRepository(context)
        val lockRepository = P1SourceRepository(context, target = WallpaperTarget.LOCK)
        val homeResult = homeRepository.importSingleDocument(selected)
        val lockResult = lockRepository.importSingleDocument(selected)
        assertEquals(P1ImportState.COMMITTED, homeResult.state)
        assertEquals(P1ImportState.COMMITTED, lockResult.state)
        val homeRecord = home.p1SourceDao().getSourceImages(homeResult.sourceId).single()
        val lockRecord = lock.p1SourceDao().getSourceImages(lockResult.sourceId).single()
        val homeCopy = File(requireNotNull(Uri.parse(homeRecord.uri).path))
        val lockCopy = File(requireNotNull(Uri.parse(lockRecord.uri).path))
        val homeBytes = homeCopy.readBytes()
        val lockBytes = lockCopy.readBytes()
        supplied.writeBytes("temporary provider decode failure".toByteArray())
        assertEquals(P1ImportState.METADATA_FAILED, lockRepository.importSingleDocument(selected).state)
        assertArrayEquals(homeBytes, homeCopy.readBytes())
        assertArrayEquals(lockBytes, lockCopy.readBytes())
        assertEquals(homeRecord, home.p1SourceDao().getSourceImages(homeResult.sourceId).single())
        assertEquals(lockRecord, lock.p1SourceDao().getSourceImages(lockResult.sourceId).single())
    }

    private suspend fun seed(database: ShimmerDatabase, source: String, image: String,
        enabled: Boolean = true, path: String = "$image.png", uri: String = "content://images/$image",
        root: String = "content://roots/$source") {
        val dao = database.p1SourceDao()
        dao.registerSource(P1SourceEntity(source, root, "DIRECTORY_TREE", enabled = enabled))
        dao.commitScan(source, listOf(P1ImageRecordEntity(image, uri, "$image.png", path)), "APP_OWNED", "2026-10-17")
    }
    private suspend fun legacy(database: ShimmerDatabase, name: String) {
        val dao = database.imageDao()
        val folder = dao.insertFolder(FolderEntity(uri = Uri.parse("file:///legacy/$name"), createdAt = "created"))
        dao.insertImages(listOf(ImageEntity(folderId = folder, uri = Uri.parse("file:///legacy/$name.png"), createdAt = "created")))
    }
    private fun directory(label: String): File = File(context.cacheDir, "p10-$label-${System.nanoTime()}").apply { mkdirs() }
    private fun selectedDocument(label: String): Pair<Uri, File> {
        val supplied = File(directory(label), "provider.png").apply { writeBytes(PNG) }
        val authority = "p10.domain.fixture"
        val provider = DocumentProvider(supplied)
        ShadowContentResolver.registerProviderInternal(authority, provider)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        return Uri.parse("content://$authority/document/image") to supplied
    }
    private class DocumentProvider(private val image: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> when (column) {
                    OpenableColumns.DISPLAY_NAME -> "chosen.png"
                    OpenableColumns.SIZE -> image.length()
                    else -> null
                } }.toTypedArray<Any?>())
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only test provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
    }
    private class Rollback : RuntimeException()
    private companion object {
        val PNG: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    }
}
