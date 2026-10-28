package dev.abdus.apps.shimmer.p4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleFormatTest {
    private val base = RuleForm(id = "r", sourceIds = listOf("s"))

    @Test
    fun parsesTwentyFourHourTimes() {
        assertEquals(8 * 60 + 5, ScheduleFormat.parseTime("8:05"))
        assertEquals(23 * 60 + 59, ScheduleFormat.parseTime(" 23:59 "))
        assertEquals(22 * 60, ScheduleFormat.parseTime("22：00"))
        assertEquals(0, ScheduleFormat.parseTime("00:00"))
        listOf("24:00", "12:60", "12", "1205", "12:5", "ab:cd", "").forEach { assertNull(it, ScheduleFormat.parseTime(it)) }
        assertEquals("08:05", ScheduleFormat.hhmm(8 * 60 + 5))
    }

    @Test
    fun parsesMonthDaysIncludingFebruary29() {
        assertEquals(1220, ScheduleFormat.parseMonthDay("12-20"))
        assertEquals(105, ScheduleFormat.parseMonthDay("1-5"))
        assertEquals(229, ScheduleFormat.parseMonthDay("02-29"))
        listOf("02-30", "13-01", "00-10", "12/20", "").forEach { assertNull(it, ScheduleFormat.parseMonthDay(it)) }
        assertEquals("01-05", ScheduleFormat.monthDayText(105))
    }

    @Test
    fun formErrorsNameTheExpectedFormat() {
        fun error(form: RuleForm) = ScheduleFormat.toRule(form).error
        assertEquals("开始日期格式应为 2026-10-01", error(base.copy(repeat = RuleRepeat.DATE_RANGE, startDate = "10-01")))
        assertEquals(
            "结束日期格式应为 2026-10-07，或留空表示单日",
            error(base.copy(repeat = RuleRepeat.DATE_RANGE, startDate = "2026-10-01", endDate = "10-07")),
        )
        assertEquals("每年开始月日格式应为 12-20", error(base.copy(repeat = RuleRepeat.YEARLY, endMonthDay = "01-05")))
        assertEquals("每年结束月日格式应为 01-05", error(base.copy(repeat = RuleRepeat.YEARLY, startMonthDay = "12-20")))
        assertEquals("时段开始时间格式应为 22:00", error(base.copy(windowOn = true, windowEnd = "02:00")))
        assertEquals("时段结束时间格式应为 02:00", error(base.copy(windowOn = true, windowStart = "22:00")))
        assertEquals("时段开始不能等于结束", error(base.copy(windowOn = true, windowStart = "10:00", windowEnd = "10:00")))
        assertEquals("至少选择一个来源", error(base.copy(sourceIds = emptyList())))
        assertEquals("至少选择一个星期几", error(base.copy(repeat = RuleRepeat.WEEKLY)))
    }

    @Test
    fun validFormBecomesARuleAndBack() {
        val form = base.copy(
            name = "  周末夜间 ",
            repeat = RuleRepeat.WEEKLY,
            weekdays = setOf(5, 6),
            windowOn = true,
            windowStart = "22:00",
            windowEnd = "02:00",
            sourceIds = listOf("s", "s", "t"),
            // Hidden fields of other repeat types are ignored.
            startDate = "garbage",
        )
        val rule = ScheduleFormat.toRule(form).rule!!
        assertEquals("周末夜间", rule.name)
        assertEquals(listOf("s", "t"), rule.sourceIds)
        assertEquals(22 * 60, rule.windowStart)
        assertEquals(2 * 60, rule.windowEnd)
        assertNull(rule.startDate)
        assertEquals(rule, ScheduleFormat.toRule(ScheduleFormat.form(rule)).rule)

        val single = ScheduleFormat.toRule(base.copy(repeat = RuleRepeat.DATE_RANGE, startDate = "2026-10-01")).rule!!
        assertEquals("2026-10-01", single.endDate)
        val noWindow = ScheduleFormat.toRule(base.copy(windowOn = false, windowStart = "bad")).rule!!
        assertNull(noWindow.windowStart)
    }

    @Test
    fun describesRulesInChinese() {
        val weekly = PlaybackRule(
            id = "r", sourceIds = listOf("s", "gone"), repeat = RuleRepeat.WEEKLY, weekdays = setOf(5, 1),
            windowStart = 22 * 60, windowEnd = 2 * 60,
        )
        assertEquals("每周一、五 22:00–次日 02:00；来源 2 个（其中 1 个已删除）", ScheduleFormat.describe(weekly, setOf("s")))
        val yearly = PlaybackRule(id = "y", sourceIds = listOf("s"), repeat = RuleRepeat.YEARLY, startMonthDay = 1220, endMonthDay = 105)
        assertEquals("每年 12-20 至 01-05（跨年） 全天；来源 1 个", ScheduleFormat.describe(yearly, setOf("s")))
        val day = PlaybackRule(id = "d", sourceIds = listOf("s"), repeat = RuleRepeat.DATE_RANGE, startDate = "2026-10-01", endDate = "2026-10-01")
        assertEquals("日期 2026-10-01 全天；来源 1 个", ScheduleFormat.describe(day, setOf("s")))
        val invalid = PlaybackRule(id = "i", sourceIds = emptyList())
        assertTrue(ScheduleFormat.describe(invalid, emptySet()).endsWith("无效：至少选择一个来源，不会生效"))
    }
}
