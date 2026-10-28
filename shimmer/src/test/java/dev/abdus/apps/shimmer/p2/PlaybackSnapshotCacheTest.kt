package dev.abdus.apps.shimmer.p2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p3.ImageTransform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSnapshotCacheTest {
    private fun store() = PlaybackSnapshotStore(ApplicationProvider.getApplicationContext<Context>())
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE)
    private fun state(id: String = "a") = PlaybackSnapshot(
        order = PlaybackOrder.MANUAL, queueIds = listOf("a", "stopped", "b"),
        historyIds = listOf(id), historyCursor = 0, currentId = id,
    )

    @Test fun identicalLiveEncodedValueReusesDecodedObject() {
        val subject = store()
        subject.clear()
        preferences().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, Json.encodeToString(state())).commit()
        val first = subject.load()!!
        repeat(20) { assertSame(first, subject.load()) }
        assertEquals(state(), first)
    }

    @Test fun directlyWrittenValueInvalidatesWithoutListenerDelivery() {
        val subject = store()
        subject.save(state())
        val first = subject.load()!!
        preferences().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, Json.encodeToString(state("b"))).commit()
        val changed = subject.load()!!
        assertNotSame(first, changed)
        assertEquals("b", changed.currentId)
        assertSame(changed, subject.load())
    }

    @Test fun anotherStoreWriteCannotBeHiddenByLocalCacheOrCas() {
        val engine = store()
        val page = store()
        engine.save(state())
        val expected = engine.load()!!
        page.save(state("b"))
        assertFalse(engine.compareAndSave(expected, state("c")))
        assertEquals(state("b"), engine.load())
    }

    @Test fun removedOrCorruptLiveValueNeverFallsBackToCachedSuccess() {
        val subject = store()
        subject.save(state())
        assertNotNull(subject.load())
        preferences().edit().remove(PlaybackSnapshotStore.SNAPSHOT_KEY).commit()
        assertNull(subject.load())
        subject.save(state())
        preferences().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, "broken-json").commit()
        assertNull(subject.load())
        assertEquals("broken-json", preferences().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
    }

    @Test fun mutableSaveInputsDoNotAliasCachedSnapshotOrStoredValue() {
        val queue = mutableListOf("a", "disabled", "b")
        val history = mutableListOf("a")
        val transforms = mutableListOf<ImageTransform?>(ImageTransform(userScale = 2f))
        val positions = linkedMapOf("source" to "a")
        val resume = mutableListOf("source")
        val subject = store()
        subject.save(state().copy(queueIds = queue, historyIds = history, historyTransforms = transforms,
            sourcePositions = positions, resumeSourceIds = resume))
        val saved = subject.load()!!
        queue.clear(); history.clear(); transforms.clear(); positions.clear(); resume.clear()
        assertEquals(listOf("a", "disabled", "b"), saved.queueIds)
        assertEquals(listOf("a"), saved.historyIds)
        assertEquals(2f, saved.historyTransforms.single()!!.userScale)
        assertEquals(mapOf("source" to "a"), saved.sourcePositions)
        assertEquals(listOf("source"), saved.resumeSourceIds)
        assertEquals(saved, store().load())
    }

    @Test fun loadedCollectionsExplicitlyRejectMutableCasts() {
        val subject = store()
        subject.save(state().copy(historyTransforms = listOf(ImageTransform()),
            sourcePositions = mapOf("source" to "a"), resumeSourceIds = listOf("source")))
        val saved = subject.load()!!
        assertMutationRejected { (saved.queueIds as MutableList<*>).clear() }
        assertMutationRejected { (saved.historyIds as MutableList<*>).clear() }
        assertMutationRejected { (saved.historyTransforms as MutableList<*>).clear() }
        assertMutationRejected { (saved.sourcePositions as MutableMap<*, *>).clear() }
        assertMutationRejected { (saved.resumeSourceIds as MutableList<*>).clear() }
        assertEquals(saved, store().load())
    }

    @Test fun transformUpdateAndHistoryResetInvalidateExactContentCache() {
        val subject = store()
        subject.save(state().copy(historyTransforms = listOf(ImageTransform(userScale = 2f))))
        val old = subject.load()!!
        subject.commitImageTransform("a", ImageTransform(userScale = 3f))
        assertTrue(subject.load()!!.historyTransforms.isEmpty())
        assertEquals(3f, subject.loadImageTransform("a")!!.userScale)
        val beforeClear = subject.load()!!
        val cleared = subject.clearHistory()
        assertEquals(beforeClear.historyResetSerial + 1, cleared.historyResetSerial)
        assertFalse(subject.compareAndSave(old, old))
        assertEquals(cleared, subject.load())
    }

    @Test fun largeManualQueueKeepsDisabledPositionsWithoutRepeatedDecode() {
        val ids = (1..11_890).map { "content://test/very-long-image-$it" }
        val subject = store()
        subject.save(state(ids[4]).copy(queueIds = ids))
        val saved = subject.load()!!
        repeat(12) { assertSame(saved, subject.load()); assertEquals(ids, subject.load()!!.queueIds) }
        val cleared = subject.clearHistory()
        assertEquals(ids, cleared.queueIds)
        assertEquals(ids[4], cleared.currentId)
        assertEquals(listOf(ids[4]), cleared.historyIds)
    }

    @Test fun failedCasDoesNotPoisonCacheAndSuccessfulCasPublishesNewValue() {
        val subject = store()
        subject.save(state())
        val first = subject.load()!!
        assertFalse(subject.compareAndSave(state("other"), state("wrong")))
        assertSame(first, subject.load())
        assertTrue(subject.compareAndSave(first, state("b")))
        assertEquals(state("b"), subject.load())
    }

    private fun assertMutationRejected(block: () -> Unit) {
        try { block(); fail("缓存集合不得允许外部可变别名修改") }
        catch (_: UnsupportedOperationException) { }
    }
}
