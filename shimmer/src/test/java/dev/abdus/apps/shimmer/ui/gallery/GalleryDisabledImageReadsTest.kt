package dev.abdus.apps.shimmer.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDisabledImageReadsTest {
    @Test
    fun theLatestCompletedReadPublishesItsExactSet() {
        val state = GalleryDisabledImageReads()
        assertTrue(state.publish(state.beginRead(), setOf("A", "B")))
        assertEquals(setOf("A", "B"), state.images)
    }

    @Test
    fun anOlderRefreshCannotOverwriteANewerListenerResult() {
        val state = GalleryDisabledImageReads()
        val refresh = state.beginRead()
        val listener = state.beginRead()
        assertTrue(state.publish(listener, setOf("newly-stopped")))
        assertFalse(state.publish(refresh, emptySet()))
        assertEquals(setOf("newly-stopped"), state.images)
    }

    @Test
    fun anOlderListenerCannotOverwriteANewerRefreshResult() {
        val state = GalleryDisabledImageReads()
        val listener = state.beginRead()
        val refresh = state.beginRead()
        assertTrue(state.publish(refresh, emptySet()))
        assertFalse(state.publish(listener, setOf("already-resumed")))
        assertTrue(state.images.isEmpty())
    }

    @Test
    fun anOldReadIsRejectedEvenBeforeTheNewReadReturns() {
        val state = GalleryDisabledImageReads()
        assertTrue(state.publish(state.beginRead(), setOf("committed")))
        val old = state.beginRead()
        val current = state.beginRead()
        assertFalse(state.publish(old, setOf("stale")))
        assertEquals(setOf("committed"), state.images)
        assertTrue(state.publish(current, setOf("current")))
        assertEquals(setOf("current"), state.images)
    }

    @Test
    fun multipleOverlappingReadsOnlyAllowTheLatestOneToPublish() {
        val state = GalleryDisabledImageReads()
        val reads = (1..100).map { state.beginRead() }
        assertTrue(state.publish(reads.last(), setOf("latest")))
        reads.dropLast(1).reversed().forEach { assertFalse(state.publish(it, setOf("old"))) }
        assertEquals(setOf("latest"), state.images)
    }

    @Test
    fun aPublishedSetIsDetachedFromItsMutableInput() {
        val state = GalleryDisabledImageReads()
        val input = mutableSetOf("A", "B")
        assertTrue(state.publish(state.beginRead(), input))
        input.clear()
        assertEquals(setOf("A", "B"), state.images)
    }
}
