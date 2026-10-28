package dev.abdus.apps.shimmer.ui.settings

import dev.abdus.apps.shimmer.p4.IntervalSettings
import dev.abdus.apps.shimmer.p4.IntervalUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleSummaryTest {
    @Test
    fun labelShowsTheStoredValueAndUnit() {
        assertEquals("每 30 分钟", ScheduleSummary.intervalLabel(IntervalSettings()))
        assertEquals("每 2 小时", ScheduleSummary.intervalLabel(IntervalSettings(value = 2, unit = IntervalUnit.HOURS)))
    }

    @Test
    fun labelNeverConvertsIntoAnotherUnit() {
        // 90 秒不写成 1.5 分钟，120 分钟不写成 2 小时，与 P4 页保持同一写法
        assertEquals("每 90 秒", ScheduleSummary.intervalLabel(IntervalSettings(value = 90, unit = IntervalUnit.SECONDS)))
        assertEquals("每 120 分钟", ScheduleSummary.intervalLabel(IntervalSettings(value = 120, unit = IntervalUnit.MINUTES)))
    }
}
