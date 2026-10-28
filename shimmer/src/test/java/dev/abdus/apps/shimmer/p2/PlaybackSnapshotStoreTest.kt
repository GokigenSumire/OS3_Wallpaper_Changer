package dev.abdus.apps.shimmer.p2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSnapshotStoreTest {
    @Test
    fun committedSnapshotRoundTripsWithoutStoringTheDerivedRandomQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        val snapshot = PlaybackSnapshot(
            order = PlaybackOrder.RANDOM,
            queueIds = listOf("a", "b"),
            queueIndex = 1,
            historyIds = listOf("a", "b"),
            historyCursor = 1,
            currentId = "b",
            randomRound = 2,
            previousRoundLastId = "a",
            queueFingerprint = 77,
        )

        store.save(snapshot)

        // A random round is rebuilt from randomRound/previousRoundLastId/queueFingerprint, so the
        // ID list is not written. A large catalog would otherwise encode megabytes on every frame.
        assertEquals(snapshot.copy(queueIds = emptyList()), store.load())
        store.clear()
    }

    @Test
    fun committedSnapshotKeepsTheManualOrderBecauseNothingElseCanDeriveIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        val snapshot = PlaybackSnapshot(
            order = PlaybackOrder.MANUAL,
            queueIds = listOf("c", "a", "b"),
            queueIndex = 0,
            historyIds = listOf("c"),
            historyCursor = 0,
            currentId = "c",
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
        store.clear()
    }

    @Test
    fun malformedSnapshotIsDiscardedWithoutBreakingPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("p2_playback_state", Context.MODE_PRIVATE)
        preferences.edit().putString("committed_snapshot", "not-json").commit()

        assertNull(PlaybackSnapshotStore(context).load())
        assertEquals("not-json", preferences.getString("committed_snapshot", null))
    }

    @Test
    fun lateEngineCommitCannotReplaceNewerHistoryOrActivityOrder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PlaybackSnapshotStore(context)
        val activity = PlaybackSnapshotStore(context)
        engine.clear()
        val expected = PlaybackSnapshot(historyIds = listOf("a"), historyCursor = 0, currentId = "a")
        engine.save(expected)
        activity.update { requireNotNull(it).copy(order = PlaybackOrder.MANUAL, queueIds = listOf("c", "a", "b")) }
        val newer = activity.load()
        val stale = expected.copy(historyIds = listOf("a", "b"), historyCursor = 1, currentId = "b")
        org.junit.Assert.assertFalse(engine.compareAndSave(expected, stale))
        assertEquals(newer, engine.load())
        assertTrue(engine.compareAndSave(newer, stale.copy(order = PlaybackOrder.MANUAL)))
    }

    @Test
    fun listenerReceivesSnapshotSaveAndClearEvents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        val changedKeys = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            changedKeys += key
        }
        store.registerListener(listener)

        store.save(PlaybackSnapshot())
        store.clear()
        store.unregisterListener(listener)

        assertTrue(changedKeys.contains(PlaybackSnapshotStore.SNAPSHOT_KEY))
    }

    @Test
    fun snapshotSavedBeforeP4DecodesWithoutResumePoints() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("p2_playback_state", Context.MODE_PRIVATE)
        preferences.edit().putString(
            "committed_snapshot",
            """{"order":"NATURAL_ASC","historyIds":["a"],"historyCursor":0,"currentId":"a"}""",
        ).commit()

        val loaded = PlaybackSnapshotStore(context).load()

        assertEquals("a", loaded?.currentId)
        assertEquals(emptyMap<String, String>(), loaded?.sourcePositions)
        assertEquals(emptyList<String>(), loaded?.resumeSourceIds)
        PlaybackSnapshotStore(context).clear()
    }

    @Test
    fun deletingASourceForgetsItsResumePointAndKeepsHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        val snapshot = PlaybackSnapshot(
            historyIds = listOf("a3", "b1"),
            historyCursor = 1,
            currentId = "b1",
            sourcePositions = mapOf("A" to "a3", "B" to "b1"),
            resumeSourceIds = listOf("A"),
        )
        store.save(snapshot)
        assertEquals(snapshot, store.load())

        store.forgetSource("A")

        assertEquals(snapshot.copy(sourcePositions = mapOf("B" to "b1"), resumeSourceIds = emptyList()), store.load())
        store.clear()
        store.forgetSource("A")
        assertNull(store.load())
    }

    @Test
    fun clearHistoryKeepsManualSlotsCurrentPlacementAndIndependentImageSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        val placement = dev.abdus.apps.shimmer.p3.ImageTransform(userScale = 2f, offsetX = 0.2f)
        store.commitImageTransform("other", placement)
        val snapshot = PlaybackSnapshot(
            order = PlaybackOrder.MANUAL,
            queueIds = listOf("a", "disabled", "c", "b"),
            queueIndex = 2,
            historyIds = listOf("a", "c", "b"),
            historyCursor = 1,
            currentId = "c",
            historyTransforms = listOf(null, placement),
            sourcePositions = mapOf("stopped" to "disabled"),
            resumeSourceIds = listOf("stopped"),
        )
        store.save(snapshot)

        val cleared = store.clearHistory()

        assertEquals(clearedPlaybackHistory(snapshot), cleared)
        assertEquals(cleared, store.load())
        assertEquals(snapshot.queueIds, store.load()!!.queueIds)
        assertEquals(placement, store.loadImageTransform("other"))
        assertEquals(placement, store.load()!!.currentEntryTransform("c"))
        store.clear()
        store.commitImageTransform("other", null)
    }

    @Test
    fun twoStoreInstancesClearTheLatestCommittedHistoryRatherThanAnOlderPageCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PlaybackSnapshotStore(context)
        val page = PlaybackSnapshotStore(context)
        engine.clear()
        engine.save(PlaybackSnapshot(historyIds = listOf("a"), historyCursor = 0, currentId = "a"))
        val latest = PlaybackSnapshot(
            order = PlaybackOrder.MANUAL, queueIds = listOf("b", "a", "c"),
            historyIds = listOf("a", "c"), historyCursor = 1, currentId = "c",
        )
        engine.save(latest)

        val cleared = page.clearHistory()

        assertEquals("c", cleared.currentId)
        assertEquals(listOf("c"), cleared.historyIds)
        assertEquals(latest.queueIds, cleared.queueIds)
        assertEquals(cleared, engine.load())
        engine.clear()
    }

    @Test
    fun staleDecodedImageCannotCommitAfterClearingHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        val previous = PlaybackSnapshot(
            order = PlaybackOrder.MANUAL, queueIds = listOf("a", "b", "c"),
            historyIds = listOf("a", "b"), historyCursor = 1, currentId = "b",
        )
        store.save(previous)
        val staleNext = previous.copy(historyIds = listOf("a", "b", "c"), historyCursor = 2, currentId = "c")
        val cleared = store.clearHistory()

        assertFalse(store.compareAndSave(previous, staleNext))
        assertEquals(cleared, store.load())
        assertEquals("b", store.load()!!.currentId)
        store.clear()
    }

    @Test
    fun repeatedBaselineClearStillRejectsAnAlreadyDecodedNextImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        store.save(PlaybackSnapshot(
            order = PlaybackOrder.MANUAL, queueIds = listOf("a", "b"),
            historyIds = listOf("a"), historyCursor = 0, currentId = "a",
        ))
        val first = store.clearHistory()
        val stale = first.copy(historyIds = listOf("a", "b"), historyCursor = 1, currentId = "b")

        val second = store.clearHistory()

        assertEquals(first.historyResetSerial + 1, second.historyResetSerial)
        assertFalse(store.compareAndSave(first, stale))
        assertEquals(second, store.load())
        store.clear()
    }

    @Test
    fun clearWithExplicitResetWritesAscendingOrderButKeepsTheCurrentBaseline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        store.save(PlaybackSnapshot(
            order = PlaybackOrder.MANUAL, queueIds = listOf("c", "disabled", "b", "a"),
            historyIds = listOf("a", "c"), historyCursor = 1, currentId = "c",
        ))

        store.clearHistory(resetManualOrder = true)

        val loaded = store.load()!!
        assertEquals(PlaybackOrder.NATURAL_ASC, loaded.order)
        assertTrue(loaded.queueIds.isEmpty())
        assertEquals(listOf("c"), loaded.historyIds)
        assertEquals("c", loaded.currentId)
        store.clear()
    }

    @Test
    fun oldSnapshotWithoutResetSerialLoadsWithZeroAndCanBeCleared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE)
        preferences.edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY,
            """{"order":"MANUAL","queueIds":["c","a"],"historyIds":["c"],"historyCursor":0,"currentId":"c"}"""
        ).commit()
        val store = PlaybackSnapshotStore(context)

        assertEquals(0L, store.load()!!.historyResetSerial)
        store.clearHistory()

        assertEquals(1L, store.load()!!.historyResetSerial)
        assertEquals(listOf("c", "a"), store.load()!!.queueIds)
        store.clear()
    }

    @Test
    fun clearHistoryPublishesASnapshotChangeEvenWhenAlreadyAtTheBaseline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PlaybackSnapshotStore(context)
        store.clear()
        store.save(PlaybackSnapshot(historyIds = listOf("a"), historyCursor = 0, currentId = "a"))
        val changes = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changes += key }
        store.registerListener(listener)

        store.clearHistory()
        store.clearHistory()
        store.unregisterListener(listener)

        assertEquals(2, changes.count { it == PlaybackSnapshotStore.SNAPSHOT_KEY })
        assertEquals(2L, store.load()!!.historyResetSerial)
        store.clear()
    }
}
