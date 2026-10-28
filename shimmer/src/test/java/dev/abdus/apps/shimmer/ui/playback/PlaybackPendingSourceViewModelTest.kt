package dev.abdus.apps.shimmer.ui.playback

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p4.PlaybackRule
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import dev.abdus.apps.shimmer.p4.ScheduleStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 真 Room／ViewModel 只读待播：隐藏壁纸时不会伪提交，未执行 Engine／Bitmap／GPU。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackPendingSourceViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(app)
    private val owner = ViewModelStore()
    private val snapshots = PlaybackSnapshotStore(app)
    private val a = (1..2).map { "file:///a/$it.png" }
    private val b = (1..2).map { "file:///b/$it.png" }
    private val initial = PlaybackSnapshot(currentId = a[0], historyIds = listOf(a[0]), historyCursor = 0,
        sourcePositions = mapOf("a" to a[0], "b" to b[1]))

    @After fun close() {
        owner.clear()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    private fun loaded(snapshot: PlaybackSnapshot = initial): PlaybackViewModel {
        runBlocking {
            val dao = database.p1SourceDao()
            for ((root, ids) in listOf("a" to a, "b" to b)) {
                dao.registerSource(P1SourceEntity(root, "file:///$root", "DIRECTORY_TREE", enabled = root == "a"))
                dao.commitScan(root, ids.mapIndexed { index, uri -> P1ImageRecordEntity(
                    "$root-$index", uri, "${index + 1}.png", "deep/${index + 1}.png",
                ) }, "APP_OWNED", "2026-10-10T00:00:00Z")
            }
        }
        snapshots.save(snapshot)
        val vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PlaybackViewModel::class.java]
        vm.screenEntered()
        await { !vm.uiState.value.isLoading && !vm.uiState.value.isReloading }
        vm.uiState.value.scrollRequest?.let { vm.scrollHandled(it.serial) }
        return vm
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("待播投影未在宿主测试等待内完成", condition())
    }

    private fun roots(aEnabled: Boolean, bEnabled: Boolean) = runBlocking {
        val dao = database.p1SourceDao()
        dao.setSourceEnabled("a", aEnabled, "2026-10-10T01:00:00Z")
        dao.setSourceEnabled("b", bEnabled, "2026-10-10T01:00:00Z")
    }

    @Test fun rootSwitchImmediatelyProjectsOwnSavedPointWithoutWritingActualCurrent() {
        val vm = loaded()
        roots(false, true)
        await { vm.uiState.value.pendingSourceImage?.uri == b[1] && !vm.uiState.value.isReloading }
        assertEquals(a[0], vm.uiState.value.currentImage?.uri)
        assertNull(vm.uiState.value.queue.currentId)
        assertEquals(b, vm.uiState.value.queue.ordered.map { it.id })
        assertEquals(initial, snapshots.load())
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun refreshCannotTurnPendingIntoCommittedHistory() {
        val vm = loaded()
        roots(false, true)
        await { vm.uiState.value.pendingSourceImage?.uri == b[1] && !vm.uiState.value.isReloading }
        repeat(3) {
            vm.refresh()
            await { vm.uiState.value.pendingSourceImage?.uri == b[1] && !vm.uiState.value.isReloading }
            assertEquals(initial, snapshots.load())
        }
    }

    @Test fun formalCommittedCurrentReplacesPendingCardAndMarksActualRow() {
        val vm = loaded()
        roots(false, true)
        await { vm.uiState.value.pendingSourceImage?.uri == b[1] && !vm.uiState.value.isReloading }
        val committed = initial.copy(currentId = b[1], historyIds = listOf(a[0], b[1]), historyCursor = 1)
        snapshots.save(committed)
        await { vm.uiState.value.pendingSourceImage == null && vm.uiState.value.currentImage?.uri == b[1] &&
            vm.uiState.value.queue.currentId == b[1] && !vm.uiState.value.isReloading }
        assertEquals(committed, snapshots.load())
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun neverPlayedRootUsesFirstRealItemRatherThanOtherRootsIndex() {
        val stored = initial.copy(sourcePositions = mapOf("a" to a[0]))
        val vm = loaded(stored)
        roots(false, true)
        await { vm.uiState.value.pendingSourceImage?.uri == b[0] && !vm.uiState.value.isReloading }
        assertEquals(stored, snapshots.load())
    }

    @Test fun rapidlyReturningToOriginalRootCannotRetainLateBProjection() {
        val vm = loaded()
        roots(false, true)
        roots(true, false)
        await { vm.uiState.value.queue.ordered.map { it.id } == a && !vm.uiState.value.isReloading }
        assertNull(vm.uiState.value.pendingSourceImage)
        assertEquals(a[0], vm.uiState.value.currentImage?.uri)
        assertEquals(initial, snapshots.load())
    }

    @Test fun mixedAndEmptySourcesClearPendingButKeepDisplayedCurrent() {
        val vm = loaded()
        roots(false, true)
        await { vm.uiState.value.pendingSourceImage?.uri == b[1] && !vm.uiState.value.isReloading }
        roots(true, true)
        await { vm.uiState.value.enabledCount == 4 && !vm.uiState.value.isReloading }
        assertNull(vm.uiState.value.pendingSourceImage)
        roots(false, false)
        await { vm.uiState.value.enabledCount == 0 && !vm.uiState.value.isReloading }
        assertNull(vm.uiState.value.pendingSourceImage)
        assertEquals(a[0], vm.uiState.value.currentImage?.uri)
        assertEquals(initial, snapshots.load())
    }

    @Test fun ruleChangeProjectsItsSingleEffectiveRootWithoutSnapshotMutation() {
        val vm = loaded()
        ScheduleStore(app).saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("r", "测试规则", sourceIds = listOf("b")))))
        await { vm.uiState.value.activeRuleName == "测试规则" && vm.uiState.value.pendingSourceImage?.uri == b[1] &&
            !vm.uiState.value.isReloading }
        assertEquals(initial, snapshots.load())
        assertEquals(a[0], vm.uiState.value.currentImage?.uri)
    }

    @Test fun removedSavedTargetIsNeverShownAsValidPending() {
        val vm = loaded()
        runBlocking { database.p1SourceDao().setDirectoryExcluded("b", "deep", true, "2026-10-10T01:00:00Z") }
        roots(false, true)
        await { vm.uiState.value.enabledCount == 0 && !vm.uiState.value.isReloading }
        assertNull(vm.uiState.value.pendingSourceImage)
        assertEquals(initial, snapshots.load())
    }

    @Test fun lateLoadedPendingWithOldSnapshotIsRejectedEvenWhenOrderUnchanged() {
        val vm = loaded()
        roots(false, true)
        await { vm.uiState.value.pendingSourceImage?.uri == b[1] && !vm.uiState.value.isReloading }
        val revision = vm.javaClass.getDeclaredField("catalogRevision").apply { isAccessible = true }.getLong(vm)
        val loadedClass = vm.javaClass.declaredClasses.single { it.simpleName == "Loaded" }
        val oldLoaded = loadedClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            vm.uiState.value.queue, initial.currentId, initial, 2, null, false, emptyMap<String, String>(), revision,
        )
        loadedClass.getDeclaredField("pendingSourceId").apply { isAccessible = true }.set(oldLoaded, b[1])
        loadedClass.getDeclaredField("pendingSourceImage").apply { isAccessible = true }.set(oldLoaded, vm.uiState.value.pendingSourceImage)
        val committed = initial.copy(currentId = b[0], historyIds = listOf(a[0], b[0]), historyCursor = 1)
        snapshots.save(committed)
        vm.javaClass.getDeclaredMethod("applyLoaded", loadedClass, CurrentImageInfo::class.java)
            .apply { isAccessible = true }.invoke(vm, oldLoaded, vm.uiState.value.currentImage)
        await { vm.uiState.value.pendingSourceImage == null && vm.uiState.value.currentImage?.uri == b[0] &&
            !vm.uiState.value.isReloading }
        assertEquals(committed, snapshots.load())
    }

    @Test fun failedRealCatalogReadRetainsLastConfirmedPendingAndActualCurrent() {
        val vm = loaded()
        roots(false, true)
        await { vm.uiState.value.pendingSourceImage?.uri == b[1] && !vm.uiState.value.isReloading }
        val before = vm.uiState.value
        // 宿主临时数据库中的真实 SQL 故障，未删除记录，不以 close() 假定查询一定失败。
        val sql = database.openHelper.writableDatabase
        sql.execSQL("ALTER TABLE image_records RENAME TO pending_test_unavailable_records")
        try {
            vm.refresh()
            await { !vm.uiState.value.isReloading && vm.uiState.value.message?.startsWith("读取播放状态失败") == true }
            assertEquals(before.currentImage, vm.uiState.value.currentImage)
            assertEquals(before.pendingSourceImage, vm.uiState.value.pendingSourceImage)
            assertEquals(before.queue, vm.uiState.value.queue)
            assertEquals(initial, snapshots.load())
        } finally {
            sql.execSQL("ALTER TABLE pending_test_unavailable_records RENAME TO image_records")
        }
    }

    @Test fun closingViewModelCancelsProjectionReadsAndObservation() {
        val vm = loaded()
        owner.clear()
        val before = vm.uiState.value
        roots(false, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, vm.uiState.value)
    }
}
