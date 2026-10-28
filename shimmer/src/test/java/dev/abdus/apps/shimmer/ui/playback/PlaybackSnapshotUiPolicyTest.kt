package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotUiPolicyTest {
    private val state = PlaybackSnapshot(historyIds = listOf("a"), historyCursor = 0, currentId = "a")
    @Test fun nullAndDefaultNaturalUseSameQueueConfiguration() { assertTrue(samePlaybackQueueConfiguration(null, PlaybackSnapshot())) }
    @Test fun currentHistoryAndSourcePointsDoNotRequestWholeQueueReload() {
        assertTrue(samePlaybackQueueConfiguration(state, state.copy(currentId = "b", historyIds = listOf("a", "b"), historyCursor = 1,
            sourcePositions = mapOf("s" to "b"), sourceHistoryCursors = mapOf("s" to 1), historySourceIds = listOf("s", "s"))))
    }
    @Test fun explicitModeChangeRequestsWholeQueueReload() { assertFalse(samePlaybackQueueConfiguration(state, state.copy(order = PlaybackOrder.NATURAL_DESC))) }
    @Test fun manualReorderRequiresReloadButCurrentOnlyDoesNot() {
        val manual = state.copy(order = PlaybackOrder.MANUAL, queueIds = listOf("a", "hidden", "b"))
        assertTrue(samePlaybackQueueConfiguration(manual, manual.copy(currentId = "b")))
        assertFalse(samePlaybackQueueConfiguration(manual, manual.copy(queueIds = listOf("b", "hidden", "a"))))
    }
    @Test fun manualHiddenPositionIsIncludedInExactConfiguration() {
        val manual = state.copy(order = PlaybackOrder.MANUAL, queueIds = listOf("a", "hidden", "b"))
        assertFalse(samePlaybackQueueConfiguration(manual, manual.copy(queueIds = listOf("hidden", "a", "b"))))
    }
    @Test fun randomHistoryAdvanceWithinOneRoundDoesNotReloadQueue() {
        val random = state.copy(order = PlaybackOrder.RANDOM, randomRound = 1, queueFingerprint = 7, previousRoundLastId = "z")
        assertTrue(samePlaybackQueueConfiguration(random, random.copy(currentId = "b", historyCursor = 1)))
    }
    @Test fun randomRoundFingerprintAndPreviousTailChangesRequireReload() {
        val random = state.copy(order = PlaybackOrder.RANDOM, randomRound = 1, queueFingerprint = 7, previousRoundLastId = "z")
        assertFalse(samePlaybackQueueConfiguration(random, random.copy(randomRound = 2)))
        assertFalse(samePlaybackQueueConfiguration(random, random.copy(queueFingerprint = 8)))
        assertFalse(samePlaybackQueueConfiguration(random, random.copy(previousRoundLastId = "y")))
    }
    @Test fun markerAndClearHistoryBoundaryDoNotChangeTheQueueShape() {
        assertTrue(samePlaybackQueueConfiguration(state, state.copy(forwardHistoryInvalidated = true, historyResetSerial = 1)))
    }
    @Test fun latestExactSnapshotAllowsAReadResultOrError() { assertTrue(currentReadMayPublish(1, 1, state, state.copy())) }
    @Test fun olderReadSerialCannotPublishEvenForAnEqualSnapshot() { assertFalse(currentReadMayPublish(1, 2, state, state)) }
    @Test fun changedSnapshotCannotPublishEvenBeforeItsListenerUpdatesTheSerial() {
        assertFalse(currentReadMayPublish(1, 1, state, state.copy(currentId = "b")))
        assertFalse(currentReadMayPublish(1, 1, state, state.copy(order = PlaybackOrder.NATURAL_DESC)))
        assertFalse(currentReadMayPublish(1, 1, state, state.copy(forwardHistoryInvalidated = true)))
    }
    @Test fun externallyClearedSnapshotRejectsAnOldReadFailure() { assertFalse(currentReadMayPublish(1, 1, state, null)) }
}
