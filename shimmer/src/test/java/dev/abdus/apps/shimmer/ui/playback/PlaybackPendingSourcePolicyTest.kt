package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test

/** 只读待播投影与正式恢复同一路径；不执行图片解码或壁纸 Engine。 */
class PlaybackPendingSourcePolicyTest {
    private fun source(name: String) = (1..3).map {
        PlaybackItem("$name$it", "$it.jpg", "$it.jpg", name, if (name == "A") 0 else 1,
            "DIRECTORY_TREE", "content://$name")
    }

    private fun state(order: PlaybackOrder = PlaybackOrder.NATURAL_ASC) = PlaybackSnapshot(
        order = order, currentId = "A2", historyIds = listOf("A1", "B1", "B3", "A2"), historyCursor = 3,
        sourcePositions = mapOf("A" to "A2", "B" to "B1"), sourceHistoryCursors = mapOf("A" to 3, "B" to 1),
    )

    private fun restored(items: List<PlaybackItem>, snapshot: PlaybackSnapshot = state()): PlaybackCoordinator =
        PlaybackOrderUpdate.restored(snapshot, items)

    @Test fun uniqueNewRootUsesItsOwnSavedIdentityRatherThanHistoryTailOrRelativeIndex() {
        assertEquals("B1", pendingSourceResumeId(restored(source("B"))))
    }

    @Test fun preparingPreviewNeverPublishesCurrentHistoryOrResumePoints() {
        val c = restored(source("B"))
        val before = c.snapshot()
        assertEquals("B1", pendingSourceResumeId(c))
        assertEquals(before, c.snapshot())
        assertEquals("A2", c.snapshot().currentId)
        assertEquals(before.sourcePositions, c.snapshot().sourcePositions)
    }

    @Test fun pendingMatchesFormalRequestInEveryOrder() {
        for (order in PlaybackOrder.entries) {
            val saved = state(order)
            val ui = restored(source("B"), saved)
            val engine = restored(source("B"), saved)
            assertEquals(order.name, engine.requestSourceRestore()?.item?.id, pendingSourceResumeId(ui))
            assertEquals(order, ui.snapshot().order)
        }
    }

    @Test fun neverPlayedRootUsesItsOwnQueueFirstInEveryOrder() {
        for (order in PlaybackOrder.entries) {
            val saved = state(order).copy(historyIds = listOf("A1", "A2"), historyCursor = 1,
                sourcePositions = mapOf("A" to "A2"), sourceHistoryCursors = emptyMap(),
                queueIds = if (order == PlaybackOrder.MANUAL) listOf("A2", "B3", "B1", "B2") else emptyList())
            val ui = restored(source("B"), saved)
            if (order == PlaybackOrder.RANDOM && saved.queueFingerprint == 0) ui.setOrder(order)
            val expected = ui.orderedItems().first().id
            assertEquals(order.name, expected, pendingSourceResumeId(ui))
        }
    }

    @Test fun noSavedPointUsesLastActualHistoryOfTheNewRoot() {
        assertEquals("B3", pendingSourceResumeId(restored(source("B"), state().copy(sourcePositions = mapOf("A" to "A2")))))
    }

    @Test fun invalidSavedPointFallsBackToRealQueueInsteadOfInventingPosition() {
        assertEquals("B1", pendingSourceResumeId(restored(source("B"), state().copy(sourcePositions = mapOf("B" to "B99")))))
    }

    @Test fun emptyOrMixedCatalogHasNoRootProjection() {
        assertNull(pendingSourceResumeId(restored(emptyList())))
        assertNull(pendingSourceResumeId(restored(source("B") + source("C"))))
    }

    @Test fun stillActiveCurrentNeverJumpsToAnotherSavedPoint() {
        assertNull(pendingSourceResumeId(restored(source("A"))))
        assertNull(pendingSourceResumeId(restored(source("A") + source("B"))))
    }

    @Test fun sameRootImageRemovalPreservesSkipContractNotAutoResume() {
        assertNull(pendingSourceResumeId(restored(source("A").filterNot { it.id == "A2" })))
    }

    @Test fun unknownSourceIdCannotClaimIndependentResumePoint() {
        assertNull(pendingSourceResumeId(restored(source("B").map { it.copy(sourceId = "") })))
    }

    @Test fun startupWithoutActualCurrentDoesNotInventAReplacement() {
        assertNull(pendingSourceResumeId(restored(source("B"), PlaybackSnapshot())))
    }

    @Test fun formalCommitClearsProjectionWithoutUiCommit() {
        val c = restored(source("B"))
        val request = c.requestSourceRestore()!!
        assertTrue(c.commit(request.version))
        assertNull(pendingSourceResumeId(c))
        assertEquals("B1", c.snapshot().currentId)
    }

    @Test fun repeatedProjectionCannotAdvanceHistoryOrQueue() {
        val c = restored(source("B"))
        val before = c.snapshot()
        repeat(5) { assertEquals("B1", pendingSourceResumeId(c)) }
        assertEquals(before, c.snapshot())
    }

    @Test fun sharedIdentityStillValidCannotBeRedirectedByChangingItsOwner() {
        val shared = source("B") + source("A").single { it.id == "A2" }.copy(sourceId = "B")
        assertNull(pendingSourceResumeId(restored(shared)))
    }
}
