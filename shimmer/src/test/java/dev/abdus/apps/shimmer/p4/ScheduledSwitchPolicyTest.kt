package dev.abdus.apps.shimmer.p4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledSwitchPolicyTest {
    private val rule = ScheduleDispatch(1, setOf(ScheduleReason.RULE))
    private val ruleAndInterval = ScheduleDispatch(2, setOf(ScheduleReason.RULE, ScheduleReason.INTERVAL))
    private val interval = ScheduleDispatch(3, setOf(ScheduleReason.INTERVAL))

    @Test
    fun ruleChangeKeepsAnImageStillInTheNewCatalog() {
        assertEquals(SwitchOutcome.RULE_KEEP, ScheduledSwitchPolicy.decide(rule, listOf("a", "b"), "a"))
        // The current image left the catalog: switch back into it.
        assertNull(ScheduledSwitchPolicy.decide(rule, listOf("a", "b"), "z"))
    }

    @Test
    fun ruleMergedWithAnotherReasonSwitchesNormally() {
        assertNull(ScheduledSwitchPolicy.decide(ruleAndInterval, listOf("a", "b"), "a"))
    }

    @Test
    fun singleImageAlreadyShowingIsSkipped() {
        assertEquals(SwitchOutcome.SKIP_SINGLE, ScheduledSwitchPolicy.decide(interval, listOf("a"), "a"))
        assertEquals(SwitchOutcome.SKIP_SINGLE, ScheduledSwitchPolicy.decide(ruleAndInterval, listOf("a"), "a"))
        // A single image that is not on screen yet is shown.
        assertNull(ScheduledSwitchPolicy.decide(interval, listOf("a"), "z"))
    }

    @Test
    fun defaultImageOrEmptyCatalogUsesTheNormalPath() {
        assertNull(ScheduledSwitchPolicy.decide(rule, listOf("a"), null))
        assertNull(ScheduledSwitchPolicy.decide(interval, emptyList(), "a"))
    }
}
