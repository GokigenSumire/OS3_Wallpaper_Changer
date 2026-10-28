package dev.abdus.apps.shimmer.p4

/** Raw text of the rule editor, turned into a [PlaybackRule] or a Chinese error. */
data class RuleForm(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val repeat: RuleRepeat = RuleRepeat.DAILY,
    val startDate: String = "",
    val endDate: String = "",
    val startMonthDay: String = "",
    val endMonthDay: String = "",
    val weekdays: Set<Int> = emptySet(),
    val windowOn: Boolean = false,
    val windowStart: String = "",
    val windowEnd: String = "",
    val sourceIds: List<String> = emptyList(),
)

/** Parsing and Chinese descriptions shared by the P4 page and its tests. Pure. */
object ScheduleFormat {
    private val TIME = Regex("""^(\d{1,2})[:：](\d{2})$""")
    private val MONTH_DAY = Regex("""^(\d{1,2})-(\d{1,2})$""")
    private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

    /** "HH:mm" (a full-width colon is accepted) to minute of day, or null. */
    fun parseTime(text: String): Int? {
        val match = TIME.matchEntire(text.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** "MM-DD" to month * 100 + day, or null; Feb 29 is allowed. */
    fun parseMonthDay(text: String): Int? {
        val match = MONTH_DAY.matchEntire(text.trim()) ?: return null
        val value = match.groupValues[1].toInt() * 100 + match.groupValues[2].toInt()
        return value.takeIf(ScheduleRules::validMonthDay)
    }

    fun monthDayText(value: Int): String = "%02d-%02d".format(value / 100, value % 100)

    fun weekdayText(day: Int): String = WEEKDAYS.getOrElse(day - 1) { "?" }

    /** The parsed rule, or the first error to show in the editor. */
    data class Parsed(val rule: PlaybackRule?, val error: String?)

    fun toRule(form: RuleForm): Parsed {
        fun fail(message: String) = Parsed(null, message)
        var rule = PlaybackRule(
            id = form.id,
            name = form.name.trim(),
            enabled = form.enabled,
            sourceIds = form.sourceIds.distinct(),
            repeat = form.repeat,
        )
        when (form.repeat) {
            RuleRepeat.DAILY -> Unit
            RuleRepeat.DATE_RANGE -> {
                val start = ScheduleRules.parseDate(form.startDate.trim()) ?: return fail("开始日期格式应为 2026-10-01")
                val endText = form.endDate.trim()
                val end = if (endText.isEmpty()) start else ScheduleRules.parseDate(endText) ?: return fail("结束日期格式应为 2026-10-07，或留空表示单日")
                rule = rule.copy(startDate = start.toString(), endDate = end.toString())
            }
            RuleRepeat.YEARLY -> {
                val start = parseMonthDay(form.startMonthDay) ?: return fail("每年开始月日格式应为 12-20")
                val end = parseMonthDay(form.endMonthDay) ?: return fail("每年结束月日格式应为 01-05")
                rule = rule.copy(startMonthDay = start, endMonthDay = end)
            }
            RuleRepeat.WEEKLY -> rule = rule.copy(weekdays = form.weekdays)
        }
        if (form.windowOn) {
            val start = parseTime(form.windowStart) ?: return fail("时段开始时间格式应为 22:00")
            val end = parseTime(form.windowEnd) ?: return fail("时段结束时间格式应为 02:00")
            rule = rule.copy(windowStart = start, windowEnd = end)
        }
        ScheduleRules.validate(rule)?.let { return fail(it) }
        return Parsed(rule, null)
    }

    /** One line for the rule list, e.g. "每周一、五 22:00–次日 02:00；来源 2 个". */
    fun describe(rule: PlaybackRule, knownSourceIds: Set<String>): String = buildString {
        append(
            when (rule.repeat) {
                RuleRepeat.DAILY -> "每天"
                RuleRepeat.DATE_RANGE ->
                    if (rule.startDate == rule.endDate || rule.endDate == null) "日期 ${rule.startDate}"
                    else "日期 ${rule.startDate} 至 ${rule.endDate}"
                RuleRepeat.YEARLY -> {
                    val start = rule.startMonthDay?.let(::monthDayText) ?: "?"
                    val end = rule.endMonthDay?.let(::monthDayText) ?: "?"
                    val wraps = (rule.startMonthDay ?: 0) > (rule.endMonthDay ?: 0)
                    "每年 $start 至 $end" + if (wraps) "（跨年）" else ""
                }
                RuleRepeat.WEEKLY -> "每周" + rule.weekdays.sorted().joinToString("、") { weekdayText(it) }
            },
        )
        val start = rule.windowStart
        val end = rule.windowEnd
        if (start != null && end != null) {
            append(" ${hhmm(start)}–")
            append(if (start > end) "次日 ${hhmm(end)}" else hhmm(end))
        } else {
            append(" 全天")
        }
        val missing = rule.sourceIds.count { it !in knownSourceIds }
        append("；来源 ${rule.sourceIds.size} 个")
        if (missing > 0) append("（其中 $missing 个已删除）")
        ScheduleRules.validate(rule)?.let { append("；无效：$it，不会生效") }
    }

    fun form(rule: PlaybackRule): RuleForm = RuleForm(
        id = rule.id,
        name = rule.name,
        enabled = rule.enabled,
        repeat = rule.repeat,
        startDate = rule.startDate.orEmpty(),
        endDate = rule.endDate.orEmpty(),
        startMonthDay = rule.startMonthDay?.let(::monthDayText).orEmpty(),
        endMonthDay = rule.endMonthDay?.let(::monthDayText).orEmpty(),
        weekdays = rule.weekdays,
        windowOn = rule.windowStart != null,
        windowStart = rule.windowStart?.let(::hhmm).orEmpty(),
        windowEnd = rule.windowEnd?.let(::hhmm).orEmpty(),
        sourceIds = rule.sourceIds,
    )
}
