package dev.abdus.apps.shimmer.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRowDisclosureTest {
    private val ids = linkedSetOf("a", "b", "c")

    @Test
    fun firstRowTapOnlyExpandsItsOperations() {
        assertEquals("a", PlaybackRowDisclosure.toggle(null, "a", ids))
    }

    @Test
    fun tappingTheExpandedRowAgainCollapsesIt() {
        assertNull(PlaybackRowDisclosure.toggle("a", "a", ids))
    }

    @Test
    fun tappingAnotherRowLeavesOnlyItsOperationsExpanded() {
        assertEquals("b", PlaybackRowDisclosure.toggle("a", "b", ids))
    }

    @Test
    fun blankOrRemovedRowsCannotExpandActions() {
        for (id in listOf("", " ", "removed")) {
            assertNull(PlaybackRowDisclosure.toggle(null, id, ids))
            assertEquals("a", PlaybackRowDisclosure.toggle("a", id, ids))
        }
    }

    @Test
    fun sourceRemovalInvalidatesTheOpenOperations() {
        assertNull(PlaybackRowDisclosure.retain("a", ids - "a"))
        assertNull(PlaybackRowDisclosure.toggle("a", "removed", ids - "a"))
    }

    @Test
    fun emptySourcesClearTheDisclosureWithoutSelectingAnotherImage() {
        assertNull(PlaybackRowDisclosure.retain("a", emptySet()))
        assertNull(PlaybackRowDisclosure.toggle("a", "b", emptySet()))
    }

    @Test
    fun savedBlankDisclosureIsNotRestoredAsAnActionTarget() {
        assertNull(PlaybackRowDisclosure.retain(" ", setOf(" ")))
        assertNull(PlaybackRowDisclosure.retain(null, ids))
    }

    @Test
    fun positionsAndCurrentChangesDoNotChangeTheOpenImageId() {
        assertEquals("b", PlaybackRowDisclosure.retain("b", linkedSetOf("c", "b", "a")))
        assertEquals("b", PlaybackRowDisclosure.retain("b", ids))
    }

    @Test
    fun collapsedRowsExposeNeitherPlaybackNorReorderControls() {
        for (manual in listOf(false, true)) for (selected in listOf(false, true)) {
            val actions = PlaybackRowDisclosure.actions(false, manual, selected)
            assertFalse(actions.showPlay)
            assertFalse(actions.showReorderSelection)
            assertFalse(actions.showMoveToPosition)
        }
    }

    @Test
    fun everyModeRequiresExpandedActionsBeforeThePlayButtonExists() {
        for (order in PlayOrder.values()) {
            val manual = order == PlayOrder.MANUAL
            assertFalse(PlaybackRowDisclosure.actions(false, manual, false).showPlay)
            assertTrue(PlaybackRowDisclosure.actions(true, manual, false).showPlay)
        }
    }

    @Test
    fun naturalAndRandomNeverExposeManualControlsEvenWithAStaleSelection() {
        for (order in PlayOrder.values().filter { it != PlayOrder.MANUAL }) {
            val actions = PlaybackRowDisclosure.actions(true, false, true)
            assertTrue("$order", actions.showPlay)
            assertFalse("$order", actions.showReorderSelection)
            assertFalse("$order", actions.showMoveToPosition)
        }
    }

    @Test
    fun manualDisclosureDoesNotPretendTheImageWasSelectedForReorder() {
        val actions = PlaybackRowDisclosure.actions(true, true, false)
        assertTrue(actions.showPlay)
        assertTrue(actions.showReorderSelection)
        assertFalse(actions.showMoveToPosition)
    }

    @Test
    fun selectedManualRowShowsSeparatePlaySelectAndPositionControls() {
        val actions = PlaybackRowDisclosure.actions(true, true, true)
        assertTrue(actions.showPlay)
        assertTrue(actions.showReorderSelection)
        assertTrue(actions.showMoveToPosition)
    }

    @Test
    fun repeatedDisclosureDoesNotMutateTheAvailableQueue() {
        val original = ids.toList()
        var expanded: String? = null
        repeat(20) {
            expanded = PlaybackRowDisclosure.toggle(expanded, "a", ids)
            assertEquals(original, ids.toList())
        }
        assertNull(expanded)
    }
}
