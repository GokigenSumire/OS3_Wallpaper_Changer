package dev.abdus.apps.shimmer.p2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackOrderMarkerStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun scalarMarkerRoundTripsAcrossStoreInstances() {
        val snapshot = PlaybackSnapshot(historyIds = listOf("a"), historyCursor = 0, currentId = "a", forwardHistoryInvalidated = true)
        PlaybackSnapshotStore(context).save(snapshot)
        assertEquals(snapshot, PlaybackSnapshotStore(context).load())
    }
    @Test fun markerOnlyUpdateRejectsPreviouslyDecodedCompareAndSave() {
        val page = PlaybackSnapshotStore(context)
        val engine = PlaybackSnapshotStore(context)
        val before = PlaybackSnapshot(historyIds = listOf("a"), historyCursor = 0, currentId = "a")
        engine.save(before)
        page.save(before.copy(forwardHistoryInvalidated = true))
        assertFalse(engine.compareAndSave(before, before.copy(currentId = "b")))
        assertTrue(engine.load()!!.forwardHistoryInvalidated)
    }
    @Test fun oldPersistedJsonLoadsWithFalseWithoutModifyingItsRawBytes() {
        val raw = "{\"historyIds\":[\"a\"],\"historyCursor\":0,\"currentId\":\"a\"}"
        val prefs = context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, raw).commit()
        assertFalse(PlaybackSnapshotStore(context).load()!!.forwardHistoryInvalidated)
        assertEquals(raw, prefs.getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
    }
    @Test fun sourceForgetKeepsTheModeBoundaryAndUnrelatedSavedPoints() {
        val store = PlaybackSnapshotStore(context)
        store.save(PlaybackSnapshot(sourcePositions = mapOf("A" to "a", "B" to "b"), forwardHistoryInvalidated = true))
        store.forgetSource("A")
        assertEquals(mapOf("B" to "b"), store.load()!!.sourcePositions)
        assertTrue(store.load()!!.forwardHistoryInvalidated)
    }
}
