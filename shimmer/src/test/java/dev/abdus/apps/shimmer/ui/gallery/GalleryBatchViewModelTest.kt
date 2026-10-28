package dev.abdus.apps.shimmer.ui.gallery

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.file.Files

/** 真 Room、偏好和播放目录路径；不把纯界面模型测试当成持久化或设备证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryBatchViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(application)
    private val preferences = WallpaperPreferences.create(application)
    private val stores = mutableListOf<ViewModelStore>()
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun clearViewModelsAndDatabase() {
        stores.forEach { it.clear() }
        playbackScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun groupsStartCollapsedAndPageWithoutDroppingTheirAssociations() {
        seedBatch(count = 125)
        val viewModel = loadedViewModel()
        assertTrue(source(viewModel).batchPage.rows.isEmpty())
        viewModel.toggleBatchExpanded("batch")
        assertEquals(50, source(viewModel).batchPage.rows.size)
        viewModel.moveBatchPage("batch", 1)
        assertEquals(51, source(viewModel).batchPage.rows.first().position)
        viewModel.toggleBatchExpanded("batch")
        viewModel.toggleBatchExpanded("batch")
        assertEquals(1, source(viewModel).batchPage.pageIndex)
        assertEquals(125, runBlocking { database.p1SourceDao().getSourceImages("batch").size })
    }

    @Test
    fun stoppingAnImagePersistsAndChangesTheEffectiveCatalogButNotLinksOrManualSnapshot() {
        seedBatch()
        val before = PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf(uri(3), uri(1), uri(2)), currentId = uri(1))
        val snapshotStore = PlaybackSnapshotStore(application)
        snapshotStore.save(before)
        val viewModel = loadedViewModel()
        viewModel.toggleBatchExpanded("batch")
        viewModel.setBatchImageEnabled("batch", uri(2), false)
        awaitCondition {
            uri(2) in preferences.getDisabledImages() && source(viewModel).batchPage.stoppedCount == 1 &&
                viewModel.uiState.value.message != null
        }
        assertFalse(source(viewModel).batchPage.rows[1].enabled)
        assertEquals(3, runBlocking { database.p1SourceDao().getSourceImages("batch").size })
        assertEquals(before, snapshotStore.load())
        val catalog = runBlocking { ImageFolderRepository(application, playbackScope).getPlaybackItems() }
        assertEquals(listOf(uri(1), uri(3)), catalog.map { it.id })
        assertEquals(before, snapshotStore.load())

        viewModel.messageShown()
        viewModel.setBatchImageEnabled("batch", uri(2), true)
        awaitCondition { uri(2) !in preferences.getDisabledImages() && source(viewModel).batchPage.stoppedCount == 0 }
        assertEquals(before, snapshotStore.load())
    }

    @Test
    fun aRecreatedViewModelReadsThePersistedStopRecord() {
        seedBatch()
        val first = loadedViewModel()
        first.setBatchImageEnabled("batch", uri(1), false)
        awaitCondition { uri(1) in preferences.getDisabledImages() }
        stores.first().clear()
        val second = loadedViewModel()
        second.toggleBatchExpanded("batch")
        assertFalse(source(second).batchPage.rows.first().enabled)
        assertEquals(1, source(second).batchPage.stoppedCount)
    }

    @Test
    fun aDisabledSourceDisablesChildControlsAndRejectsWrites() {
        seedBatch(enabled = false)
        val viewModel = loadedViewModel()
        viewModel.toggleBatchExpanded("batch")
        assertTrue(source(viewModel).batchPage.rows.none { it.canToggle })
        viewModel.setBatchImageEnabled("batch", uri(1), false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(preferences.getDisabledImages().isEmpty())
    }

    @Test
    fun aStaleEnabledCardCannotWriteAfterTheSourceWasStoppedInRoom() {
        seedBatch()
        val viewModel = loadedViewModel()
        runBlocking { database.p1SourceDao().setSourceEnabled("batch", false, null) }
        viewModel.setBatchImageEnabled("batch", uri(1), false)
        // 必须等本次写入尝试结束；仅等 Room 来源 Flow 刷新会提前断言，无法证明后台 guard。
        awaitCondition { viewModel.uiState.value.message != null }
        assertTrue(viewModel.uiState.value.message!!.contains("未修改图片状态"))
        assertTrue(preferences.getDisabledImages().isEmpty())
    }

    @Test
    fun anImageOutsideTheGroupAndASingleImageSourceCannotBeWrittenViaTheBatchEntry() {
        seedBatch()
        runBlocking {
            database.p1SourceDao().also { dao ->
                dao.registerSource(P1SourceEntity("single", "file:///single", "SINGLE_DOCUMENT"))
                dao.commitScan("single", listOf(P1ImageRecordEntity("single-image", "file:///single.png", "single.png", "single.png")), "APP_OWNED", "2026-10-09T00:00:00Z")
            }
        }
        val viewModel = loadedViewModel()
        viewModel.setBatchImageEnabled("batch", "file:///not-in-this-group.jpg", false)
        viewModel.setBatchImageEnabled("missing", uri(1), false)
        viewModel.setBatchImageEnabled("single", "file:///single.png", false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(preferences.getDisabledImages().isEmpty())
    }

    @Test
    fun anExternalEditUsesTheSameStopStorageAndUpdatesAllSharedGroups() {
        seedBatch()
        seedBatch(id = "shared", count = 1)
        val viewModel = loadedViewModel()
        viewModel.toggleBatchExpanded("batch")
        viewModel.toggleBatchExpanded("shared")
        preferences.setImageEnabled(uri(1), false)
        awaitCondition {
            viewModel.uiState.value.sources.all { item -> item.batchPage.stoppedCount == 1 }
        }
        assertTrue(viewModel.uiState.value.sources.all { !it.batchPage.rows.first().enabled })
    }

    @Test
    fun sourceStopAndResumeKeepEachImageOwnRecord() {
        seedBatch()
        preferences.setImageEnabled(uri(2), false)
        val viewModel = loadedViewModel()
        viewModel.toggleBatchExpanded("batch")
        viewModel.toggleSourceEnabled("batch")
        awaitCondition { !source(viewModel).isEnabled }
        assertTrue(source(viewModel).batchPage.rows.none { it.canToggle })
        assertEquals(setOf(uri(2)), preferences.getDisabledImages())
        viewModel.toggleSourceEnabled("batch")
        awaitCondition { source(viewModel).isEnabled }
        assertTrue(source(viewModel).batchPage.rows.all { it.canToggle })
        assertFalse(source(viewModel).batchPage.rows[1].enabled)
    }

    @Test
    fun refreshKeepsTheExpandedGroupAndPageWhileReadingNewExternalStopState() {
        seedBatch(count = 110)
        val viewModel = loadedViewModel()
        viewModel.toggleBatchExpanded("batch")
        viewModel.moveBatchPage("batch", 1)
        preferences.setImageEnabled(uri(51), false)
        viewModel.refresh()
        awaitCondition { source(viewModel).batchPage.stoppedCount == 1 }
        assertTrue(source(viewModel).batchPage.expanded)
        assertEquals(1, source(viewModel).batchPage.pageIndex)
        assertFalse(source(viewModel).batchPage.rows.first().enabled)
    }

    @Test
    fun directoryRecursiveSelectionIsStoredThroughTheRealImportPath() {
        val viewModel = newViewModel()
        for (include in listOf(false, true)) {
            val directory = Files.createTempDirectory(application.cacheDir.toPath(), "gallery-recursive-").toFile()
            viewModel.addDirectorySource(Uri.fromFile(directory), include)
            awaitCondition { !viewModel.uiState.value.isImporting && viewModel.uiState.value.message != null }
            val saved = runBlocking { database.p1SourceDao().getSources().single { it.rootUri == Uri.fromFile(directory).toString() } }
            assertEquals(include, saved.includeSubdirectories)
            viewModel.messageShown()
        }
    }

    @Test
    fun directoryRecursiveOptionDefaultsToTrueForExistingCallers() {
        val viewModel = newViewModel()
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "gallery-default-").toFile()
        viewModel.addDirectorySource(Uri.fromFile(directory))
        awaitCondition { !viewModel.uiState.value.isImporting && viewModel.uiState.value.message != null }
        assertTrue(runBlocking { database.p1SourceDao().getSources().single().includeSubdirectories })
    }

    private fun newViewModel(): GalleryViewModel {
        val store = ViewModelStore().also { stores += it }
        // Robolectric 每例创建新的 Application；静态 getInstance 只记住首例，会让 VM 读写另一份偏好。
        val viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[
            GalleryViewModel::class.java,
        ]
        assertSame("图库与测试必须使用同一 Application 和偏好存储", application, viewModel.getApplication<Application>())
        return viewModel
    }

    private fun loadedViewModel(): GalleryViewModel = newViewModel().also { viewModel ->
        viewModel.refresh()
        awaitCondition { !viewModel.uiState.value.isLoading && viewModel.uiState.value.sources.any { it.id == "batch" } }
    }

    private fun source(viewModel: GalleryViewModel): SourceItem = viewModel.uiState.value.sources.single { it.id == "batch" }

    private fun uri(index: Int) = "file:///batch/photo$index.jpg"

    private fun seedBatch(id: String = "batch", count: Int = 3, enabled: Boolean = true) = runBlocking {
        database.p1SourceDao().also { dao ->
            dao.registerSource(P1SourceEntity(id, "p1-image-batch://$id", "MULTIPLE_DOCUMENTS", enabled = enabled))
            dao.commitScan(id, (1..count).map { index ->
                P1ImageRecordEntity("image$index", uri(index), "photo$index.jpg", "photo$index.jpg")
            }, "APP_OWNED", "2026-10-09T00:00:00Z")
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val timeout = System.nanoTime() + 8_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < timeout)
        assertTrue("预期图库操作未完成", condition())
    }
}
