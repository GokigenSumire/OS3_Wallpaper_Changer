package dev.abdus.apps.shimmer.p3

import dev.abdus.apps.shimmer.p2.PlaybackDirection
import dev.abdus.apps.shimmer.p4.ScheduleDispatch
import dev.abdus.apps.shimmer.p4.ScheduleReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHoldGateTest {
    @Test
    fun admitsEverythingWhenNotHolding() {
        val gate = EditHoldGate()
        assertTrue(gate.admitTimerTick())
        assertTrue(gate.admitGesture())
        assertTrue(gate.admitRefresh())
        assertTrue(gate.admitSwitch(HeldAction.Step(PlaybackDirection.NEXT)))
        assertTrue(gate.admitSwitch(HeldAction.SetImage("content://a")))
        assertEquals(emptyList<HeldAction>(), gate.release())
    }

    @Test
    fun dropsTimerTicksAndGesturesWhileHolding() {
        val gate = EditHoldGate()
        gate.begin()
        assertFalse(gate.admitTimerTick())
        assertFalse(gate.admitGesture())
        // Dropped work is not replayed: the timer restarts and gestures belong to the editor.
        assertEquals(emptyList<HeldAction>(), gate.release())
        assertFalse(gate.holding)
    }

    @Test
    fun keepsOnlyTheNewestSwitchAndOneRefreshInOrder() {
        val gate = EditHoldGate()
        gate.begin()
        assertFalse(gate.admitSwitch(HeldAction.Step(PlaybackDirection.NEXT)))
        assertFalse(gate.admitRefresh())
        assertFalse(gate.admitSwitch(HeldAction.SetImage("content://a")))
        assertFalse(gate.admitRefresh())
        assertFalse(gate.admitSwitch(HeldAction.Step(PlaybackDirection.PREVIOUS)))

        assertEquals(
            listOf(HeldAction.Refresh, HeldAction.Step(PlaybackDirection.PREVIOUS)),
            gate.release(),
        )
    }

    @Test
    fun aHeldScheduledStepKeepsItsDispatchAndExposesTheOneANewerSwitchReplaces() {
        val gate = EditHoldGate()
        gate.begin()
        val scheduled = HeldAction.Step(PlaybackDirection.NEXT, ScheduleDispatch(4L, setOf(ScheduleReason.DAILY)))
        assertNull(gate.deferredSwitch)
        assertFalse(gate.admitSwitch(scheduled))
        assertEquals(scheduled, gate.deferredSwitch)
        // A refresh never replaces the deferred switch.
        assertFalse(gate.admitRefresh())
        assertEquals(scheduled, gate.deferredSwitch)

        val manual = HeldAction.SetImage("content://a")
        assertFalse(gate.admitSwitch(manual))
        assertEquals(manual, gate.deferredSwitch)
        assertFalse(gate.admitSwitch(scheduled))
        assertEquals(4L, (gate.release().last() as HeldAction.Step).dispatch?.seq)
        assertNull(gate.deferredSwitch)
    }

    @Test
    fun releaseClearsStateForTheNextEdit() {
        val gate = EditHoldGate()
        gate.begin()
        gate.admitRefresh()
        gate.admitSwitch(HeldAction.SetImage("content://a"))
        gate.release()

        assertFalse(gate.holding)
        assertTrue(gate.admitTimerTick())
        gate.begin()
        assertEquals(emptyList<HeldAction>(), gate.release())
    }
}
