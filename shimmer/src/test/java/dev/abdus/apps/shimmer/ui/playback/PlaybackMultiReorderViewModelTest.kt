package dev.abdus.apps.shimmer.ui.playback

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 真 Room／ViewModel／偏好锁；不含 GPU、手机手势或系统壁纸。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackMultiReorderViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(app)
    private val snapshots = PlaybackSnapshotStore(app)
    private val owner = ViewModelStore()
    private val ids = (1..6).map { "file:///multi/$it.png" }
    private val submitted = mutableListOf<String>()
    private val engine = "multi-reorder-test"

    @After fun close() {
        owner.clear()
        PlaybackSelectionBridge.unregister(engine)
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("多选调序宿主检查未在等待内完成", condition())
    }
    private fun loaded(order: PlaybackOrder = PlaybackOrder.MANUAL): PlaybackViewModel {
        runBlocking {
            val dao = database.p1SourceDao()
            dao.registerSource(P1SourceEntity("multi", "file:///multi", "DIRECTORY_TREE", enabled = true))
            dao.commitScan("multi", ids.mapIndexed { i, uri ->
                P1ImageRecordEntity("multi-$i", uri, "${i + 1}.png", "${i + 1}.png")
            }, "APP_OWNED", "2026-10-12T00:00:00Z")
        }
        snapshots.save(PlaybackSnapshot(order = order, queueIds = if (order == PlaybackOrder.MANUAL) ids else emptyList(),
            currentId = ids[2], historyIds = listOf(ids[0], ids[2]), historyCursor = 1,
            sourcePositions = mapOf("multi" to ids[2]), sourceHistoryCursors = mapOf("multi" to 1)))
        return ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PlaybackViewModel::class.java].also {
            it.screenEntered()
            await { !it.uiState.value.isLoading && !it.uiState.value.isReloading }
            it.uiState.value.scrollRequest?.let { scroll -> it.scrollHandled(scroll.serial) }
        }
    }
    private fun view(vm: PlaybackViewModel) = vm.javaClass.getDeclaredField("queueView").apply { isAccessible = true }.get(vm) as QueueViewState
    private fun select(vm: PlaybackViewModel, vararg indices: Int) {
        vm.setMultiReorder(true)
        indices.forEach { vm.toggleMultiItem(ids[it]) }
    }
    private fun ready(vm: PlaybackViewModel) = await { !vm.uiState.value.isSavingOrder && !vm.uiState.value.isReloading }
    private fun bridge() {
        PlaybackSelectionBridge.register(engine, object : PlaybackSelectionBridge.Target {
            override fun select(itemId: String): PlaybackSelectionBridge.Result { submitted += itemId; return PlaybackSelectionBridge.Result.QUEUED }
        })
        PlaybackSelectionBridge.markVisible(engine)
    }

    @Test fun enteringSelectingAndFinishingNeverWritesOrPlays() {
        val vm = loaded(); bridge()
        vm.selectItem(ids[0])
        val before = snapshots.load()
        select(vm, 3, 1)
        assertEquals(setOf(ids[1], ids[3]), vm.uiState.value.multiReorder.selectedIds)
        assertEquals(before, snapshots.load()); assertTrue(submitted.isEmpty())
        vm.setMultiReorder(false)
        assertEquals(ids[0], view(vm).selectedItemId)
        assertTrue(view(vm).multiSelectedIds.isEmpty())
        assertEquals(before, snapshots.load()); assertTrue(submitted.isEmpty())
    }
    @Test fun nonContiguousMovementUsesQueueOrderAndKeepsHistoryCurrentAndRootPoints() {
        val vm = loaded(); val before = snapshots.load()!!
        select(vm, 3, 1); vm.moveMultiItems(-1); ready(vm)
        val saved = snapshots.load()!!
        assertEquals(listOf(ids[1], ids[3], ids[0], ids[2], ids[4], ids[5]), saved.queueIds)
        assertEquals(before.historyIds, saved.historyIds); assertEquals(before.currentId, saved.currentId)
        assertEquals(before.sourcePositions, saved.sourcePositions); assertEquals(before.sourceHistoryCursors, saved.sourceHistoryCursors)
        assertEquals(before.historyTransforms, saved.historyTransforms)
        assertTrue(saved.forwardHistoryInvalidated)
        assertEquals(setOf(ids[1], ids[3]), view(vm).movedItemIds)
    }
    @Test fun movementMergesTheLatestSavedOrderAndFormalCurrentRatherThanStaleUi() {
        val vm = loaded(); select(vm, 1, 3)
        val lock = vm.javaClass.getDeclaredField("coordinatorLock").apply { isAccessible = true }.get(vm) as Mutex
        runBlocking { lock.lock() }
        vm.moveMultiItemsTo(1)
        val newest = snapshots.load()!!.copy(queueIds = ids.reversed(), currentId = ids[4],
            historyIds = listOf(ids[0], ids[2], ids[4]), historyCursor = 2, sourcePositions = mapOf("multi" to ids[4]))
        snapshots.save(newest)
        lock.unlock(); ready(vm)
        val saved = snapshots.load()!!
        assertEquals(listOf(ids[3], ids[1], ids[5], ids[4], ids[2], ids[0]), saved.queueIds)
        assertEquals(newest.currentId, saved.currentId); assertEquals(newest.historyIds, saved.historyIds)
        assertEquals(newest.sourcePositions, saved.sourcePositions)
    }
    @Test fun staleModeAtTheSaveLockRejectsWithoutOverwritingNewConfiguration() {
        val vm = loaded(); select(vm, 1, 3)
        val latest = snapshots.update { PlaybackOrderUpdate.select(it, PlayOrder.NATURAL_DESC) }
        vm.moveMultiItemsTo(1); ready(vm)
        assertEquals(latest, snapshots.load()); assertFalse(vm.uiState.value.multiReorder.enabled)
    }
    @Test fun missingSelectedItemRejectsAllAndNeverMovesOnlyItsSurvivor() {
        val vm = loaded(); select(vm, 1, 3)
        val before = snapshots.load()
        runBlocking { database.p1SourceDao().setSourceEnabled("multi", false, null) }
        vm.moveMultiItemsTo(1); ready(vm)
        assertEquals(before, snapshots.load())
        assertTrue(vm.uiState.value.message!!.contains("整次移动已取消"))
        assertEquals(2, vm.uiState.value.multiReorder.unavailableCount)
        assertTrue(view(vm).movedItemIds.isEmpty())
    }
    @Test fun inactiveSelectionIsHiddenUntilExplicitlyCleared() {
        val vm = loaded(); select(vm, 1, 3)
        runBlocking { database.p1SourceDao().setSourceEnabled("multi", false, null) }
        vm.refresh(); ready(vm)
        assertTrue(vm.uiState.value.multiReorder.selectedIds.isEmpty())
        assertEquals(2, vm.uiState.value.multiReorder.unavailableCount)
        vm.clearMultiSelection()
        assertEquals(0, vm.uiState.value.multiReorder.unavailableCount)
    }
    @Test fun browseExpansionPaginationAndScreenReentryKeepSameSessionSelection() {
        val vm = loaded(); select(vm, 1, 3)
        val level = vm.uiState.value.queueRows.filterIsInstance<QueueRow.Level>().first()
        vm.toggleLevel(level.levelKey); vm.showMoreRows("arbitrary-page")
        vm.screenEntered(); ready(vm)
        assertEquals(setOf(ids[1], ids[3]), vm.uiState.value.multiReorder.selectedIds)
    }
    @Test fun leavingManualClearsMultiStateAndReturningDoesNotCreateAPermanentGroup() {
        val vm = loaded(); select(vm, 1, 3)
        vm.setPlayOrder(PlayOrder.NATURAL_ASC); ready(vm)
        assertFalse(vm.uiState.value.multiReorder.enabled); assertTrue(view(vm).multiSelectedIds.isEmpty())
        vm.setPlayOrder(PlayOrder.MANUAL); ready(vm)
        assertFalse(vm.uiState.value.multiReorder.enabled); assertTrue(view(vm).multiSelectedIds.isEmpty())
    }
    @Test fun naturalModeCannotEnterMultiOrSelectAnItem() {
        val vm = loaded(PlaybackOrder.NATURAL_ASC)
        vm.setMultiReorder(true); vm.toggleMultiItem(ids[1])
        assertFalse(vm.uiState.value.multiReorder.enabled); assertTrue(view(vm).multiSelectedIds.isEmpty())
    }
    @Test fun multiModeBlocksCompetingSingleGroupAndExplicitPlaybackActions() {
        val vm = loaded(); bridge(); select(vm, 1, 3)
        val before = snapshots.load()
        vm.selectItem(ids[0]); vm.moveItem(ids[0], 1); vm.moveItemTo(ids[0], 6)
        vm.moveGroup(PlaybackGroupKey("DIRECTORY_TREE", "multi"), 1); vm.clickToPlay(ids[0])
        assertEquals(before, snapshots.load()); assertTrue(submitted.isEmpty()); assertNull(view(vm).selectedItemId)
    }
    @Test fun busyMoveDoesNotAcceptConflictingSelectionOrAnotherMove() {
        val vm = loaded(); select(vm, 1, 3)
        vm.moveMultiItemsTo(1)
        assertTrue(vm.uiState.value.isSavingOrder)
        vm.toggleMultiItem(ids[4]); vm.clearMultiSelection(); vm.setMultiReorder(false); vm.moveMultiItemsTo(5)
        ready(vm)
        assertTrue(vm.uiState.value.multiReorder.enabled)
        assertEquals(setOf(ids[1], ids[3]), view(vm).multiSelectedIds)
        assertEquals(listOf(ids[1], ids[3], ids[0], ids[2], ids[4], ids[5]), snapshots.load()!!.queueIds)
    }
    @Test fun afterFinishEachPreviouslySelectedItemCanMoveIndependently() {
        val vm = loaded(); select(vm, 1, 3); vm.moveMultiItemsTo(1); ready(vm)
        vm.setMultiReorder(false); vm.moveItem(ids[1], 1); ready(vm)
        assertEquals(listOf(ids[3], ids[1], ids[0], ids[2], ids[4], ids[5]), snapshots.load()!!.queueIds)
    }
    @Test fun hiddenSavedManualSlotIsPreservedDuringVisibleBlockReorder() {
        val vm = loaded()
        snapshots.update { it!!.copy(queueIds = listOf(ids[0], "file:///hidden.png") + ids.drop(1)) }
        select(vm, 1, 3); vm.moveMultiItemsTo(1); ready(vm)
        assertEquals(listOf(ids[1], "file:///hidden.png", ids[3], ids[0], ids[2], ids[4], ids[5]), snapshots.load()!!.queueIds)
    }
    @Test fun brokenRealDatabaseReadKeepsOrderAndDoesNotHighlightAMove() {
        val vm = loaded(); select(vm, 1, 3)
        val before = snapshots.load()
        database.openHelper.writableDatabase.execSQL("ALTER TABLE image_records RENAME TO multi_missing_image_records")
        try {
            vm.moveMultiItemsTo(1); ready(vm)
            assertEquals(before, snapshots.load())
            assertTrue(view(vm).movedItemIds.isEmpty())
            assertTrue(vm.uiState.value.message!!.contains("失败"))
        } finally { database.openHelper.writableDatabase.execSQL("ALTER TABLE multi_missing_image_records RENAME TO image_records") }
    }
    @Test fun clearingViewModelCancelsPendingMoveWithoutSavingOrHighlighting() {
        val vm = loaded(); select(vm, 1, 3)
        val before = snapshots.load()
        val lock = vm.javaClass.getDeclaredField("coordinatorLock").apply { isAccessible = true }.get(vm) as Mutex
        runBlocking { lock.lock() }
        vm.moveMultiItemsTo(1)
        owner.clear()
        lock.unlock()
        await { !vm.uiState.value.isSavingOrder }
        assertEquals(before, snapshots.load())
        assertTrue(view(vm).movedItemIds.isEmpty())
    }
}
