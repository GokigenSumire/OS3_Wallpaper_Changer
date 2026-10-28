package dev.abdus.apps.shimmer.ui.settings

import dev.abdus.apps.shimmer.p4.IntervalSettings

/** Text for the settings tab schedule card, worded like the P4 page. */
object ScheduleSummary {
    /** The stored value and unit, e.g. "每 30 分钟"; never a value converted into another unit. */
    fun intervalLabel(interval: IntervalSettings): String = "每 ${interval.value} ${interval.unit.label}"
}
