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
class PlaybackSourceCursorStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = PlaybackSnapshotStore(context)
    private fun preferences() = context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE)
    private fun state() = PlaybackSnapshot(
        order = PlaybackOrder.MANUAL, queueIds = listOf("a", "b", "hidden"), currentId = "a",
        historyIds = listOf("a", "b", "a"), historyCursor = 0, queueIndex = 0,
        historyTransforms = listOf(ImageTransform(userScale = 2f), null, ImageTransform(userScale = 3f)),
        sourcePositions = mapOf("A" to "a", "B" to "b"), sourceHistoryCursors = mapOf("A" to 0, "B" to 1),
        historySourceIds = listOf("A", "B", "A"),
    )

    @Test fun oldJsonWithoutCursorsLoadsWithEmptyDefault() {
        preferences().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY,
            """{"currentId":"a","historyIds":["a"],"historyCursor":0,"sourcePositions":{"A":"a"}}""").commit()
        val loaded = store().load()!!
        assertEquals("a", loaded.currentId)
        assertEquals(mapOf("A" to "a"), loaded.sourcePositions)
        assertTrue(loaded.sourceHistoryCursors.isEmpty())
        assertTrue(loaded.historySourceIds.isEmpty())
    }
    @Test fun sourceCursorsRoundTripKeepsEarlierDuplicateOccurrence() {
        val subject = store(); subject.save(state())
        assertEquals(state(), PlaybackSnapshotStore(context).load())
        assertEquals(0, subject.load()!!.sourceHistoryCursors["A"])
    }
    @Test fun saveDefensivelyCopiesMutableCursorInput() {
        val cursors = linkedMapOf("A" to 0, "B" to 1)
        val subject = store(); subject.save(state().copy(sourceHistoryCursors = cursors))
        cursors["A"] = 2
        assertEquals(0, subject.load()!!.sourceHistoryCursors["A"])
        assertEquals(0, PlaybackSnapshotStore(context).load()!!.sourceHistoryCursors["A"])
    }
    @Test fun loadedCursorMapExplicitlyRejectsMutation() {
        val subject = store(); subject.save(state())
        @Suppress("UNCHECKED_CAST")
        val cursors = subject.load()!!.sourceHistoryCursors as MutableMap<String, Int>
        assertThrows(UnsupportedOperationException::class.java) { cursors["A"] = 2 }
        assertEquals(0, subject.load()!!.sourceHistoryCursors["A"])
    }
    @Test fun anotherStoreCursorOnlyWriteInvalidatesCas() {
        val first = store(); first.save(state()); val expected = first.load()
        val changed = state().copy(sourceHistoryCursors = mapOf("A" to 2, "B" to 1))
        store().save(changed)
        assertFalse(first.compareAndSave(expected, state()))
        assertEquals(changed, first.load())
    }
    @Test fun directPreferenceCursorChangeInvalidatesWithoutListener() {
        val subject = store(); subject.save(state()); val expected = subject.load()
        val changed = state().copy(sourceHistoryCursors = mapOf("A" to 2))
        preferences().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, Json.encodeToString(changed)).commit()
        assertFalse(subject.compareAndSave(expected, state()))
        assertEquals(changed, subject.load())
    }
    @Test fun forgetSourceRemovesItsCursorAndKeepsOtherSource() {
        val subject = store(); subject.save(state()); subject.forgetSource("A")
        assertEquals(mapOf("B" to 1), subject.load()!!.sourceHistoryCursors)
        assertEquals(mapOf("B" to "b"), subject.load()!!.sourcePositions)
        assertEquals(state().historyIds, subject.load()!!.historyIds)
    }
    @Test fun forgetCursorOnlySourceAlsoWritesRemoval() {
        val subject = store(); subject.save(state().copy(sourcePositions = emptyMap()))
        subject.forgetSource("A")
        assertEquals(mapOf("B" to 1), subject.load()!!.sourceHistoryCursors)
    }
    @Test fun clearHistoryRemapsOnlyTheRetainedCurrentBaseline() {
        val subject = store(); subject.save(state()); val cleared = subject.clearHistory()
        assertEquals(mapOf("A" to 0), cleared.sourceHistoryCursors)
        assertEquals(state().sourcePositions, cleared.sourcePositions)
        assertEquals(state().queueIds, cleared.queueIds)
        assertEquals(listOf("a"), cleared.historyIds)
        assertEquals(state().historyTransforms.first(), cleared.currentEntryTransform("a"))
    }
    @Test fun explicitSortingResetAlsoClearsOtherHistoryCursors() {
        val subject = store(); subject.save(state()); val cleared = subject.clearHistory(true)
        assertEquals(PlaybackOrder.NATURAL_ASC, cleared.order)
        assertTrue(cleared.queueIds.isEmpty())
        assertEquals(mapOf("A" to 0), cleared.sourceHistoryCursors)
        assertEquals(state().sourcePositions, cleared.sourcePositions)
    }
    @Test fun absentCurrentClearCannotRetainStaleCursors() {
        val cleared = clearedPlaybackHistory(state().copy(currentId = null, historyIds = emptyList(), historyCursor = -1))
        assertTrue(cleared.sourceHistoryCursors.isEmpty())
        assertTrue(cleared.historyIds.isEmpty())
        assertEquals(state().sourcePositions, cleared.sourcePositions)
    }
    @Test fun naturalQueuePersistenceStillKeepsCursorsAndCasEqual() {
        val subject = store(); val natural = state().copy(order = PlaybackOrder.NATURAL_ASC)
        subject.save(natural)
        assertTrue(subject.load()!!.queueIds.isEmpty())
        assertEquals(natural.sourceHistoryCursors, subject.load()!!.sourceHistoryCursors)
        assertTrue(subject.compareAndSave(natural, natural))
    }
    @Test fun historyOwnersAreCopiedAndFrozenOnSaveAndLoad() {
        val owners = mutableListOf("A", "B", "A")
        val subject = store(); subject.save(state().copy(historySourceIds = owners))
        owners[0] = "OTHER"
        assertEquals(listOf("A", "B", "A"), subject.load()!!.historySourceIds)
        val loaded = subject.load()!!.historySourceIds as MutableList<String>
        assertThrows(UnsupportedOperationException::class.java) { loaded[0] = "OTHER" }
        assertEquals(listOf("A", "B", "A"), PlaybackSnapshotStore(context).load()!!.historySourceIds)
    }
    @Test fun historyOwnerOnlyChangeInvalidatesRealCas() {
        val subject = store(); subject.save(state()); val expected = subject.load()
        val changed = state().copy(historySourceIds = listOf("B", "B", "A"))
        store().save(changed)
        assertFalse(subject.compareAndSave(expected, state()))
        assertEquals(changed, subject.load())
    }
    @Test fun clearHistoryKeepsOnlyTheActualBaselineOwner() {
        assertEquals(listOf("A"), clearedPlaybackHistory(state()).historySourceIds)
        assertEquals(listOf("B"), clearedPlaybackHistory(state().copy(currentId = "b", historyCursor = 1)).historySourceIds)
    }
    @Test fun clearCannotAssignOwnerToAnUnmatchedCurrent() {
        assertTrue(clearedPlaybackHistory(state().copy(currentId = "other")).historySourceIds.isEmpty())
    }
    @Test fun cursorAndOwnerFieldsPersistTogetherAcrossNewStore() {
        val subject = store(); subject.save(state())
        val restored = PlaybackSnapshotStore(context).load()!!
        assertEquals(state().historySourceIds, restored.historySourceIds)
        assertEquals(state().sourceHistoryCursors, restored.sourceHistoryCursors)
        assertEquals(state().historyTransforms, restored.historyTransforms)
    }
}
