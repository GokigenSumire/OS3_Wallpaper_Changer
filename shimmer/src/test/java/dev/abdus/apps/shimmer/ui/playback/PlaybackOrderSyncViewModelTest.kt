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
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p2.forPersistence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 真 ViewModel／Room／Store 通知；不包含手机壁纸、图片解码或 GPU 呈现。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackOrderSyncViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(app)
    private val owner = ViewModelStore()
    private val snapshots = PlaybackSnapshotStore(app)
    private val ids = (1..3).map { "file:///dir/$it.png" }
    private val initial = PlaybackSnapshot(historyIds = ids, historyCursor = 1, currentId = ids[1],
        sourcePositions = mapOf("dir" to ids[1]))

    @After fun close() {
        owner.clear()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }
    private fun create() = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PlaybackViewModel::class.java]
    private fun seed() = runBlocking {
        val dao = database.p1SourceDao()
        dao.registerSource(P1SourceEntity("dir", "file:///dir", "DIRECTORY_TREE", enabled = true))
        dao.commitScan("dir", ids.mapIndexed { i, uri ->
            P1ImageRecordEntity(id = "i$i", uri = uri, displayName = "${i + 1}.png", relativePath = "${i + 1}.png")
        }, "APP_OWNED", "2026-10-10T00:00:00Z")
        snapshots.save(initial)
    }
    private fun loaded(): PlaybackViewModel { seed(); return create().also { it.screenEntered(); await { !it.uiState.value.isLoading } } }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("播放状态同步未在测试等待内完成", condition())
    }

    @Test fun initialQueueShowsTheActualCommittedCurrent() {
        val vm = loaded()
        assertEquals(ids[1], vm.uiState.value.currentImage!!.uri)
        assertEquals(ids[1], vm.uiState.value.queue.currentId)
        assertEquals(3, vm.uiState.value.enabledCount)
    }
    @Test fun selectingModePersistsWithoutHistoryClearAndWithoutAutoAdvance() {
        val vm = loaded()
        vm.setPlayOrder(PlayOrder.NATURAL_DESC)
        // 请求发出不等于已保存；旧选择保持，明确显示保存中。
        assertTrue(vm.uiState.value.isSavingOrder)
        assertEquals(PlayOrder.NATURAL_ASC, vm.uiState.value.selectedOrder)
        await { snapshots.load()?.order == PlaybackOrder.NATURAL_DESC && !vm.uiState.value.isReloading }
        val saved = snapshots.load()!!
        assertEquals(initial.historyIds, saved.historyIds)
        assertEquals(initial.currentId, saved.currentId)
        assertEquals(initial.sourcePositions, saved.sourcePositions)
        assertTrue(saved.forwardHistoryInvalidated)
        await { vm.uiState.value.queue.order == PlaybackOrder.NATURAL_DESC }
        assertEquals(ids.reversed(), vm.uiState.value.queue.ordered.map { it.id })
        assertEquals(ids[1], vm.uiState.value.currentImage!!.uri)
    }
    @Test fun selectionCanPersistEvenWhenTheDatabaseIsUnavailable() {
        seed()
        val vm = create() // 尚未进页、未读取目录。
        withBrokenImageTable {
            vm.setPlayOrder(PlayOrder.NATURAL_DESC)
            await { snapshots.load()?.order == PlaybackOrder.NATURAL_DESC && !vm.uiState.value.isSavingOrder }
            assertEquals(PlayOrder.NATURAL_DESC, vm.uiState.value.selectedOrder)
            assertEquals(initial.historyIds, snapshots.load()!!.historyIds)
            // 后台展示读取可以失败，但不能撤销已成功保存的配置。
            await { vm.uiState.value.message?.contains("读取播放状态失败") == true }
            assertEquals(PlaybackOrder.NATURAL_DESC, snapshots.load()!!.order)
        }
    }
    @Test fun anotherStoreCurrentCommitUpdatesTheCardWithoutCatalogReload() {
        val vm = loaded()
        val ordered = vm.uiState.value.queue.ordered
        PlaybackSnapshotStore(app).save(initial.copy(currentId = ids[2], historyCursor = 2))
        await { vm.uiState.value.currentImage?.uri == ids[2] }
        assertSame(ordered, vm.uiState.value.queue.ordered)
        assertEquals(3, vm.uiState.value.enabledCount) // 真实目录未改变，当前通知不重建队列。
        assertEquals(ids[2], vm.uiState.value.queue.currentId)
    }
    @Test fun currentOnlyNotificationsKeepExpansionAndPagination() {
        val vm = loaded()
        val level = vm.uiState.value.queueRows.filterIsInstance<QueueRow.Level>().single()
        if (!level.expanded) vm.toggleLevel(level.levelKey)
        val before = vm.uiState.value.queueRows.map { it.key }
        snapshots.save(initial.copy(currentId = ids[0], historyCursor = 0))
        await { vm.uiState.value.currentImage?.uri == ids[0] }
        assertEquals(before, vm.uiState.value.queueRows.map { it.key })
        assertTrue(vm.uiState.value.queueRows.filterIsInstance<QueueRow.Level>().single().expanded)
        assertTrue(vm.uiState.value.queueRows.filterIsInstance<QueueRow.Image>().single { it.item.id == ids[0] }.isCurrent)
    }
    @Test fun externalModeSaveReloadsTheQueueWithoutCurrentAdvance() {
        val vm = loaded()
        PlaybackSnapshotStore(app).update { latest -> PlaybackOrderUpdate.select(latest, PlayOrder.NATURAL_DESC) }
        await { vm.uiState.value.queue.order == PlaybackOrder.NATURAL_DESC && !vm.uiState.value.isReloading }
        assertEquals(PlayOrder.NATURAL_DESC, vm.uiState.value.selectedOrder)
        assertEquals(ids[1], vm.uiState.value.currentImage!!.uri)
        assertEquals(initial.historyIds, snapshots.load()!!.historyIds)
    }
    @Test fun sameSelectionDoesNotWriteOrClearTheHistory() {
        val vm = loaded()
        val before = snapshots.load()
        vm.setPlayOrder(PlayOrder.NATURAL_ASC)
        assertFalse(vm.uiState.value.isSavingOrder)
        assertEquals(before, snapshots.load())
    }
    @Test fun rapidSecondSelectionWhileSavingCannotOverrideTheFirstRequest() {
        val vm = loaded()
        vm.setPlayOrder(PlayOrder.NATURAL_DESC)
        vm.setPlayOrder(PlayOrder.RANDOM)
        await { snapshots.load()?.order == PlaybackOrder.NATURAL_DESC && !vm.uiState.value.isSavingOrder }
        assertEquals(PlaybackOrder.NATURAL_DESC, snapshots.load()!!.order)
    }
    @Test fun randomSelectionDisplaysACompleteQueueWithoutWritingHistory() {
        val vm = loaded()
        vm.setPlayOrder(PlayOrder.RANDOM)
        await { vm.uiState.value.queue.order == PlaybackOrder.RANDOM && !vm.uiState.value.isReloading }
        assertEquals(ids.toSet(), vm.uiState.value.queue.ordered.map { it.id }.toSet())
        assertEquals(initial.historyIds, snapshots.load()!!.historyIds)
        assertEquals(initial.currentId, snapshots.load()!!.currentId)
        assertTrue(snapshots.load()!!.forwardHistoryInvalidated)
    }
    @Test fun lateLoadedOldModeCannotPublishOverAnAlreadyCommittedNewMode() {
        val vm = loaded()
        val oldUi = vm.uiState.value
        val oldLoaded = loadedValue(vm, initial.forPersistence(), oldUi.queue)
        snapshots.update { PlaybackOrderUpdate.select(it, PlayOrder.NATURAL_DESC) }
        vm.javaClass.getDeclaredMethod("applyLoaded", oldLoaded.javaClass, CurrentImageInfo::class.java)
            .apply { isAccessible = true }.invoke(vm, oldLoaded, oldUi.currentImage)
        await { vm.uiState.value.queue.order == PlaybackOrder.NATURAL_DESC && !vm.uiState.value.isReloading }
        assertEquals(PlayOrder.NATURAL_DESC, vm.uiState.value.selectedOrder)
    }
    @Test fun lateLoadedOldCurrentCannotRevertTheNewCurrentCard() {
        val vm = loaded()
        val oldUi = vm.uiState.value
        val oldLoaded = loadedValue(vm, initial.forPersistence(), oldUi.queue)
        snapshots.save(initial.copy(currentId = ids[2], historyCursor = 2))
        vm.javaClass.getDeclaredMethod("applyLoaded", oldLoaded.javaClass, CurrentImageInfo::class.java)
            .apply { isAccessible = true }.invoke(vm, oldLoaded, oldUi.currentImage)
        await { vm.uiState.value.currentImage?.uri == ids[2] }
        assertEquals(ids[2], vm.uiState.value.queue.currentId)
    }
    @Test fun clearingTheViewModelUnregistersItsStrongSnapshotListener() {
        val vm = loaded()
        owner.clear()
        val before = vm.uiState.value
        snapshots.save(initial.copy(currentId = ids[2], historyCursor = 2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, vm.uiState.value)
        val listeners = vm.javaClass.getDeclaredField("snapshotListener").apply { isAccessible = true }.get(vm)
        assertNotNull(listeners) // 实际强引用存在，不依赖弱监听存活。
    }
    @Test fun enteringManualUsesTheOrderShownInThePageBeforeSelection() {
        val vm = loaded()
        vm.setPlayOrder(PlayOrder.NATURAL_DESC)
        await { vm.uiState.value.queue.order == PlaybackOrder.NATURAL_DESC && !vm.uiState.value.isReloading }
        vm.setPlayOrder(PlayOrder.MANUAL)
        await { vm.uiState.value.queue.order == PlaybackOrder.MANUAL && !vm.uiState.value.isReloading }
        assertEquals(ids.reversed(), snapshots.load()!!.queueIds)
        assertEquals(ids.reversed(), vm.uiState.value.queue.ordered.map { it.id })
    }
    @Test fun lightweightCurrentReadFailureRetainsTheShownCardAndSavedConfiguration() {
        val vm = loaded()
        val old = vm.uiState.value
        val committed = initial.copy(currentId = ids[2], historyCursor = 2)
        withBrokenImageTable {
            snapshots.save(committed)
            await { vm.uiState.value.message?.contains("读取当前图片信息失败") == true }
            assertEquals(old.currentImage, vm.uiState.value.currentImage)
            assertEquals(old.queue, vm.uiState.value.queue)
            assertEquals(committed.forPersistence(), snapshots.load())
        }
    }
    private fun withBrokenImageTable(test: () -> Unit) {
        // 只改 Robolectric 生成的隔离测试数据库，令实际生产 SQL 确定失败；finally 恢复，无文件删除。
        database.openHelper.writableDatabase.execSQL("ALTER TABLE image_records RENAME TO order_sync_missing_image_records")
        try { test() } finally {
            database.openHelper.writableDatabase.execSQL("ALTER TABLE order_sync_missing_image_records RENAME TO image_records")
        }
    }
    private fun loadedValue(vm: PlaybackViewModel, snapshot: PlaybackSnapshot, model: QueueModel): Any {
        val type = vm.javaClass.declaredClasses.single { it.simpleName == "Loaded" }
        return type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            model, snapshot.currentId, snapshot, 3, null, false, emptyMap<String, String>(),
            vm.javaClass.getDeclaredField("catalogRevision").apply { isAccessible = true }.getLong(vm),
        )
    }
}
