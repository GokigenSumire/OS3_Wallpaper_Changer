package dev.abdus.apps.shimmer.ui.playback

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackGroupKey
import dev.abdus.apps.shimmer.p2.PlaybackOrder
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

/** 真 ViewModel／Room／Store 验证定位请求；列表实际像素偏移及桌面播放另由目标机测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackModeFocusViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(app)
    private val owner = ViewModelStore()
    private val snapshots = PlaybackSnapshotStore(app)
    private val ids = (1..130).map { "file:///dir/deep/$it.png" }
    private val secondIds = (1..2).map { "file:///other/$it.png" }
    private val initial = PlaybackSnapshot(
        currentId = ids[1], historyIds = ids.take(2), historyCursor = 1,
        sourcePositions = mapOf("dir" to ids[1]),
    )

    @After fun close() {
        owner.clear()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    private fun create() = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PlaybackViewModel::class.java]

    private fun seed() = runBlocking {
        val dao = database.p1SourceDao()
        for ((source, uris) in listOf("dir" to ids, "other" to secondIds)) {
            dao.registerSource(P1SourceEntity(source, "file:///$source", "DIRECTORY_TREE", enabled = true))
            dao.commitScan(source, uris.mapIndexed { i, uri ->
                P1ImageRecordEntity(id = "$source-$i", uri = uri, displayName = "${i + 1}.png",
                    relativePath = if (source == "dir") "deep/${i + 1}.png" else "${i + 1}.png")
            }, "APP_OWNED", "2026-10-10T00:00:00Z")
        }
        snapshots.save(initial)
    }

    private fun loaded(): PlaybackViewModel {
        seed()
        return create().also { vm ->
            vm.screenEntered()
            await { !vm.uiState.value.isLoading && !vm.uiState.value.isReloading }
            vm.uiState.value.scrollRequest?.let { vm.scrollHandled(it.serial) }
            assertNull(vm.uiState.value.scrollRequest)
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("播放模式定位测试未在等待内完成", condition())
    }

    private fun change(vm: PlaybackViewModel, order: PlayOrder) {
        vm.setPlayOrder(order)
        await { vm.uiState.value.selectedOrder == order && !vm.uiState.value.isSavingOrder && !vm.uiState.value.isReloading }
    }

    private fun view(vm: PlaybackViewModel): QueueViewState = vm.javaClass.getDeclaredField("queueView")
        .apply { isAccessible = true }.get(vm) as QueueViewState

    private fun pending(vm: PlaybackViewModel, target: FocusTarget) {
        // 重建读期间尚未消费的首进／调序意图；不制造保存动作或伪装 Compose 已滚动。
        vm.javaClass.getDeclaredField("pendingFocus").apply { isAccessible = true; set(vm, target) }
    }

    @Test fun selectingEveryModeDoesNotGenerateAScrollRequestOrAdvanceCurrent() {
        val vm = loaded()
        for (order in listOf(PlayOrder.NATURAL_DESC, PlayOrder.RANDOM, PlayOrder.MANUAL, PlayOrder.NATURAL_ASC)) {
            change(vm, order)
            assertNull("$order 不应发出定位请求", vm.uiState.value.scrollRequest)
            assertEquals(order, vm.uiState.value.selectedOrder)
            assertEquals(initial.currentId, vm.uiState.value.currentImage?.uri)
            assertEquals(initial.historyIds, snapshots.load()!!.historyIds)
            assertEquals(initial.sourcePositions, snapshots.load()!!.sourcePositions)
        }
    }

    @Test fun reselectingTheSameModeDoesNotWriteOrGenerateFocus() {
        val vm = loaded()
        for (order in PlayOrder.values()) {
            change(vm, order)
            val saved = snapshots.load()
            vm.setPlayOrder(order)
            assertEquals(saved, snapshots.load())
            assertNull(vm.uiState.value.scrollRequest)
            assertFalse(vm.uiState.value.isSavingOrder)
        }
    }

    @Test fun externalModeNotificationsReloadWithoutFocus() {
        val vm = loaded()
        for (order in listOf(PlayOrder.NATURAL_DESC, PlayOrder.RANDOM, PlayOrder.MANUAL, PlayOrder.NATURAL_ASC)) {
            snapshots.update { latest -> PlaybackOrderUpdate.select(latest, order) }
            await { vm.uiState.value.selectedOrder == order && !vm.uiState.value.isReloading }
            assertNull(vm.uiState.value.scrollRequest)
            assertEquals(initial.currentId, vm.uiState.value.currentImage?.uri)
        }
    }

    @Test fun currentCommitAfterModeChangeUpdatesCardWithoutFocus() {
        val vm = loaded()
        change(vm, PlayOrder.NATURAL_DESC)
        snapshots.update { latest -> latest!!.copy(currentId = ids[0], historyCursor = 0) }
        await { vm.uiState.value.currentImage?.uri == ids[0] }
        assertEquals(ids[0], vm.uiState.value.queue.currentId)
        assertEquals(PlayOrder.NATURAL_DESC, vm.uiState.value.selectedOrder)
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun reEntryAfterModeChangeDoesNotCreateANewFocus() {
        val vm = loaded()
        change(vm, PlayOrder.RANDOM)
        repeat(3) {
            vm.screenEntered()
            await { !vm.uiState.value.isReloading }
            assertNull(vm.uiState.value.scrollRequest)
        }
    }

    @Test fun firstEntryFocusRemainsReachableAfterModeSelection() {
        seed()
        val vm = create()
        vm.screenEntered()
        vm.setPlayOrder(PlayOrder.NATURAL_DESC)
        await { vm.uiState.value.selectedOrder == PlayOrder.NATURAL_DESC && !vm.uiState.value.isReloading }
        assertEquals(imageRowKey(ids[1]), vm.uiState.value.scrollRequest?.rowKey)
    }

    @Test fun pendingMovedItemIsNotReplacedByCurrentFocusOnModeChange() {
        val vm = loaded()
        pending(vm, FocusTarget.Item(ids[119]))
        change(vm, PlayOrder.NATURAL_DESC)
        val request = vm.uiState.value.scrollRequest!!
        assertEquals(imageRowKey(ids[119]), request.rowKey)
        assertTrue(vm.uiState.value.queueRows.any { it.key == request.rowKey })
    }

    @Test fun pendingMovedGroupIsNotReplacedByCurrentFocusOnModeChange() {
        val vm = loaded()
        val group = PlaybackGroupKey("DIRECTORY_TREE", "other")
        pending(vm, FocusTarget.Group(group))
        change(vm, PlayOrder.NATURAL_DESC)
        val block = vm.uiState.value.queue.blocks.first { it.group == group }
        assertEquals(groupRowKey(block), vm.uiState.value.scrollRequest?.rowKey)
    }

    @Test fun explicitLocateAndSerialConsumptionStillWorkAfterModeChange() {
        val vm = loaded()
        change(vm, PlayOrder.NATURAL_DESC)
        vm.locateCurrent()
        val first = vm.uiState.value.scrollRequest!!
        assertEquals(imageRowKey(ids[1]), first.rowKey)
        vm.locateCurrent()
        val second = vm.uiState.value.scrollRequest!!
        assertTrue(second.serial > first.serial)
        vm.scrollHandled(first.serial)
        assertEquals(second, vm.uiState.value.scrollRequest)
        vm.scrollHandled(second.serial)
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun manualItemMoveStillFocusesItsActualResult() {
        val vm = loaded()
        change(vm, PlayOrder.MANUAL)
        vm.moveItem(ids[1], 1)
        await { !vm.uiState.value.isSavingOrder && !vm.uiState.value.isReloading }
        assertEquals(imageRowKey(ids[1]), vm.uiState.value.scrollRequest?.rowKey)
        assertEquals(ids[1], view(vm).movedItemId)
    }

    @Test fun manualGroupMoveStillFocusesItsActualResult() {
        val vm = loaded()
        change(vm, PlayOrder.MANUAL)
        val group = PlaybackGroupKey("DIRECTORY_TREE", "other")
        vm.moveGroup(group, -1)
        await { !vm.uiState.value.isSavingOrder && !vm.uiState.value.isReloading }
        assertEquals(group, view(vm).movedGroup)
        val block = vm.uiState.value.queue.blocks.first { it.group == group }
        assertEquals(groupRowKey(block), vm.uiState.value.scrollRequest?.rowKey)
    }

    @Test fun changingModeKeepsExpansionAndPaginationWithoutNewFocus() {
        val vm = loaded()
        val more = vm.uiState.value.queueRows.filterIsInstance<QueueRow.More>().first()
        vm.showMoreRows(more.pageKey)
        val browsing = view(vm)
        change(vm, PlayOrder.NATURAL_DESC)
        val after = view(vm)
        assertEquals(browsing.expanded, after.expanded)
        assertEquals(browsing.visibleRows, after.visibleRows)
        assertNull(vm.uiState.value.scrollRequest)
    }
}
