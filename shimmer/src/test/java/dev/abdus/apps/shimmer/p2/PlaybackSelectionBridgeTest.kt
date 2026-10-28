package dev.abdus.apps.shimmer.p2

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionBridgeTest {
    private class Receiver(val response: PlaybackSelectionBridge.Result) : PlaybackSelectionBridge.Target {
        val values = mutableListOf<String>()
        override fun select(itemId: String): PlaybackSelectionBridge.Result { values.add(itemId); return response }
    }
    @After fun clear() { listOf("a", "b", "c").forEach(PlaybackSelectionBridge::unregister) }
    @Test fun absentFormalEngineDoesNotPretendPlaybackSuccess() {
        assertEquals(PlaybackSelectionBridge.Result.NO_ENGINE, PlaybackSelectionBridge.submit("image"))
    }
    @Test fun blankIdentityNeverReachesAnyEngine() {
        val receiver = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        PlaybackSelectionBridge.register("a", receiver)
        assertEquals(PlaybackSelectionBridge.Result.NOOP, PlaybackSelectionBridge.submit(" "))
        assertTrue(receiver.values.isEmpty())
    }
    @Test fun newestEngineIsFallbackBeforeAnyVisibility() {
        val a = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        val b = Receiver(PlaybackSelectionBridge.Result.QUEUED)
        PlaybackSelectionBridge.register("a", a); PlaybackSelectionBridge.register("b", b)
        assertEquals(PlaybackSelectionBridge.Result.QUEUED, PlaybackSelectionBridge.submit("one"))
        assertTrue(a.values.isEmpty()); assertEquals(listOf("one"), b.values)
    }
    @Test fun mostRecentlyVisibleEngineReceivesExactlyOneRequest() {
        val a = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        val b = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        PlaybackSelectionBridge.register("a", a); PlaybackSelectionBridge.register("b", b)
        PlaybackSelectionBridge.markVisible("a")
        PlaybackSelectionBridge.submit("one")
        assertEquals(listOf("one"), a.values); assertTrue(b.values.isEmpty())
    }
    @Test fun visibilityCanChangeExecutorWithoutDuplicatingRequest() {
        val a = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        val b = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        PlaybackSelectionBridge.register("a", a); PlaybackSelectionBridge.register("b", b)
        PlaybackSelectionBridge.markVisible("a"); PlaybackSelectionBridge.markVisible("b")
        PlaybackSelectionBridge.submit("two")
        assertTrue(a.values.isEmpty()); assertEquals(listOf("two"), b.values)
    }
    @Test fun unregisterVisibleEngineFallsBackToRemainingFormalTarget() {
        val a = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        val b = Receiver(PlaybackSelectionBridge.Result.ACCEPTED)
        PlaybackSelectionBridge.register("a", a); PlaybackSelectionBridge.register("b", b)
        PlaybackSelectionBridge.markVisible("b"); PlaybackSelectionBridge.unregister("b")
        PlaybackSelectionBridge.submit("one")
        assertEquals(listOf("one"), a.values); assertTrue(b.values.isEmpty())
    }
    @Test fun unknownVisibilityDoesNotSelectUnknownTarget() {
        val a = Receiver(PlaybackSelectionBridge.Result.BUSY_EDIT)
        PlaybackSelectionBridge.register("a", a); PlaybackSelectionBridge.markVisible("unknown")
        assertEquals(PlaybackSelectionBridge.Result.BUSY_EDIT, PlaybackSelectionBridge.submit("one"))
    }
    @Test fun acceptedIntentDoesNotCreateAnyPersistentSnapshot() {
        val a = Receiver(PlaybackSelectionBridge.Result.QUEUED)
        PlaybackSelectionBridge.register("a", a)
        assertEquals(PlaybackSelectionBridge.Result.QUEUED, PlaybackSelectionBridge.submit("one"))
        assertEquals(PlaybackSelectionBridge.Result.QUEUED, PlaybackSelectionBridge.submit("two"))
        assertEquals(listOf("one", "two"), a.values)
    }
}
