package dev.abdus.apps.shimmer.ui.playback

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSelectionBridge
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 真实页面 Intent 送入内部桥；Target 是夹具，不冒充正式图片解码／呈现。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackSelectionViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(app)
    private val owner = ViewModelStore()
    private val snapshots = PlaybackSnapshotStore(app)
    private val ids = (1..3).map { "file:///pick/$it.png" }
    private val targetId = "ui-pick-test"
    private val submitted = mutableListOf<String>()

    @After fun close() {
        PlaybackSelectionBridge.unregister(targetId)
        owner.clear()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    private fun loaded(order: PlaybackOrder = PlaybackOrder.NATURAL_ASC): PlaybackViewModel {
        runBlocking {
            database.p1SourceDao().registerSource(P1SourceEntity("pick", "file:///pick", "DIRECTORY_TREE", enabled = true))
            database.p1SourceDao().commitScan("pick", ids.mapIndexed { i, uri ->
                P1ImageRecordEntity("pick-$i", uri, "${i + 1}.png", "${i + 1}.png")
            }, "APP_OWNED", "2026-10-10T00:00:00Z")
        }
        snapshots.save(PlaybackSnapshot(order = order, queueIds = if (order == PlaybackOrder.MANUAL) ids.reversed() else emptyList(),
            currentId = ids[0], historyIds = listOf(ids[0]), historyCursor = 0, sourcePositions = mapOf("pick" to ids[0])))
        val vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PlaybackViewModel::class.java]
        vm.screenEntered()
        await { !vm.uiState.value.isLoading && !vm.uiState.value.isReloading }
        vm.uiState.value.scrollRequest?.let { vm.scrollHandled(it.serial) }
        return vm
    }

    private fun target(result: PlaybackSelectionBridge.Result) {
        PlaybackSelectionBridge.register(targetId, object : PlaybackSelectionBridge.Target {
            override fun select(itemId: String): PlaybackSelectionBridge.Result {
                submitted += itemId
                return result
            }
        })
        PlaybackSelectionBridge.markVisible(targetId)
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("播放点击宿主测试未在等待内完成", condition())
    }

    private fun view(vm: PlaybackViewModel): QueueViewState = vm.javaClass.getDeclaredField("queueView")
        .apply { isAccessible = true }.get(vm) as QueueViewState

    @Test fun primaryTapSubmitsInEveryModeWithoutCurrentOrSortMutation() {
        val vm = loaded()
        target(PlaybackSelectionBridge.Result.QUEUED)
        for (order in PlayOrder.values()) {
            vm.setPlayOrder(order)
            await { vm.uiState.value.selectedOrder == order && !vm.uiState.value.isSavingOrder && !vm.uiState.value.isReloading }
            val before = snapshots.load()
            val shown = vm.uiState.value
            vm.clickToPlay(ids[2])
            assertEquals(ids[2], submitted.last())
            assertEquals(before, snapshots.load())
            assertEquals(shown.currentImage, vm.uiState.value.currentImage)
            assertSame(shown.queue, vm.uiState.value.queue)
            assertNull(vm.uiState.value.scrollRequest)
            assertTrue(vm.uiState.value.message!!.contains("返回桌面"))
        }
        assertEquals(4, submitted.size)
    }

    @Test fun manualSecondarySelectionNeverSubmitsPlayback() {
        val vm = loaded(PlaybackOrder.MANUAL)
        target(PlaybackSelectionBridge.Result.ACCEPTED)
        val before = snapshots.load()
        vm.selectItem(ids[1])
        assertEquals(ids[1], view(vm).selectedItemId)
        assertTrue(submitted.isEmpty())
        assertEquals(before, snapshots.load())
        vm.selectItem(ids[1])
        assertNull(view(vm).selectedItemId)
        assertTrue(submitted.isEmpty())
    }

    @Test fun primaryTapPreservesTheIndependentManualSelection() {
        val vm = loaded(PlaybackOrder.MANUAL)
        target(PlaybackSelectionBridge.Result.QUEUED)
        vm.selectItem(ids[1])
        val before = snapshots.load()
        vm.clickToPlay(ids[2])
        assertEquals(ids[1], view(vm).selectedItemId)
        assertEquals(before, snapshots.load())
        assertEquals(listOf(ids[2]), submitted)
        assertEquals(ids[0], vm.uiState.value.queue.currentId)
    }

    @Test fun movingAManualItemDoesNotSubmitPlayback() {
        val vm = loaded(PlaybackOrder.MANUAL)
        target(PlaybackSelectionBridge.Result.ACCEPTED)
        vm.moveItem(ids[1], -1)
        await { !vm.uiState.value.isSavingOrder && !vm.uiState.value.isReloading }
        assertTrue(submitted.isEmpty())
        assertEquals(ids[0], snapshots.load()!!.currentId)
        assertEquals(listOf(ids[1], ids[2], ids[0]), snapshots.load()!!.queueIds)
    }

    @Test fun naturalModeCannotAcquireASortSelection() {
        val vm = loaded()
        target(PlaybackSelectionBridge.Result.ACCEPTED)
        vm.selectItem(ids[1])
        assertNull(view(vm).selectedItemId)
        assertTrue(submitted.isEmpty())
    }

    @Test fun targetNotInCurrentModelDoesNotReachTheBridge() {
        val vm = loaded()
        target(PlaybackSelectionBridge.Result.ACCEPTED)
        val before = snapshots.load()
        vm.clickToPlay("file:///not-listed.png")
        vm.clickToPlay("")
        assertTrue(submitted.isEmpty())
        assertEquals(before, snapshots.load())
        assertTrue(vm.uiState.value.message!!.contains("不在当前列表"))
    }

    @Test fun acceptedResultDoesNotOptimisticallyMarkTheRequestedImage() {
        val vm = loaded()
        target(PlaybackSelectionBridge.Result.ACCEPTED)
        val before = snapshots.load()
        vm.clickToPlay(ids[2])
        assertEquals(before, snapshots.load())
        assertEquals(ids[0], vm.uiState.value.queue.currentId)
        assertEquals(ids[0], vm.uiState.value.currentImage?.uri)
        assertTrue(vm.uiState.value.message!!.contains("成功显示后"))
    }

    @Test fun onlyRealFormalSnapshotSubmissionChangesTheCurrentMarker() {
        val vm = loaded()
        target(PlaybackSelectionBridge.Result.QUEUED)
        vm.clickToPlay(ids[2])
        val previous = snapshots.load()!!
        snapshots.save(previous.copy(currentId = ids[2], historyIds = listOf(ids[0], ids[2]), historyCursor = 1))
        await { vm.uiState.value.queue.currentId == ids[2] && vm.uiState.value.currentImage?.uri == ids[2] }
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun noEngineGivesASetWallpaperHintWithoutChangingTheSnapshot() {
        val vm = loaded()
        val before = snapshots.load()
        vm.clickToPlay(ids[1])
        assertEquals("请先在系统预览中应用桌面动态壁纸，再选择要播放的图片。", vm.uiState.value.message)
        assertEquals(before, snapshots.load())
    }

    @Test fun editingRefusalKeepsTheCurrentHistoryAndManualOrder() {
        val vm = loaded(PlaybackOrder.MANUAL)
        target(PlaybackSelectionBridge.Result.BUSY_EDIT)
        val before = snapshots.load()
        vm.clickToPlay(ids[2])
        assertTrue(vm.uiState.value.message!!.contains("实时编辑"))
        assertEquals(before, snapshots.load())
    }

    @Test fun noopGivesAnAccurateHintWithoutWritingPlaybackState() {
        val vm = loaded()
        target(PlaybackSelectionBridge.Result.NOOP)
        val before = snapshots.load()
        vm.clickToPlay(ids[0])
        assertTrue(vm.uiState.value.message!!.contains("当前已在播放"))
        assertEquals(before, snapshots.load())
    }

    @Test fun busySortSaveDoesNotSendACompetingPlaybackRequest() {
        val vm = loaded()
        target(PlaybackSelectionBridge.Result.ACCEPTED)
        vm.setPlayOrder(PlayOrder.NATURAL_DESC)
        assertTrue(vm.uiState.value.isSavingOrder)
        vm.clickToPlay(ids[2])
        assertTrue(submitted.isEmpty())
        await { !vm.uiState.value.isSavingOrder && !vm.uiState.value.isReloading }
    }
}
