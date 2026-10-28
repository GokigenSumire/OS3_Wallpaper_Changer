package dev.abdus.apps.shimmer.ui.navigation

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSelectionBridge
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p2.SourcePreviewOverrides
import dev.abdus.apps.shimmer.p4.IntervalClock
import dev.abdus.apps.shimmer.p4.IntervalUnit
import dev.abdus.apps.shimmer.p4.ScheduleStore
import dev.abdus.apps.shimmer.p5.TransitionChoice
import dev.abdus.apps.shimmer.p5.TransitionSettingsStore
import dev.abdus.apps.shimmer.ui.effects.EffectsViewModel
import dev.abdus.apps.shimmer.ui.effects.FitMode
import dev.abdus.apps.shimmer.ui.effects.TransitionEffect
import dev.abdus.apps.shimmer.ui.gallery.GalleryViewModel
import dev.abdus.apps.shimmer.ui.playback.PlayOrder
import dev.abdus.apps.shimmer.ui.playback.PlaybackViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 真实 Room 与偏好；页面切域仅换 ViewModel，不运行系统壁纸、手机或 GPU。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TargetPageViewModelIsolationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val owner = ViewModelStore()
    private val home = WallpaperTarget.HOME
    private val lock = WallpaperTarget.LOCK
    private val uris = listOf("file:///shared/1.jpg", "file:///shared/2.jpg")

    private fun preferenceSnapshot(): Map<String, Map<String, *>> {
        val names = listOf("shimmer_wallpaper_prefs", WallpaperPreferences.LOCK_DOMAIN_NAME) +
            WallpaperTarget.entries.flatMap { target -> listOf(
                PlaybackSnapshotStore.nameFor(target), ScheduleStore.nameFor(target),
                TransitionSettingsStore.nameFor(target), SourcePreviewOverrides.nameFor(target),
            ) }
        return names.associateWith { app.getSharedPreferences(it, Application.MODE_PRIVATE).all.toMap() }
    }

    @After fun cleanup() {
        owner.clear()
        PlaybackSelectionBridge.unregister("target-page-home")
        PlaybackSelectionBridge.unregister("target-page-lock")
        shadowOf(Looper.getMainLooper()).idle()
        listOf("INSTANCE", "LOCK_INSTANCE").forEach { name ->
            ShimmerDatabase::class.java.getDeclaredField(name).apply {
                isAccessible = true
                (get(null) as? ShimmerDatabase)?.close()
                set(null, null)
            }
        }
    }

    private inline fun <reified T : ViewModel> kept(target: WallpaperTarget, crossinline create: () -> T): T =
        ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        })[WallpaperTargetUi.viewModelKey(T::class.java.name, target), T::class.java]

    private fun gallery(target: WallpaperTarget): GalleryViewModel =
        kept(target) { GalleryViewModel(app, target) }.also { vm ->
            vm.refresh()
            await { !vm.uiState.value.isLoading }
        }

    private fun playback(target: WallpaperTarget): PlaybackViewModel =
        kept(target) { PlaybackViewModel(app, target) }.also { vm ->
            vm.screenEntered()
            await { !vm.uiState.value.isLoading && !vm.uiState.value.isReloading }
        }

    private fun effects(target: WallpaperTarget): EffectsViewModel =
        kept(target) { EffectsViewModel(app, target) }.also { vm ->
            vm.refresh()
            await { !vm.uiState.value.isLoading }
        }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("双域页面的宿主操作未完成", condition())
    }

    private fun seed(target: WallpaperTarget) = runBlocking {
        ShimmerDatabase.getInstance(app, target).p1SourceDao().also { dao ->
            dao.registerSource(P1SourceEntity("same-source", "file:///${target.name}", "MULTIPLE_DOCUMENTS", enabled = true))
            dao.commitScan("same-source", uris.mapIndexed { index, uri ->
                P1ImageRecordEntity("same-$index", uri, "${target.name}-${index + 1}.jpg", "${index + 1}.jpg")
            }, "APP_OWNED", "2026-10-17T00:00:00Z")
        }
        PlaybackSnapshotStore(app, target).save(PlaybackSnapshot(order = PlaybackOrder.MANUAL,
            queueIds = uris, currentId = if (target == home) uris[0] else uris[1],
            historyIds = uris, historyCursor = if (target == home) 0 else 1))
    }

    @Test fun legacyApplicationOnlyConstructorsAndAndroidFactoryRemainHomeCompatible() {
        listOf(GalleryViewModel::class.java, PlaybackViewModel::class.java, EffectsViewModel::class.java).forEach { type ->
            assertNotNull(type.getConstructor(Application::class.java))
        }
        val provider = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))
        assertEquals(home, provider[GalleryViewModel::class.java].wallpaperTarget)
        assertEquals(home, provider[PlaybackViewModel::class.java].wallpaperTarget)
        assertEquals(home, provider[EffectsViewModel::class.java].wallpaperTarget)
    }

    @Test fun gallerySameSourceIdsHaveSeparateRecordsAndPreviewOverrides() {
        seed(home)
        seed(lock)
        SourcePreviewOverrides(app, home).set("MULTIPLE_DOCUMENTS", "same-source", "file:///home-preview.jpg")
        SourcePreviewOverrides(app, lock).set("MULTIPLE_DOCUMENTS", "same-source", "file:///lock-preview.jpg")
        val homeVm = gallery(home)
        val lockVm = gallery(lock)
        assertNotSame(homeVm, lockVm)
        assertEquals("file:///home-preview.jpg", homeVm.uiState.value.sources.single().previewUri)
        assertEquals("file:///lock-preview.jpg", lockVm.uiState.value.sources.single().previewUri)
        assertTrue(homeVm.uiState.value.sources.single().location.contains("HOME"))
        assertTrue(lockVm.uiState.value.sources.single().location.contains("LOCK"))
    }

    @Test fun galleryLockSourceToggleNeverChangesHomeSourceOrHistory() {
        seed(home)
        seed(lock)
        val beforeHome = PlaybackSnapshotStore(app, home).load()
        val homeVm = gallery(home)
        val lockVm = gallery(lock)
        lockVm.toggleSourceEnabled("same-source")
        await { !lockVm.uiState.value.sources.single().isEnabled }
        assertTrue(homeVm.uiState.value.sources.single().isEnabled)
        assertTrue(runBlocking { ShimmerDatabase.getInstance(app, home).p1SourceDao().getSource("same-source")!!.enabled })
        assertEquals(beforeHome, PlaybackSnapshotStore(app, home).load())
    }

    @Test fun galleryPerImageToggleWithSharedUriIsTargetLocal() {
        seed(home)
        seed(lock)
        gallery(home)
        val lockVm = gallery(lock)
        lockVm.setBatchImageEnabled("same-source", uris[0], false)
        await { uris[0] in WallpaperPreferences.create(app, lock).getDisabledImages() }
        assertTrue(WallpaperPreferences.create(app, home).getDisabledImages().isEmpty())
    }

    @Test fun playbackTargetEntryReadsOwnCurrentAndDoesNotWriteEitherSnapshot() {
        seed(home)
        seed(lock)
        val homeBefore = PlaybackSnapshotStore(app, home).load()
        val lockBefore = PlaybackSnapshotStore(app, lock).load()
        val prefsBefore = preferenceSnapshot()
        val homeVm = playback(home)
        val lockVm = playback(lock)
        assertEquals("HOME-1.jpg", homeVm.uiState.value.currentImage!!.fileName)
        assertEquals("LOCK-2.jpg", lockVm.uiState.value.currentImage!!.fileName)
        assertEquals(homeBefore, PlaybackSnapshotStore(app, home).load())
        assertEquals(lockBefore, PlaybackSnapshotStore(app, lock).load())
        assertEquals(prefsBefore, preferenceSnapshot())
        assertSame(homeVm, playback(home))
        assertEquals(homeBefore, PlaybackSnapshotStore(app, home).load())
        assertEquals(lockBefore, PlaybackSnapshotStore(app, lock).load())
    }

    @Test fun playbackOrderAndMultiSelectionAreIndependentAcrossTargets() {
        seed(home)
        seed(lock)
        val homeVm = playback(home)
        val lockVm = playback(lock)
        homeVm.setMultiReorder(true)
        homeVm.toggleMultiItem(uris[0])
        assertFalse(lockVm.uiState.value.multiReorder.enabled)
        assertTrue(lockVm.uiState.value.multiReorder.selectedIds.isEmpty())
        lockVm.setPlayOrder(PlayOrder.NATURAL_DESC)
        await { !lockVm.uiState.value.isSavingOrder && lockVm.uiState.value.selectedOrder == PlayOrder.NATURAL_DESC }
        assertEquals(PlaybackOrder.MANUAL, PlaybackSnapshotStore(app, home).load()!!.order)
        assertEquals(PlaybackOrder.NATURAL_DESC, PlaybackSnapshotStore(app, lock).load()!!.order)
        assertEquals(setOf(uris[0]), homeVm.uiState.value.multiReorder.selectedIds)
    }

    @Test fun lockPlaybackSelectionOnlyDispatchesToLockEngineAndDoesNotCommitSnapshot() {
        seed(home)
        seed(lock)
        val calls = mutableListOf<String>()
        fun register(id: String, target: WallpaperTarget) {
            PlaybackSelectionBridge.register(id, object : PlaybackSelectionBridge.Target {
                override fun select(itemId: String): PlaybackSelectionBridge.Result {
                    calls += "${target.name}:$itemId"
                    return PlaybackSelectionBridge.Result.QUEUED
                }
            }, target)
            PlaybackSelectionBridge.markVisible(id)
        }
        register("target-page-lock", lock)
        register("target-page-home", home)
        val homeBefore = PlaybackSnapshotStore(app, home).load()
        val lockBefore = PlaybackSnapshotStore(app, lock).load()
        playback(lock).clickToPlay(uris[0])
        assertEquals(listOf("LOCK:${uris[0]}"), calls)
        assertEquals(homeBefore, PlaybackSnapshotStore(app, home).load())
        assertEquals(lockBefore, PlaybackSnapshotStore(app, lock).load())
    }

    @Test fun effectsReadOnlyEntryDoesNotInitializeOrWriteDomainSettings() {
        val homePreferences = WallpaperPreferences.create(app, home).getP3Defaults()
        val lockPreferences = WallpaperPreferences.create(app, lock).getP3Defaults()
        val homeSchedule = ScheduleStore(app, home).loadSettings()
        val lockSchedule = ScheduleStore(app, lock).loadSettings()
        val homeTransition = TransitionSettingsStore(app, home).load()
        val lockTransition = TransitionSettingsStore(app, lock).load()
        val prefsBefore = preferenceSnapshot()
        effects(home)
        effects(lock)
        assertEquals(homePreferences, WallpaperPreferences.create(app, home).getP3Defaults())
        assertEquals(lockPreferences, WallpaperPreferences.create(app, lock).getP3Defaults())
        assertEquals(homeSchedule, ScheduleStore(app, home).loadSettings())
        assertEquals(lockSchedule, ScheduleStore(app, lock).loadSettings())
        assertEquals(homeTransition, TransitionSettingsStore(app, home).load())
        assertEquals(lockTransition, TransitionSettingsStore(app, lock).load())
        assertEquals(prefsBefore, preferenceSnapshot())
    }

    @Test fun lockEffectsWritesFitTransitionAndScheduleOnlyToLockDomain() {
        val homeDefaults = WallpaperPreferences.create(app, home).getP3Defaults()
        val homeSchedule = ScheduleStore(app, home).loadSettings()
        val homeTransition = TransitionSettingsStore(app, home).load()
        val vm = effects(lock)
        vm.setFitMode(FitMode.FIT_WIDTH)
        vm.toggleBlurFill(true)
        vm.setTransitionEffect(TransitionEffect.SLIDE)
        vm.setTransitionDuration(487)
        vm.setInterval(17, IntervalUnit.SECONDS, IntervalClock.WALL)
        vm.toggleInterval(true)
        vm.toggleUnlock(true)
        await { TransitionSettingsStore(app, lock).load().durationMillis == 487 && ScheduleStore(app, lock).loadSettings().onUnlock }
        assertEquals(FitMode.FIT_WIDTH, vm.uiState.value.selectedFitMode)
        assertTrue(WallpaperPreferences.create(app, lock).getP3Defaults().blurFill)
        assertEquals(TransitionChoice.SLIDE, TransitionSettingsStore(app, lock).load().choice)
        assertEquals(17, ScheduleStore(app, lock).loadSettings().interval.value)
        assertEquals(homeDefaults, WallpaperPreferences.create(app, home).getP3Defaults())
        assertEquals(homeSchedule, ScheduleStore(app, home).loadSettings())
        assertEquals(homeTransition, TransitionSettingsStore(app, home).load())
    }

    @Test fun lockAndScreenOffSwitchesAreVisibleInTargetStateAndNeverChangeHome() {
        val homeBefore = ScheduleStore(app, home).loadSettings()
        val lockVm = effects(lock)
        lockVm.toggleLock(true)
        lockVm.toggleScreenOff(true)
        await { lockVm.uiState.value.lockEnabled && lockVm.uiState.value.screenOffEnabled }
        assertTrue(ScheduleStore(app, lock).loadSettings().onLock)
        assertTrue(ScheduleStore(app, lock).loadSettings().onScreenOff)
        assertEquals(homeBefore, ScheduleStore(app, home).loadSettings())
        lockVm.refresh()
        await { !lockVm.uiState.value.isLoading }
        assertTrue(lockVm.uiState.value.lockEnabled)
        assertTrue(lockVm.uiState.value.screenOffEnabled)
    }
}
