package dev.abdus.apps.shimmer.ui.gallery

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 图库页子目录展开与启用/停用，使用真实 Room 数据库。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(application)
    private val store = ViewModelStore()

    @After
    fun clearViewModelAndDatabase() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun aCardStartsCollapsedAndOneTapOpensTheNextLevel() {
        seedDirectorySource()
        val viewModel = loadedViewModel()

        val collapsed = rows(viewModel)
        assertEquals(listOf(""), collapsed.map { it.path })
        assertEquals(3, collapsed.single().totalCount)

        viewModel.toggleDirectoryExpanded("dir", "")
        assertEquals(listOf("", "1"), rows(viewModel).map { it.path })

        viewModel.toggleDirectoryExpanded("dir", "1")
        assertEquals(listOf("", "1", "1/1-1", "1/1-2"), rows(viewModel).map { it.path })

        viewModel.toggleDirectoryExpanded("dir", "")
        assertEquals(listOf(""), rows(viewModel).map { it.path })
    }

    @Test
    fun stoppingAFolderWritesTheSameRecordAsTheSourcesPage() {
        seedDirectorySource()
        val viewModel = loadedViewModel()
        viewModel.toggleDirectoryExpanded("dir", "")
        viewModel.toggleDirectoryExpanded("dir", "1")

        viewModel.setDirectoryEnabled("dir", "1", enabled = false)
        awaitCondition { viewModel.uiState.value.message != null }

        assertEquals(setOf("1"), exclusions())
        assertTrue(viewModel.uiState.value.message!!.contains("重新载入来源"))
        val byPath = rows(viewModel).associateBy { it.path }
        assertEquals("已停用", byPath.getValue("1").stateLabel)
        assertEquals(DirectoryControl.PARENT, byPath.getValue("1/1-1").control)
        viewModel.messageShown()

        viewModel.setDirectoryEnabled("dir", "1", enabled = true)
        awaitCondition { viewModel.uiState.value.message != null }

        assertEquals(emptySet<String>(), exclusions())
        assertEquals(DirectoryControl.TOGGLE, rows(viewModel).single { it.path == "1/1-1" }.control)
    }

    @Test
    fun stopRecordsMadeElsewhereAreReadOnTheNextRefresh() {
        seedDirectorySource()
        runBlocking { database.p1SourceDao().setDirectoryExcluded("dir", "1/1-2", true, "2026-10-04T00:00:00Z") }

        val viewModel = loadedViewModel()
        viewModel.toggleDirectoryExpanded("dir", "")
        viewModel.toggleDirectoryExpanded("dir", "1")

        assertEquals("已停用", rows(viewModel).single { it.path == "1/1-2" }.stateLabel)
    }

    private fun loadedViewModel(): GalleryViewModel {
        // 每例使用当前 Application，避免静态工厂保留上一个 Robolectric 测试的偏好文件。
        val viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[
            GalleryViewModel::class.java,
        ]
        assertSame("图库与测试必须使用同一 Application 和偏好存储", application, viewModel.getApplication<Application>())
        viewModel.refresh()
        awaitCondition { viewModel.uiState.value.sources.any { it.directoryRows.rows.isNotEmpty() } }
        return viewModel
    }

    private fun rows(viewModel: GalleryViewModel): List<DirectoryRow> =
        viewModel.uiState.value.sources.single { it.id == "dir" }.directoryRows.rows

    private fun exclusions(): Set<String> =
        runBlocking { database.p1SourceDao().getExclusions("dir").map { it.directoryPath }.toSet() }

    private fun seedDirectorySource() = runBlocking {
        database.p1SourceDao().also { dao ->
            dao.registerSource(P1SourceEntity("dir", "file:///dir", "DIRECTORY_TREE", enabled = true))
            dao.commitScan("dir", listOf("1/1-1/a.png", "1/1-2/b.png", "top.png").map { path ->
                P1ImageRecordEntity(
                    id = path,
                    uri = "file:///dir/$path",
                    displayName = path.substringAfterLast('/'),
                    relativePath = path,
                )
            }, "APP_OWNED", "2026-09-28T00:00:00Z")
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val timeout = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < timeout)
        assertTrue("The expected gallery operation did not finish", condition())
    }
}
