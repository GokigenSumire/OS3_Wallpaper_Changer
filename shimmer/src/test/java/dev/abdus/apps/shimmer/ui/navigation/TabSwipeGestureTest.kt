package dev.abdus.apps.shimmer.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwipeGestureTest {
    @Test
    fun leftSwipeInLtrSelectsOnlyTheNextPageAt32Pixels() {
        val result = gesture().update(-32f, 0f, 100)
        assertEquals(2, result.targetIndex)
        assertTrue(result.consume)
        assertTrue(result.finished)
    }

    @Test
    fun rightSwipeInLtrSelectsThePreviousPage() {
        assertEquals(0, gesture().update(32f, 0f, 100).targetIndex)
    }

    @Test
    fun rtlDirectionsMirrorTheLogicalAdjacentPages() {
        assertEquals(2, gesture(isLtr = false).update(32f, 0f, 100).targetIndex)
        assertEquals(0, gesture(isLtr = false).update(-32f, 0f, 100).targetIndex)
    }

    @Test
    fun justBelowTriggerDoesNotSelectButClaimsAnIntentionalDrag() {
        val result = gesture().update(-31.99f, 0f, 100)
        assertNull(result.targetIndex)
        assertTrue(result.consume)
        assertFalse(result.finished)
    }

    @Test
    fun distanceAccumulatesOverSeveralUnconsumedMoves() {
        val machine = gesture()
        assertNull(machine.update(-7f, 0f, 20).targetIndex)
        assertNull(machine.update(-10f, 0f, 40).targetIndex)
        assertEquals(2, machine.update(-15f, 0f, 60).targetIndex)
    }

    @Test
    fun usesNetDistanceInsteadOfAddingBackAndForthTravel() {
        val machine = gesture()
        assertNull(machine.update(-20f, 0f, 50).targetIndex)
        assertNull(machine.update(15f, 0f, 100).targetIndex)
        assertNull(machine.update(-15f, 0f, 150).targetIndex)
        assertEquals(2, machine.update(-12f, 0f, 200).targetIndex)
    }

    @Test
    fun aSmallInitialVerticalWobbleDoesNotCancelLaterHorizontalSwipe() {
        val machine = gesture()
        val wobble = machine.update(0f, 4f, 20)
        assertFalse(wobble.consume)
        assertFalse(wobble.finished)
        assertEquals(2, machine.update(-32f, 0f, 80).targetIndex)
    }

    @Test
    fun jitterAtSystemSlopDoesNotConsumeOrClassify() {
        val result = gesture().update(8f, 8f, 100)
        assertFalse(result.consume)
        assertFalse(result.finished)
        assertNull(result.targetIndex)
    }

    @Test
    fun aVerticalDragBeyondSystemSlopPermanentlyYields() {
        val machine = gesture()
        val result = machine.update(3f, 9f, 50)
        assertTrue(result.finished)
        assertFalse(result.consume)
        assertNull(machine.update(-100f, 0f, 100).targetIndex)
    }

    @Test
    fun diagonalAtTheExistingToleranceStillSelects() {
        assertEquals(2, gesture().update(-32f, 48f, 100).targetIndex)
    }

    @Test
    fun diagonalBeyondTheExistingToleranceYields() {
        val result = gesture().update(-32f, 48.01f, 100)
        assertFalse(result.consume)
        assertTrue(result.finished)
        assertNull(result.targetIndex)
    }

    @Test
    fun horizontalIntentThatTurnsClearlyVerticalDoesNotSelect() {
        val machine = gesture()
        assertTrue(machine.update(-10f, 0f, 40).consume)
        val result = machine.update(-2f, 30f, 80)
        assertTrue(result.finished)
        assertFalse(result.consume)
        assertNull(machine.update(-100f, 0f, 100).targetIndex)
    }

    @Test
    fun childConsumedMotionWinsEvenIfItCrossesTriggerInOneEvent() {
        val machine = gesture()
        val result = machine.update(-100f, 0f, 50, consumedByChild = true)
        assertFalse(result.consume)
        assertTrue(result.finished)
        assertNull(result.targetIndex)
        assertNull(machine.update(-100f, 0f, 100).targetIndex)
    }

    @Test
    fun childTakingAnAlreadyObservedGestureCannotBeReacquired() {
        val machine = gesture()
        assertTrue(machine.update(-10f, 0f, 40).consume)
        assertTrue(machine.update(-3f, 0f, 60, consumedByChild = true).finished)
        assertNull(machine.update(-100f, 0f, 100).targetIndex)
    }

    @Test
    fun twoFingersPermanentlyYieldWithoutSelecting() {
        val machine = gesture()
        assertTrue(machine.update(-40f, 0f, 50, pointerCount = 2).finished)
        assertNull(machine.update(-40f, 0f, 100, pointerCount = 1).targetIndex)
    }

    @Test
    fun missingPointerDoesNotSelect() {
        val result = gesture().update(-40f, 0f, 50, pointerCount = 0)
        assertTrue(result.finished)
        assertFalse(result.consume)
        assertNull(result.targetIndex)
    }

    @Test
    fun anUnclaimedLongPressCannotTurnIntoPageNavigation() {
        val machine = gesture()
        assertFalse(machine.update(1f, 1f, 499).finished)
        val result = machine.update(-40f, 0f, 500)
        assertTrue(result.finished)
        assertFalse(result.consume)
        assertNull(machine.update(-100f, 0f, 600).targetIndex)
    }

    @Test
    fun anAlreadyClaimedSlowHorizontalDragIsNotTreatedAsLongPress() {
        val machine = gesture()
        assertTrue(machine.update(-10f, 0f, 100).consume)
        assertEquals(2, machine.update(-22f, 0f, 900).targetIndex)
    }

    @Test
    fun aTapReleaseIsNeverConsumedOrSelected() {
        val result = gesture().update(2f, 2f, 100, released = true)
        assertTrue(result.finished)
        assertFalse(result.consume)
        assertNull(result.targetIndex)
    }

    @Test
    fun unclaimedUpEventDoesNotBecomeASwipeShortcut() {
        val result = gesture().update(-40f, 0f, 100, released = true)
        assertTrue(result.finished)
        assertFalse(result.consume)
        assertNull(result.targetIndex)
    }

    @Test
    fun claimedReleaseCanCompleteDistanceWithoutReplaying() {
        val machine = gesture()
        assertTrue(machine.update(-20f, 0f, 50).consume)
        assertEquals(2, machine.update(-12f, 0f, 100, released = true).targetIndex)
        assertNull(machine.update(-40f, 0f, 120).targetIndex)
    }

    @Test
    fun claimedDragReleasedBeforeTriggerDoesNotSelect() {
        val machine = gesture()
        machine.update(-10f, 0f, 50)
        val result = machine.update(0f, 0f, 100, released = true)
        assertTrue(result.consume)
        assertTrue(result.finished)
        assertNull(result.targetIndex)
    }

    @Test
    fun enormousDistanceStillSelectsOnlyOneAdjacentPage() {
        val machine = gesture()
        assertEquals(2, machine.update(-1000f, 0f, 100).targetIndex)
        assertNull(machine.update(-1000f, 0f, 120).targetIndex)
    }

    @Test
    fun firstAndLastPageNeverWrapOrSelectAnInvalidIndex() {
        val first = gesture(selectedIndex = 0).update(100f, 0f, 100)
        val last = gesture(selectedIndex = 3).update(-100f, 0f, 100)
        assertNull(first.targetIndex)
        assertNull(last.targetIndex)
        assertTrue(first.finished)
        assertTrue(last.finished)
    }

    @Test
    fun both32PixelEdgesRemainExcludedWithoutConsumption() {
        listOf(0f, 31.99f, 328.01f, 360f).forEach { start ->
            val result = gesture(startX = start).update(-100f, 0f, 100)
            assertNull(result.targetIndex)
            assertFalse(result.consume)
            assertTrue(result.finished)
        }
    }

    @Test
    fun exactEdgeBoundariesRetainTheOriginalInclusiveContentRule() {
        assertEquals(2, gesture(startX = 32f).update(-32f, 0f, 100).targetIndex)
        assertEquals(2, gesture(startX = 328f).update(-32f, 0f, 100).targetIndex)
    }

    @Test
    fun pixelConfigurationKeeps32DpTriggerAndEdgesAcrossDensities() {
        listOf(0.75f, 1f, 2f, 3.5f).forEach { density ->
            val machine = gesture(startX = 180f * density, width = 360f * density,
                edge = 32f * density, threshold = 32f * density, slop = 8f * density)
            assertNull(machine.update(-31f * density, 0f, 50).targetIndex)
            assertEquals(2, machine.update(-density, 0f, 100).targetIndex)
        }
    }

    @Test
    fun aLargerSystemSlopIsNeverBypassedByThe32PixelThreshold() {
        val machine = gesture(slop = 40f)
        val early = machine.update(-32f, 0f, 50)
        assertFalse(early.consume)
        assertNull(early.targetIndex)
        assertEquals(2, machine.update(-9f, 0f, 100).targetIndex)
    }

    @Test
    fun invalidCoordinatesAndElapsedTimeFailClosed() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertNull(gesture().update(invalid, 0f, 100).targetIndex)
            assertNull(gesture().update(0f, invalid, 100).targetIndex)
        }
        assertNull(gesture().update(-100f, 0f, -1).targetIndex)
    }

    @Test
    fun invalidLayoutOrSelectionCannotTrigger() {
        val invalid = listOf(
            gesture(tabCount = 1), gesture(tabCount = 0), gesture(selectedIndex = -1),
            gesture(selectedIndex = 4), gesture(width = 64f), gesture(width = Float.NaN),
            gesture(startX = Float.NaN), gesture(edge = -1f), gesture(threshold = 0f),
            gesture(slop = -1f), gesture(longPress = 0),
        )
        invalid.forEach { machine ->
            val result = machine.update(-100f, 0f, 100)
            assertNull(result.targetIndex)
            assertFalse(result.consume)
            assertTrue(result.finished)
        }
    }

    private fun gesture(
        selectedIndex: Int = 1,
        tabCount: Int = 4,
        isLtr: Boolean = true,
        startX: Float = 180f,
        width: Float = 360f,
        edge: Float = 32f,
        threshold: Float = 32f,
        slop: Float = 8f,
        longPress: Long = 500,
    ) = TabSwipeGesture(selectedIndex, tabCount, isLtr, startX, width, edge, threshold, slop, longPress)
}
