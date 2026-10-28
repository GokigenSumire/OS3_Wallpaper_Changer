package dev.abdus.apps.shimmer.ui.playback

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.P1SourceImageLinkEntity
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

/** 真 ViewModel／Room／偏好观察；不执行壁纸 Engine、Bitmap 或 GPU。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackSourceUiViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(app)
    private val owner = ViewModelStore()
    private val snapshots = PlaybackSnapshotStore(app)
    private val a = (1..2).map { "file:///a/deep/$it.png" }
    private val b = (1..2).map { "file:///b/deep/$it.png" }
    private val initial = PlaybackSnapshot(currentId = a[0], historyIds = listOf(a[0]), historyCursor = 0,
        sourcePositions = mapOf("a" to a[0], "b" to b[1]))

    @After fun close() {
        owner.clear()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    private fun loaded(): PlaybackViewModel {
        runBlocking {
            val dao = database.p1SourceDao()
            for ((root, ids) in listOf("a" to a, "b" to b)) {
                dao.registerSource(P1SourceEntity(root, "file:///$root", "DIRECTORY_TREE", enabled = root == "a"))
                dao.commitScan(root, ids.mapIndexed { i, uri -> record(root, i, uri) }, "APP_OWNED", "2026-10-10T00:00:00Z")
            }
        }
        snapshots.save(initial)
        val vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PlaybackViewModel::class.java]
        vm.screenEntered()
        await { !vm.uiState.value.isLoading && !vm.uiState.value.isReloading }
        vm.uiState.value.scrollRequest?.let { vm.scrollHandled(it.serial) }
        return vm
    }

    private fun record(root: String, i: Int, uri: String) = P1ImageRecordEntity(
        id = "$root-$i", uri = uri, displayName = "${i + 1}.png", relativePath = "deep/${i + 1}.png",
    )

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("播放来源同步未在宿主测试等待内完成", condition())
    }

    private fun roots(aEnabled: Boolean, bEnabled: Boolean) = runBlocking {
        val dao = database.p1SourceDao()
        dao.setSourceEnabled("a", aEnabled, "2026-10-10T01:00:00Z")
        dao.setSourceEnabled("b", bEnabled, "2026-10-10T01:00:00Z")
    }

    @Test fun rootSwitchAutomaticallyRebuildsTheListWithoutReloadButton() {
        val vm = loaded()
        roots(false, true)
        await { vm.uiState.value.queue.ordered.map { it.id } == b && !vm.uiState.value.isReloading }
        assertEquals(a[0], vm.uiState.value.currentImage?.uri) // 页面不能自行播放来源 B。
        assertNull(vm.uiState.value.queue.currentId)
        assertEquals(initial, snapshots.load())
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun restoredFormalCurrentMarksTheNewRootAndUpdatesTheCard() {
        val vm = loaded()
        roots(false, true)
        snapshots.save(initial.copy(currentId = b[1], historyIds = listOf(a[0], b[1]), historyCursor = 1))
        await { vm.uiState.value.queue.ordered.map { it.id } == b && vm.uiState.value.queue.currentId == b[1] &&
            vm.uiState.value.currentImage?.uri == b[1] && !vm.uiState.value.isReloading }
        val rootLevel = vm.uiState.value.queueRows.filterIsInstance<QueueRow.Level>().first()
        if (!rootLevel.expanded) vm.toggleLevel(rootLevel.levelKey)
        val deepLevel = vm.uiState.value.queueRows.filterIsInstance<QueueRow.Level>().last()
        if (!deepLevel.expanded) vm.toggleLevel(deepLevel.levelKey)
        assertTrue(vm.uiState.value.queueRows.filterIsInstance<QueueRow.Image>().single { it.item.id == b[1] }.isCurrent)
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun returningToARebuildsAndUsesOnlyTheActualCommittedCurrent() {
        val vm = loaded()
        roots(false, true)
        snapshots.save(initial.copy(currentId = b[1], historyIds = listOf(a[0], b[1]), historyCursor = 1))
        await { vm.uiState.value.queue.currentId == b[1] && vm.uiState.value.queue.ordered.map { it.id } == b }
        roots(true, false)
        snapshots.save(initial)
        await { vm.uiState.value.queue.currentId == a[0] && vm.uiState.value.queue.ordered.map { it.id } == a &&
            vm.uiState.value.currentImage?.uri == a[0] }
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun stoppingAllSourcesRetainsTheCommittedCardAndSnapshot() {
        val vm = loaded()
        roots(false, false)
        await { vm.uiState.value.enabledCount == 0 && !vm.uiState.value.isReloading }
        assertEquals(a[0], vm.uiState.value.currentImage?.uri)
        assertNull(vm.uiState.value.queue.currentId)
        assertEquals(initial, snapshots.load())
    }

    @Test fun imageRecordInvalidationUpdatesCurrentMetadataWithoutAButton() {
        val vm = loaded()
        runBlocking { database.p1SourceDao().updateImages(listOf(record("a", 0, a[0]).copy(displayName = "更新后的名称.png"))) }
        await { vm.uiState.value.currentImage?.fileName == "更新后的名称.png" && !vm.uiState.value.isReloading }
        assertEquals(a[0], vm.uiState.value.queue.currentId)
    }

    @Test fun linkTableInvalidationAddsAnImageToTheActualList() {
        val vm = loaded()
        val uri = "file:///a/deep/3.png"
        runBlocking {
            database.p1SourceDao().insertImages(listOf(record("a", 2, uri)))
            database.p1SourceDao().insertLinks(listOf(P1SourceImageLinkEntity("a", "a-2", 2)))
        }
        await { vm.uiState.value.queue.ordered.map { it.id } == a + uri && !vm.uiState.value.isReloading }
        assertEquals(3, vm.uiState.value.enabledCount)
    }

    @Test fun directoryExclusionInvalidationAutomaticallyRemovesItsDescendants() {
        val vm = loaded()
        runBlocking { database.p1SourceDao().setDirectoryExcluded("a", "deep", true, "2026-10-10T01:00:00Z") }
        await { vm.uiState.value.enabledCount == 0 && !vm.uiState.value.isReloading }
        assertEquals(a[0], vm.uiState.value.currentImage?.uri)
        runBlocking { database.p1SourceDao().setDirectoryExcluded("a", "deep", false, "2026-10-10T01:00:00Z") }
        await { vm.uiState.value.queue.ordered.map { it.id } == a && !vm.uiState.value.isReloading }
    }

    @Test fun perImageDisabledPreferenceAutomaticallyChangesTheActualList() {
        val vm = loaded()
        val preferences = WallpaperPreferences.create(app)
        preferences.setImageEnabled(a[1], false)
        await { vm.uiState.value.queue.ordered.map { it.id } == listOf(a[0]) && !vm.uiState.value.isReloading }
        preferences.setImageEnabled(a[1], true)
        await { vm.uiState.value.queue.ordered.map { it.id } == a && !vm.uiState.value.isReloading }
    }

    @Test fun ruleSettingsListenerUpdatesTheEffectiveSourceList() {
        val vm = loaded()
        ScheduleStore(app).saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("r", "测试规则", sourceIds = listOf("b")))))
        await { vm.uiState.value.queue.ordered.map { it.id } == b && vm.uiState.value.activeRuleName == "测试规则" &&
            !vm.uiState.value.isReloading }
        assertEquals(a[0], vm.uiState.value.currentImage?.uri)
        assertNull(vm.uiState.value.scrollRequest)
    }

    @Test fun oldLoadedCatalogCannotOverwriteTheNewSourceRevision() {
        val vm = loaded()
        val old = vm.uiState.value
        val revision = vm.javaClass.getDeclaredField("catalogRevision").apply { isAccessible = true }.getLong(vm)
        val loadedClass = vm.javaClass.declaredClasses.single { it.simpleName == "Loaded" }
        val oldLoaded = loadedClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            old.queue, initial.currentId, snapshots.load(), 2, null, false, emptyMap<String, String>(), revision,
        )
        roots(false, true)
        await { vm.uiState.value.queue.ordered.map { it.id } == b && !vm.uiState.value.isReloading }
        vm.javaClass.getDeclaredMethod("applyLoaded", loadedClass, CurrentImageInfo::class.java)
            .apply { isAccessible = true }.invoke(vm, oldLoaded, old.currentImage)
        assertEquals(b, vm.uiState.value.queue.ordered.map { it.id })
        await { !vm.uiState.value.isReloading }
        assertEquals(b, vm.uiState.value.queue.ordered.map { it.id })
    }

    @Test fun currentOutsideAStaleModelElevatesTheReadToARealCatalogRefresh() {
        val vm = loaded()
        val stale = vm.uiState.value
        roots(false, true)
        await { vm.uiState.value.queue.ordered.map { it.id } == b && !vm.uiState.value.isReloading }
        // 模拟 current 通知先于页面目录提交：只恢复旧展示模型，不改真实 Room 或偏好授权。
        @Suppress("UNCHECKED_CAST")
        val state = vm.javaClass.getDeclaredField("_uiState").apply { isAccessible = true }.get(vm)
            as kotlinx.coroutines.flow.MutableStateFlow<PlaybackUiState>
        state.value = stale
        snapshots.save(initial.copy(currentId = b[0], historyIds = listOf(a[0], b[0]), historyCursor = 1))
        await { vm.uiState.value.queue.ordered.map { it.id } == b && vm.uiState.value.queue.currentId == b[0] &&
            vm.uiState.value.currentImage?.uri == b[0] && !vm.uiState.value.isReloading }
    }

    @Test fun currentOnlyNotificationKeepsTheExistingModelAndBrowseExpansion() {
        val vm = loaded()
        val root = vm.uiState.value.queueRows.filterIsInstance<QueueRow.Level>().first()
        if (!root.expanded) vm.toggleLevel(root.levelKey)
        val ordered = vm.uiState.value.queue.ordered
        val rows = vm.uiState.value.queueRows.map { it.key }
        val serial = vm.javaClass.getDeclaredField("loadSerial").apply { isAccessible = true }.getLong(vm)
        snapshots.save(initial.copy(currentId = a[1], historyIds = a, historyCursor = 1))
        await { vm.uiState.value.currentImage?.uri == a[1] }
        assertSame(ordered, vm.uiState.value.queue.ordered)
        assertEquals(rows, vm.uiState.value.queueRows.map { it.key })
        assertEquals(serial, vm.javaClass.getDeclaredField("loadSerial").apply { isAccessible = true }.getLong(vm))
    }

    @Test fun clearingTheViewModelStopsSourceAndPreferenceObservation() {
        val vm = loaded()
        owner.clear()
        val before = vm.uiState.value
        roots(false, true)
        WallpaperPreferences.create(app).setImageEnabled(a[1], false)
        ScheduleStore(app).saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("r", sourceIds = listOf("b")))))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, vm.uiState.value)
        val job = vm.javaClass.getDeclaredField("catalogObservationJob").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.Job
        assertTrue(job.isCancelled)
    }
}
