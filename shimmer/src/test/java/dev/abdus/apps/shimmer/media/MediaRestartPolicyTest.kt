package dev.abdus.apps.shimmer.media

import org.junit.Assert.*
import org.junit.Test

class MediaRestartPolicyTest {
    @Test fun firstObservationIsOnlyABaseline() {
        assertFalse(MediaRestartPolicy().observe(true, true, true))
        assertFalse(MediaRestartPolicy().observe(false, true, true))
    }
    @Test fun repeatedVisibilityAndBroadcastSamplesCannotRestartTwice() {
        val p = MediaRestartPolicy()
        assertFalse(p.observe(false, true, true))
        assertTrue(p.observe(true, true, true))
        repeat(5) { assertFalse(p.observe(true, true, true)) }
        assertTrue(p.observe(false, true, true))
        repeat(5) { assertFalse(p.observe(false, true, true)) }
    }
    @Test fun lockAndUnlockAreIndependentAndOptionChangesAreNotEvents() {
        val p = MediaRestartPolicy()
        p.observe(false, false, false)
        assertFalse(p.observe(true, false, true))
        assertFalse(p.observe(true, true, true))
        assertTrue(p.observe(false, false, true))
        assertTrue(p.observe(true, true, false))
        assertFalse(p.observe(false, true, false))
    }
}
