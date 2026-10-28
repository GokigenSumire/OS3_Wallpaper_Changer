package dev.abdus.apps.shimmer.ui.playback

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
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

/** 真实 Room／偏好／ViewModel；不执行手机、位图或 GPU。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DisabledPlaybackViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(app)
    private val preferences = WallpaperPreferences.create(app)
    private val snapshots = PlaybackSnapshotStore(app)
    private val owner = ViewModelStore()
    private val ids = (1..120).map { "file:///album/%03d.jpg".format(it) }
    private val engine = "disabled-playback-test"
    private val played = mutableListOf<String>()

    @After fun close() {
        owner.clear()
        PlaybackSelectionBridge.unregister(engine)
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("停用列表宿主检查未在等待内完成", condition())
    }

    private fun ready(vm: PlaybackViewModel) = await {
        !vm.uiState.value.isLoading && !vm.uiState.value.isReloading &&
            !vm.uiState.value.isLoadingDisabled && !vm.uiState.value.isRestoringDisabled
    }

    private fun loaded(stopped: Set<String> = setOf(ids[1]), count: Int = 4): PlaybackViewModel {
        runBlocking {
            val dao = database.p1SourceDao()
            dao.registerSource(P1SourceEntity("album", "file:///album", "DIRECTORY_TREE", enabled = true))
            dao.commitScan("album", ids.take(count).mapIndexed { i, uri ->
                P1ImageRecordEntity("image-$i", uri, "%03d.jpg".format(i + 1), "子目录/%03d.jpg".format(i + 1))
            }, "APP_OWNED", "2026-10-16T00:00:00Z")
        }
        stopped.forEach { preferences.setImageEnabled(it, false) }
        snapshots.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = ids.take(count),
            currentId = ids[0], historyIds = listOf(ids[0], ids[1], ids[0]), historyCursor = 2,
            sourcePositions = mapOf("album" to ids[0])))
        PlaybackSelectionBridge.register(engine, object : PlaybackSelectionBridge.Target {
            override fun select(itemId: String): PlaybackSelectionBridge.Result {
                played += itemId
                return PlaybackSelectionBridge.Result.QUEUED
            }
        })
        PlaybackSelectionBridge.markVisible(engine)
        return ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PlaybackViewModel::class.java].also {
            it.screenEntered()
            ready(it)
            it.uiState.value.scrollRequest?.let { request -> it.scrollHandled(request.serial) }
        }
    }

    @Test fun actualPerImageStopsAppearOnceAndAreDisjointFromTheEffectiveQueue() {
        val vm = loaded()
        val state = vm.uiState.value
        assertEquals(listOf(ids[1]), state.disabledPage.entries.map { it.id })
        assertEquals(listOf(ids[0], ids[2], ids[3]), state.queue.ordered.map { it.id })
        assertTrue(state.disabledPage.entries.none { it.id in state.queue.positions })
        assertEquals("002.jpg", state.disabledPage.entries.single().name)
        assertEquals("子目录/002.jpg", state.disabledPage.entries.single().detail)
    }

    @Test fun sourceDisableDoesNotMisclassifyAllItsImagesAsPerImageStops() {
        val vm = loaded(stopped = emptySet())
        runBlocking { database.p1SourceDao().setSourceEnabled("album", false, null) }
        await { vm.uiState.value.queue.size == 0 && !vm.uiState.value.isReloading }
        assertEquals(0, vm.uiState.value.disabledPage.totalCount)
        assertTrue(vm.uiState.value.disabledPage.entries.isEmpty())
    }

    @Test fun folderExclusionDoesNotCreatePerImageStopRows() {
        val vm = loaded(stopped = emptySet())
        runBlocking { database.p1SourceDao().setDirectoryExcluded("album", "子目录", true, null) }
        await { vm.uiState.value.queue.size == 0 && !vm.uiState.value.isReloading }
        assertEquals(0, vm.uiState.value.disabledPage.totalCount)
    }

    @Test fun changingPerImageStopPreferenceMovesTheRowBetweenBothSectionsAtomically() {
        val vm = loaded(stopped = emptySet())
        preferences.setImageEnabled(ids[2], false)
        await { vm.uiState.value.disabledPage.disabledIds == setOf(ids[2]) && !vm.uiState.value.isReloading }
        assertFalse(ids[2] in vm.uiState.value.queue.positions)
        preferences.setImageEnabled(ids[2], true)
        await { vm.uiState.value.disabledPage.totalCount == 0 && ids[2] in vm.uiState.value.queue.positions && !vm.uiState.value.isReloading }
    }

    @Test fun explicitRestoreKeepsCurrentHistoryAndSavedManualPositionsAndDoesNotPlay() {
        val vm = loaded()
        val before = snapshots.load()
        vm.restoreDisabledImage(ids[1])
        await { ids[1] in vm.uiState.value.queue.positions && vm.uiState.value.disabledPage.totalCount == 0 && !vm.uiState.value.isReloading }
        assertEquals(before, snapshots.load())
        assertTrue(played.isEmpty())
        assertEquals(ids.take(4), vm.uiState.value.queue.ordered.map { it.id })
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun restoringAStoppedImageDoesNotEnableItsWholeSource() {
        val vm = loaded()
        runBlocking { database.p1SourceDao().setSourceEnabled("album", false, null) }
        await { vm.uiState.value.queue.size == 0 && !vm.uiState.value.isReloading }
        vm.restoreDisabledImage(ids[1])
        await { vm.uiState.value.disabledPage.totalCount == 0 && !vm.uiState.value.isReloading }
        assertEquals(0, vm.uiState.value.queue.size)
        assertFalse(runBlocking { database.p1SourceDao().getSource("album")!!.enabled })
        assertTrue(played.isEmpty())
    }

    @Test fun restoringAStoppedImageDoesNotReenableAnExcludedFolder() {
        val vm = loaded()
        runBlocking { database.p1SourceDao().setDirectoryExcluded("album", "子目录", true, null) }
        await { vm.uiState.value.queue.size == 0 && !vm.uiState.value.isReloading }
        vm.restoreDisabledImage(ids[1])
        await { vm.uiState.value.disabledPage.totalCount == 0 && !vm.uiState.value.isReloading }
        assertEquals(0, vm.uiState.value.queue.size)
        assertTrue(played.isEmpty())
    }

    @Test fun restoreAllClearsOnlyStopsAndPreservesTheExactSnapshot() {
        val vm = loaded(stopped = ids.take(4).toSet())
        val before = snapshots.load()
        vm.restoreAllDisabledImages()
        await { vm.uiState.value.disabledPage.totalCount == 0 && vm.uiState.value.queue.size == 4 && !vm.uiState.value.isReloading }
        assertTrue(preferences.getDisabledImages().isEmpty())
        assertEquals(before, snapshots.load())
        assertTrue(played.isEmpty())
    }

    @Test fun stoppedItemCannotBePlayedOrSelectedForManualSingleOrMultiReorder() {
        val vm = loaded()
        val before = snapshots.load()
        vm.clickToPlay(ids[1])
        vm.selectItem(ids[1])
        vm.setMultiReorder(true)
        vm.toggleMultiItem(ids[1])
        assertTrue(vm.uiState.value.multiReorder.selectedIds.isEmpty())
        val view = vm.javaClass.getDeclaredField("queueView").apply { isAccessible = true }.get(vm) as QueueViewState
        assertNull(view.selectedItemId)
        assertTrue(played.isEmpty())
        assertEquals(before, snapshots.load())
        assertEquals(setOf(ids[1]), preferences.getDisabledImages())
    }

    @Test fun paginationKeepsQueueIdentityFocusAndSnapshotAndCanReachPastTheOldTwoHundredLimit() {
        val stopped = (1..251).map { "file:///missing/%03d.jpg".format(it) }.toSet()
        val vm = loaded(stopped = stopped)
        assertEquals(50, vm.uiState.value.disabledPage.entries.size)
        val ordered = vm.uiState.value.queue.ordered
        val before = snapshots.load()
        repeat(5) {
            vm.showMoreDisabledImages()
            ready(vm)
            assertSame(ordered, vm.uiState.value.queue.ordered)
            assertNull(vm.uiState.value.scrollRequest)
        }
        assertEquals(251, vm.uiState.value.disabledPage.entries.size)
        assertEquals(251, vm.uiState.value.disabledPage.entries.map { it.id }.distinct().size)
        assertEquals(0, vm.uiState.value.disabledPage.remainingCount)
        assertEquals(before, snapshots.load())
        assertTrue(played.isEmpty())
    }

    @Test fun sourceMissingImageStillHasARecoverableRowAndNoFakePlaybackMembership() {
        val missing = "file:///deleted/孤立.jpg"
        val vm = loaded(stopped = setOf(missing))
        val entry = vm.uiState.value.disabledPage.entries.single()
        assertEquals("孤立.jpg", entry.name)
        assertEquals("来源记录中已没有这张图片", entry.detail)
        assertFalse(missing in vm.uiState.value.queue.positions)
        vm.restoreDisabledImage(missing)
        await { vm.uiState.value.disabledPage.totalCount == 0 && !vm.uiState.value.isReloading }
        assertFalse(missing in vm.uiState.value.queue.positions)
        assertTrue(played.isEmpty())
    }

    @Test fun staleRestoreTargetDoesNotEnableAnUnrelatedImageOrChangeTheSnapshot() {
        val vm = loaded()
        val before = snapshots.load()
        vm.restoreDisabledImage("file:///not-in-stops.jpg")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(ids[1]), preferences.getDisabledImages())
        assertEquals(before, snapshots.load())
        assertTrue(played.isEmpty())
    }

    @Test fun reenteringPlaybackKeepsAlreadyRequestedDisabledPagesWithoutPositionRequests() {
        val vm = loaded(stopped = ids.toSet(), count = 120)
        vm.showMoreDisabledImages()
        ready(vm)
        assertEquals(100, vm.uiState.value.disabledPage.entries.size)
        vm.screenEntered()
        ready(vm)
        assertEquals(100, vm.uiState.value.disabledPage.entries.size)
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun staleReadCannotPublishAnEnabledAndDisabledDuplicateAtTheSameTime() {
        val vm = loaded(stopped = emptySet())
        val old = vm.uiState.value
        val revision = vm.javaClass.getDeclaredField("catalogRevision").apply { isAccessible = true }.getLong(vm)
        val loadedClass = vm.javaClass.declaredClasses.single { it.simpleName == "Loaded" }
        val mixed = loadedClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            old.queue, ids[0], snapshots.load(), 4, null, false, emptyMap<String, String>(), revision,
        )
        val stopped = setOf(ids[1])
        loadedClass.getDeclaredField("disabledPage").apply { isAccessible = true }
            .set(mixed, DisabledPlaybackList.page(stopped, 50, emptyMap()))
        // 不等偏好listener推进版本：直接将新停用偏好和旧有效队列的混合读交给提交门禁。
        preferences.setImageEnabled(ids[1], false)
        vm.javaClass.getDeclaredMethod("applyLoaded", loadedClass, CurrentImageInfo::class.java)
            .apply { isAccessible = true }.invoke(vm, mixed, old.currentImage)
        assertFalse(vm.uiState.value.disabledPage.disabledIds.any { it in vm.uiState.value.queue.positions })
        await { vm.uiState.value.disabledPage.disabledIds == stopped && !vm.uiState.value.isReloading }
        assertFalse(ids[1] in vm.uiState.value.queue.positions)
    }
}
