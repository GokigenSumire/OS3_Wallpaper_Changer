package dev.abdus.apps.shimmer.p4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleRulesTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(local: String, zone: ZoneId = shanghai): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun rule(
        repeat: RuleRepeat = RuleRepeat.DAILY,
        id: String = "r",
        start: Int? = null,
        end: Int? = null,
        block: PlaybackRule.() -> PlaybackRule = { this },
    ) = PlaybackRule(id = id, sourceIds = listOf("s"), repeat = repeat, windowStart = start, windowEnd = end).block()

    @Test
    fun dstGapResolvesToTheFirstInstantAfterTheGap() {
        // 2026-03-08 02:30 does not exist in New York; the clock jumps from 02:00 EST to 03:00 EDT.
        val resolved = ScheduleRules.resolve(LocalDate.parse("2026-03-08"), 150, newYork)
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), resolved)
    }

    @Test
    fun dstOverlapResolvesToTheFirstOccurrence() {
        // 2026-11-01 01:30 happens twice; the first is still EDT (UTC-4).
        val resolved = ScheduleRules.resolve(LocalDate.parse("2026-11-01"), 90, newYork)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), resolved)
    }

    @Test
    fun yearlyRangeMayWrapTheYearEnd() {
        val winter = rule(RuleRepeat.YEARLY) { copy(startMonthDay = 1220, endMonthDay = 105) }
        assertTrue(ScheduleRules.matchesDate(winter, LocalDate.parse("2026-12-20")))
        assertTrue(ScheduleRules.matchesDate(winter, LocalDate.parse("2026-12-31")))
        assertTrue(ScheduleRules.matchesDate(winter, LocalDate.parse("2027-01-05")))
        assertFalse(ScheduleRules.matchesDate(winter, LocalDate.parse("2027-01-06")))
        assertFalse(ScheduleRules.matchesDate(winter, LocalDate.parse("2026-12-19")))

        val summer = rule(RuleRepeat.YEARLY) { copy(startMonthDay = 701, endMonthDay = 831) }
        assertTrue(ScheduleRules.matchesDate(summer, LocalDate.parse("2026-08-15")))
        assertFalse(ScheduleRules.matchesDate(summer, LocalDate.parse("2026-09-01")))
    }

    @Test
    fun february29IsFebruary28InACommonYear() {
        val leapDay = rule(RuleRepeat.YEARLY) { copy(startMonthDay = 229, endMonthDay = 229) }
        assertTrue(ScheduleRules.matchesDate(leapDay, LocalDate.parse("2027-02-28")))
        assertFalse(ScheduleRules.matchesDate(leapDay, LocalDate.parse("2027-03-01")))
        assertTrue(ScheduleRules.matchesDate(leapDay, LocalDate.parse("2028-02-29")))
        assertFalse(ScheduleRules.matchesDate(leapDay, LocalDate.parse("2028-02-28")))
    }

    @Test
    fun windowAcrossMidnightBelongsToItsStartDate() {
        // Friday 22:00 to Saturday 02:00.
        val friday = rule(RuleRepeat.WEEKLY, start = 22 * 60, end = 2 * 60) { copy(weekdays = setOf(5)) }
        assertEquals(5, LocalDate.parse("2026-10-02").dayOfWeek.value)
        assertFalse(ScheduleRules.isActive(friday, at("2026-10-02T21:59:59"), shanghai))
        assertTrue(ScheduleRules.isActive(friday, at("2026-10-02T22:00:00"), shanghai))
        assertTrue(ScheduleRules.isActive(friday, at("2026-10-03T01:59:59"), shanghai))
        assertFalse(ScheduleRules.isActive(friday, at("2026-10-03T02:00:00"), shanghai))
        // Saturday 22:00 is not a Friday occurrence.
        assertFalse(ScheduleRules.isActive(friday, at("2026-10-03T23:00:00"), shanghai))
    }

    @Test
    fun dateRangeIsInclusiveAndASingleDayNeedsNoEnd() {
        val holiday = rule(RuleRepeat.DATE_RANGE) { copy(startDate = "2026-10-01", endDate = "2026-10-07") }
        assertTrue(ScheduleRules.isActive(holiday, at("2026-10-01T00:00:00"), shanghai))
        assertTrue(ScheduleRules.isActive(holiday, at("2026-10-07T23:59:59"), shanghai))
        assertFalse(ScheduleRules.isActive(holiday, at("2026-10-08T00:00:00"), shanghai))
        assertFalse(ScheduleRules.isActive(holiday, at("2026-09-30T23:59:59"), shanghai))

        val oneDay = rule(RuleRepeat.DATE_RANGE) { copy(startDate = "2026-10-01") }
        assertNull(ScheduleRules.validate(oneDay))
        assertTrue(ScheduleRules.isActive(oneDay, at("2026-10-01T12:00:00"), shanghai))
        assertFalse(ScheduleRules.isActive(oneDay, at("2026-10-02T12:00:00"), shanghai))
    }

    @Test
    fun invalidRulesAreRejectedAndNeverActive() {
        val now = at("2026-10-02T12:00:00")
        val cases = mapOf(
            rule(start = 600, end = 600) to "时段开始不能等于结束",
            rule().copy(sourceIds = emptyList()) to "至少选择一个来源",
            rule(start = 600) to "时段需同时设置开始和结束",
            rule(RuleRepeat.DATE_RANGE) { copy(startDate = "2026-10-07", endDate = "2026-10-01") } to "结束日期不能早于开始日期",
            rule(RuleRepeat.WEEKLY) to "至少选择一个星期几",
            rule(RuleRepeat.YEARLY) { copy(startMonthDay = 230, endMonthDay = 301) } to "请设置有效的每年起止月日",
        )
        cases.forEach { (invalid, message) ->
            assertEquals(message, ScheduleRules.validate(invalid))
            assertFalse(ScheduleRules.isActive(invalid, now, shanghai))
        }
    }

    @Test
    fun activeRulesKeepListPriorityAndSkipDisabledRules() {
        val first = rule(id = "first")
        val disabled = rule(id = "disabled").copy(enabled = false)
        val second = rule(id = "second", start = 11 * 60, end = 13 * 60)
        val settings = ScheduleSettings(rules = listOf(disabled, second, first))
        val active = ScheduleRules.activeRules(settings, at("2026-10-02T12:00:00"), shanghai)
        assertEquals(listOf("second", "first"), active.map { it.id })
        assertEquals(listOf("first"), ScheduleRules.activeRules(settings, at("2026-10-02T14:00:00"), shanghai).map { it.id })
    }

    @Test
    fun ruleKeyChangesWithRulesOrSourcesButNotSourceOrder() {
        val a = rule(id = "a").copy(sourceIds = listOf("s1", "s2"))
        assertEquals(ScheduleRules.ruleKey(listOf(a)), ScheduleRules.ruleKey(listOf(a.copy(sourceIds = listOf("s2", "s1")))))
        assertTrue(ScheduleRules.ruleKey(listOf(a)) != ScheduleRules.ruleKey(listOf(a.copy(sourceIds = listOf("s1")))))
        assertEquals("", ScheduleRules.ruleKey(emptyList()))
    }

    @Test
    fun boundariesAreMidnightAndWindowEdges() {
        val night = rule(start = 22 * 60, end = 2 * 60)
        val settings = ScheduleSettings(rules = listOf(night))
        val noon = at("2026-10-02T12:00:00")
        assertEquals(at("2026-10-02T22:00:00"), ScheduleRules.nextRuleBoundary(settings, noon, shanghai))
        assertEquals(at("2026-10-02T02:00:00"), ScheduleRules.previousRuleBoundary(settings, noon, shanghai))
        assertEquals(at("2026-10-03T00:00:00"), ScheduleRules.nextRuleBoundary(settings, at("2026-10-02T23:00:00"), shanghai))
        assertNull(ScheduleRules.nextRuleBoundary(ScheduleSettings(), noon, shanghai))
    }

    @Test
    fun latestAndNextDailyUseTheLocalDate() {
        val (date, instant) = ScheduleRules.latestDaily(8 * 60, at("2026-10-02T07:59:00"), shanghai)
        assertEquals(LocalDate.parse("2026-10-01"), date)
        assertEquals(at("2026-10-01T08:00:00"), instant)
        val (today, exact) = ScheduleRules.latestDaily(8 * 60, at("2026-10-02T08:00:00"), shanghai)
        assertEquals(LocalDate.parse("2026-10-02"), today)
        assertEquals(at("2026-10-02T08:00:00"), exact)
        assertEquals(at("2026-10-03T08:00:00"), ScheduleRules.nextDaily(8 * 60, at("2026-10-02T08:00:00"), shanghai))
        assertNotNull(ScheduleRules.parseDate("2026-02-28"))
        assertNull(ScheduleRules.parseDate("2026-02-30"))
    }
}