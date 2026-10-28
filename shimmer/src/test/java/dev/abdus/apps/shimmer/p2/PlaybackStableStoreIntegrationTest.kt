package dev.abdus.apps.shimmer.p2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p3.ImageTransform
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real prefs/Coordinator/Resolver/CAS. String payload is not a bitmap, permission or GPU test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStableStoreIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PlaybackSnapshotStore(context)
    private val another = PlaybackSnapshotStore(context)
    @Before fun initialize() { store.clear() }
    @After fun restoreIsolation() { store.clear() }
    private fun catalog(size: Int) = CertifiedPlaybackItems((0 until size).map {
        PlaybackItem("content://test/$it", "$it.jpg", "dir/$it.jpg", "root", 1, "DIRECTORY_TREE", "content://root")
    })
    private suspend fun advance(coordinator: PlaybackCoordinator, direction: PlaybackDirection): Pair<PlaybackSnapshot, StoreWriteWork> {
        val expected = store.load()
        val loaded = PlaybackImageResolver<String>(coordinator).resolveRequest(direction) { _, item -> item.id }
        assertNotNull(loaded)
        assertTrue(coordinator.commit(loaded!!.request.version))
        val result = store.compareAndSaveWithWork(expected, coordinator.snapshot())
        assertTrue(result.accepted)
        assertEquals(coordinator.snapshot().forPersistence(), store.load())
        return coordinator.snapshot() to result.work
    }
    @Test fun realStoreSmallAndLargeFourModesHaveZeroWarmCatalogRestoreAndManualConfigurationEncoding() = runBlocking {
        for (size in listOf(270, 11890, 26000)) for (order in PlaybackOrder.entries) {
            store.clear()
            val items = catalog(size)
            val coordinator = PlaybackCoordinator(42)
            val baseline = PlaybackCoordinator(42)
            val cold = coordinator.setItemsAndRestore(items, PlaybackSnapshot(order = order))
            baseline.setItemsAndRestore(items.toList(), PlaybackSnapshot(order = order))
            assertEquals(size.toLong(), cold.catalogItemsVisited)
            assertEquals(1, cold.fullRestoreCount)
            store.save(coordinator.snapshot())
            repeat(8) {
                val latest = store.load()!!
                val prepared = coordinator.setItemsAndRestore(items, latest)
                assertEquals(CoordinatorPreparationWork(), prepared)
                val reference = baseline.requestNext()
                assertTrue(baseline.commit(reference.version))
                val (actual, work) = advance(coordinator, PlaybackDirection.NEXT)
                assertEquals(baseline.snapshot(), actual)
                assertEquals(0, work.manualIdsEncoded)
                assertEquals(0, work.manualConfigurationWrites)
                assertEquals(0, work.manualIdsCopied)
                if (order == PlaybackOrder.MANUAL) assertSame(actual.queueIds, store.load()!!.queueIds)
            }
            repeat(5) {
                assertEquals(CoordinatorPreparationWork(), coordinator.setItemsAndRestore(items, another.load()!!))
                val previous = baseline.requestPrevious()
                assertTrue(baseline.commit(previous.version))
                val (actual, work) = advance(coordinator, PlaybackDirection.PREVIOUS)
                assertEquals(baseline.snapshot(), actual)
                assertEquals(0, work.manualIdsEncoded)
                assertEquals(0, work.manualConfigurationWrites)
            }
        }
    }
    @Test fun anotherStoreModeAndManualQueueUpdatesForceFullRestoreBeforeResolverRuns() = runBlocking {
        val items = catalog(18)
        val coordinator = PlaybackCoordinator()
        coordinator.setItemsAndRestore(items, PlaybackSnapshot(order = PlaybackOrder.MANUAL))
        store.save(coordinator.snapshot())
        advance(coordinator, PlaybackDirection.NEXT)
        another.update { latest -> latest!!.copy(queueIds = latest.queueIds.reversed(), forwardHistoryInvalidated = true) }
        val changed = store.load()!!
        assertEquals(1, coordinator.setItemsAndRestore(items, changed).fullRestoreCount)
        assertEquals(changed.queueIds, coordinator.snapshot().queueIds)
        advance(coordinator, PlaybackDirection.NEXT)
        another.update { latest -> latest!!.copy(order = PlaybackOrder.NATURAL_DESC, forwardHistoryInvalidated = true) }
        assertEquals(1, coordinator.setItemsAndRestore(items, store.load()!!).fullRestoreCount)
        assertEquals(PlaybackOrder.NATURAL_DESC, coordinator.snapshot().order)
    }
    @Test fun realClearHistoryPreservingManualOrderChangesResetBoundaryAndCannotUseWarmRestoreSkip() = runBlocking {
        val items = catalog(15)
        val coordinator = PlaybackCoordinator()
        coordinator.setItemsAndRestore(items, PlaybackSnapshot(order = PlaybackOrder.MANUAL))
        store.save(coordinator.snapshot())
        repeat(4) { coordinator.setItemsAndRestore(items, store.load()!!); advance(coordinator, PlaybackDirection.NEXT) }
        val order = store.load()!!.queueIds
        val before = store.load()!!
        val cleared = another.clearHistory()
        assertNotEquals(before.historyResetSerial, cleared.historyResetSerial)
        assertEquals(order, cleared.queueIds)
        assertEquals(1, coordinator.setItemsAndRestore(items, store.load()!!).fullRestoreCount)
        assertEquals(cleared.currentId, coordinator.snapshot().currentId)
        assertEquals(1, coordinator.snapshot().historyIds.size)
        assertEquals(CoordinatorPreparationWork(), coordinator.setItemsAndRestore(items, store.load()!!))
    }
    @Test fun actualTransformCommitClearsInheritedSlotsAndIsSeenBeforeTheNextStep() = runBlocking {
        val items = catalog(12)
        val coordinator = PlaybackCoordinator()
        coordinator.setItems(items)
        val request = coordinator.requestNext()
        assertTrue(coordinator.commit(request.version, ImageTransform(userScale = 2f)))
        store.save(coordinator.snapshot())
        assertTrue(store.load()!!.historyTransforms.isNotEmpty())
        val id = coordinator.snapshot().currentId!!
        another.commitImageTransform(id, ImageTransform(mirrorHorizontal = true))
        assertTrue(store.load()!!.historyTransforms.isEmpty())
        assertEquals(1, coordinator.setItemsAndRestore(items, store.load()!!).fullRestoreCount)
        assertEquals(ImageTransform(mirrorHorizontal = true), store.loadImageTransform(id))
        assertTrue(coordinator.snapshot().historyTransforms.isEmpty())
    }
    @Test fun staleCandidateCannotOverwriteAnotherStoreSortingOrResetBoundary() = runBlocking {
        val items = catalog(30)
        val coordinator = PlaybackCoordinator()
        coordinator.setItemsAndRestore(items, PlaybackSnapshot(order = PlaybackOrder.MANUAL))
        store.save(coordinator.snapshot())
        val expected = store.load()!!
        val loaded = PlaybackImageResolver<String>(coordinator).resolveRequest(PlaybackDirection.NEXT) { _, item -> item.id }!!
        another.clearHistory()
        val external = store.load()
        assertTrue(coordinator.commit(loaded.request.version))
        val rejected = store.compareAndSaveWithWork(expected, coordinator.snapshot())
        assertFalse(rejected.accepted)
        assertEquals(StoreWriteWork(), rejected.work)
        assertEquals(external, store.load())
        assertEquals(1, coordinator.setItemsAndRestore(items, store.load()!!).fullRestoreCount)
    }
    @Test fun coldManualPreparationAndActualFirstSaveKeepAllHiddenSlotsWithoutEncodingWarmQueue() = runBlocking {
        val all = catalog(14)
        val hidden = all.map { it.id }.reversed()
        val coordinator = PlaybackCoordinator()
        val visible = CertifiedPlaybackItems(all.filterIndexed { index, _ -> index % 2 == 0 })
        coordinator.setItemsAndRestore(visible, PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = hidden))
        store.save(coordinator.snapshot())
        assertEquals(hidden, store.load()!!.queueIds)
        val current = store.load()!!
        assertEquals(CoordinatorPreparationWork(), coordinator.setItemsAndRestore(visible, current))
        val (actual, work) = advance(coordinator, PlaybackDirection.NEXT)
        assertEquals(hidden, actual.queueIds)
        assertEquals(0, work.manualIdsEncoded)
        assertEquals(0, work.manualConfigurationWrites)
        assertEquals(1, coordinator.setItemsAndRestore(all, store.load()!!).fullRestoreCount)
        assertEquals(hidden, coordinator.snapshot().queueIds)
    }
}
