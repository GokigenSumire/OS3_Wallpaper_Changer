package dev.abdus.apps.shimmer.p4

import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/** Pure local date/time evaluation for daily times and playback rules. No Android dependency. */
object ScheduleRules {
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * The instant a local [date] and [minuteOfDay] name in [zone]. A time inside a DST gap
     * resolves to the first valid instant after the gap; a repeated time to its first occurrence.
     */
    fun resolve(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Instant {
        val local = date.atStartOfDay().plusMinutes(minuteOfDay.toLong())
        val offsets = zone.rules.getValidOffsets(local)
        // Overlap lists the offset before the transition first, which is the earlier instant.
        return if (offsets.isNotEmpty()) local.atOffset(offsets.first()).toInstant()
        else zone.rules.getTransition(local).instant
    }

    fun localDate(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    fun parseDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun validMonthDay(value: Int): Boolean = runCatching { MonthDay.of(value / 100, value % 100) }.isSuccess

    /** [monthDay] (month * 100 + day) in [year]; Feb 29 is Feb 28 in a non-leap year. */
    fun monthDayOn(year: Int, monthDay: Int): LocalDate? =
        runCatching { MonthDay.of(monthDay / 100, monthDay % 100).atYear(year) }.getOrNull()

    fun matchesDate(rule: PlaybackRule, date: LocalDate): Boolean = when (rule.repeat) {
        RuleRepeat.DAILY -> true
        RuleRepeat.DATE_RANGE -> {
            val start = parseDate(rule.startDate)
            val end = parseDate(rule.endDate ?: rule.startDate)
            start != null && end != null && !end.isBefore(start) && !date.isBefore(start) && !date.isAfter(end)
        }
        RuleRepeat.YEARLY -> {
            val start = rule.startMonthDay?.let { monthDayOn(date.year, it) }
            val end = rule.endMonthDay?.let { monthDayOn(date.year, it) }
            when {
                start == null || end == null -> false
                !start.isAfter(end) -> !date.isBefore(start) && !date.isAfter(end)
                // Wraps the year end, e.g. 12-20..01-05.
                else -> !date.isBefore(start) || !date.isAfter(end)
            }
        }
        RuleRepeat.WEEKLY -> date.dayOfWeek.value in rule.weekdays
    }

    /**
     * The [start, end) the rule covers for its occurrence beginning on [date], or null when the
     * date does not match. A window whose start is after its end runs into the next day and still
     * belongs to [date]; no window is the whole day.
     */
    fun occurrence(rule: PlaybackRule, date: LocalDate, zone: ZoneId): Pair<Instant, Instant>? {
        if (!matchesDate(rule, date)) return null
        val start = rule.windowStart
        val end = rule.windowEnd
        return when {
            start == null || end == null -> resolve(date, 0, zone) to resolve(date.plusDays(1), 0, zone)
            start < end -> resolve(date, start, zone) to resolve(date, end, zone)
            start > end -> resolve(date, start, zone) to resolve(date.plusDays(1), end, zone)
            else -> null
        }
    }

    fun isActive(rule: PlaybackRule, instant: Instant, zone: ZoneId): Boolean {
        if (!rule.enabled || validate(rule) != null) return false
        val today = localDate(instant, zone)
        // Yesterday's occurrence may still run past midnight.
        return listOf(today, today.minusDays(1)).any { date ->
            occurrence(rule, date, zone)?.let { (start, end) -> !instant.isBefore(start) && instant.isBefore(end) } == true
        }
    }

    /** Matching rules in priority (list) order. */
    fun activeRules(settings: ScheduleSettings, instant: Instant, zone: ZoneId): List<PlaybackRule> =
        settings.rules.filter { isActive(it, instant, zone) }

    /** Dedupe key: matching rule ids and their source sets. RULE is produced only when it changes. */
    fun ruleKey(active: List<PlaybackRule>): String =
        active.joinToString(";") { rule -> rule.id + "=" + rule.sourceIds.sorted().joinToString(",") }

    /** Every instant at which the matching set may change: local midnight and each window edge. */
    private fun boundaries(settings: ScheduleSettings, around: Instant, zone: ZoneId): List<Instant> {
        val rules = settings.rules.filter { it.enabled }
        if (rules.isEmpty()) return emptyList()
        val minutes = (rules.flatMap { listOfNotNull(it.windowStart, it.windowEnd) } + 0).toSortedSet()
        val today = localDate(around, zone)
        return (-1L..2L).flatMap { offset -> minutes.map { resolve(today.plusDays(offset), it, zone) } }
    }

    fun nextRuleBoundary(settings: ScheduleSettings, after: Instant, zone: ZoneId): Instant? =
        boundaries(settings, after, zone).filter { it.isAfter(after) }.minOrNull()

    fun previousRuleBoundary(settings: ScheduleSettings, atOrBefore: Instant, zone: ZoneId): Instant? =
        boundaries(settings, atOrBefore, zone).filter { !it.isAfter(atOrBefore) }.maxOrNull()

    /** The latest occurrence of [minuteOfDay] at or before [instant], with its local date. */
    fun latestDaily(minuteOfDay: Int, instant: Instant, zone: ZoneId): Pair<LocalDate, Instant> {
        val today = localDate(instant, zone)
        val at = resolve(today, minuteOfDay, zone)
        if (!at.isAfter(instant)) return today to at
        val yesterday = today.minusDays(1)
        return yesterday to resolve(yesterday, minuteOfDay, zone)
    }

    fun nextDaily(minuteOfDay: Int, instant: Instant, zone: ZoneId): Instant {
        val today = localDate(instant, zone)
        val at = resolve(today, minuteOfDay, zone)
        return if (at.isAfter(instant)) at else resolve(today.plusDays(1), minuteOfDay, zone)
    }

    /** Null when [rule] can be saved; otherwise the reason, shown in the editor. */
    fun validate(rule: PlaybackRule): String? {
        if (rule.sourceIds.isEmpty()) return "至少选择一个来源"
        val start = rule.windowStart
        val end = rule.windowEnd
        if ((start == null) != (end == null)) return "时段需同时设置开始和结束"
        if (start != null && end != null) {
            if (start !in 0 until MINUTES_PER_DAY || end !in 0 until MINUTES_PER_DAY) return "时段时间无效"
            if (start == end) return "时段开始不能等于结束"
        }
        when (rule.repeat) {
            RuleRepeat.DAILY -> Unit
            RuleRepeat.DATE_RANGE -> {
                val first = parseDate(rule.startDate) ?: return "请设置开始日期"
                val last = parseDate(rule.endDate ?: rule.startDate) ?: return "结束日期无效"
                if (last.isBefore(first)) return "结束日期不能早于开始日期"
            }
            RuleRepeat.YEARLY -> {
                val valid = listOf(rule.startMonthDay, rule.endMonthDay).all { it != null && validMonthDay(it) }
                if (!valid) return "请设置有效的每年起止月日"
            }
            RuleRepeat.WEEKLY -> if (rule.weekdays.isEmpty() || rule.weekdays.any { it !in 1..7 }) return "至少选择一个星期几"
        }
        return null
    }
}
