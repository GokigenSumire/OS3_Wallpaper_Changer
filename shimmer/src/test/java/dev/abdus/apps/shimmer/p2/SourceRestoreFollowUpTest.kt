package dev.abdus.apps.shimmer.p2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceRestoreFollowUpTest {
    private fun follow(
        direction: PlaybackDirection = PlaybackDirection.NEXT,
        restored: Boolean = true,
        restoreOnly: Boolean = false,
        expected: Long = 12,
        actual: Long = 12,
        active: Boolean = true,
        visible: Boolean = true,
        ready: Boolean = true,
    ) = sourceRestoreFollowUp(direction, restored, restoreOnly, expected, actual, active, visible, ready)

    @Test fun nextGestureIsNotConsumedByRestore() { assertEquals(PlaybackDirection.NEXT, follow()) }
    @Test fun previousGestureKeepsItsDirection() { assertEquals(PlaybackDirection.PREVIOUS, follow(PlaybackDirection.PREVIOUS)) }
    @Test fun automaticRestoreDoesNotAdvance() { assertNull(follow(restoreOnly = true)) }
    @Test fun normalCommitDoesNotRepeat() { assertNull(follow(restored = false)) }
    @Test fun newerGenerationDropsOldGesture() { assertNull(follow(actual = 13)) }
    @Test fun earlierGenerationAlsoDoesNotMatch() { assertNull(follow(actual = 11)) }
    @Test fun cancellationDropsGesture() { assertNull(follow(active = false)) }
    @Test fun hiddenEngineDoesNotReplay() { assertNull(follow(visible = false)) }
    @Test fun lostSurfaceDoesNotReplay() { assertNull(follow(ready = false)) }
    @Test fun repeatedAutomaticRestoreNeverCreatesNext() {
        repeat(3) { assertNull(follow(restoreOnly = true)) }
    }
}
