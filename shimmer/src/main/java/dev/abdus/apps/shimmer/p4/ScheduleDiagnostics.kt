package dev.abdus.apps.shimmer.p4

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/** Diagnostic event kinds; kept as raw identifiers so copied logs match the test card. */
object EventKind {
    const val BOOT = "BOOT"
    const val SETTINGS = "SETTINGS"
    const val VISIBLE = "VISIBLE"
    const val INVISIBLE = "INVISIBLE"
    const val SCREEN_OFF = "SCREEN_OFF"
    const val SCREEN_ON = "SCREEN_ON"
    const val USER_PRESENT = "USER_PRESENT"
    const val CLOCK = "CLOCK"
    const val CYCLE_OPEN = "CYCLE_OPEN"
    const val CYCLE_CLOSE = "CYCLE_CLOSE"
    const val LOCKED = "LOCKED"
    const val RULE_CHANGE = "RULE_CHANGE"
    const val PENDING = "PENDING"
    const val SKIP_PAUSED = "SKIP_PAUSED"
    const val SKIP_UNLOCK = "SKIP_UNLOCK"
    const val DISPATCH = "DISPATCH"
    const val COMMIT = "COMMIT"
    const val OUTCOME = "OUTCOME"
    const val RETRY = "RETRY"
    const val MANUAL = "MANUAL"
    const val EDIT_END = "EDIT_END"
}

@Serializable
data class ScheduleEvent(
    val wall: Long,
    val elapsed: Long,
    val kind: String,
    val reasons: List<ScheduleReason> = emptyList(),
    val seq: Long? = null,
    val cycle: Long? = null,
    /** Visible-time lateness of each due INTERVAL/DAILY/RULE reason (T1, T5). */
    val late: Map<ScheduleReason, Long> = emptyMap(),
    /** Visible edge to dispatch for a dispatch carrying pending reasons (T4). */
    val mergeMs: Long? = null,
    /** Dispatch to commit of the same seq (T2). */
    val commitMs: Long? = null,
    val catalogSize: Int? = null,
    val detail: String = "",
) {
    fun line(zone: ZoneId): String = buildString {
        append(TIME.format(Instant.ofEpochMilli(wall).atZone(zone)))
        append(" [").append(elapsed).append("] ").append(kind)
        seq?.let { append(" #").append(it) }
        cycle?.let { append(" 周期").append(it) }
        if (reasons.isNotEmpty()) append(' ').append(reasons.joinToString("+") { it.name })
        late.forEach { (reason, ms) -> append(" 晚到").append(reason.name).append('=').append(ms).append("ms") }
        mergeMs?.let { append(" 合并=").append(it).append("ms") }
        commitMs?.let { append(" 提交=").append(it).append("ms") }
        catalogSize?.let { append(" 目录=").append(it) }
        if (detail.isNotEmpty()) append(' ').append(detail)
    }

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}

/** Threshold samples; derived only from events so the report can be rebuilt and cleared. */
@Serializable
data class ScheduleStats(
    val sinceWall: Long = 0L,
    val dueLate: List<Long> = emptyList(),
    val ruleLate: List<Long> = emptyList(),
    val commitNormal: List<Long> = emptyList(),
    val commitLarge: List<Long> = emptyList(),
    val merge: List<Long> = emptyList(),
    /** Lock cycle id to scheduled commits in it (T3). */
    val cycleCommits: Map<Long, Int> = emptyMap(),
    val counts: Map<String, Int> = emptyMap(),
) {
    fun record(event: ScheduleEvent): ScheduleStats {
        var next = copy(counts = counts + (event.kind to (counts[event.kind] ?: 0) + 1))
        if (event.late.isNotEmpty()) {
            next = next.copy(dueLate = (next.dueLate + event.late.values).cap())
            event.late[ScheduleReason.RULE]?.let { next = next.copy(ruleLate = (next.ruleLate + it).cap()) }
        }
        event.mergeMs?.let { next = next.copy(merge = (next.merge + it).cap()) }
        if (event.kind == EventKind.CYCLE_OPEN && event.cycle != null) {
            next = next.copy(cycleCommits = (next.cycleCommits + (event.cycle to 0)).capCycles())
        }
        if (event.kind == EventKind.COMMIT) {
            event.commitMs?.let { ms ->
                next = if ((event.catalogSize ?: 0) <= NORMAL_LIBRARY_MAX) {
                    next.copy(commitNormal = (next.commitNormal + ms).cap())
                } else {
                    next.copy(commitLarge = (next.commitLarge + ms).cap())
                }
            }
            event.cycle?.let { cycle ->
                val count = next.cycleCommits[cycle]
                if (count != null) next = next.copy(cycleCommits = next.cycleCommits + (cycle to count + 1))
            }
        }
        return next
    }

    /** Chinese report for the P4 page, judged against the thresholds frozen in P4_PLAN.md. */
    fun report(zone: ZoneId): List<String> {
        val cycles = cycleCommits.values
        val repeated = cycles.count { it >= 2 }
        val since = if (sinceWall > 0L) SINCE.format(Instant.ofEpochMilli(sinceWall).atZone(zone)) else "首次运行"
        return listOf(
            "统计起点：$since",
            "T1 可见时到期延迟：" + judge(dueLate, 20, 1_000L, 2_000L),
            "T2 派发到提交（≤ $NORMAL_LIBRARY_MAX 张）：" + judge(commitNormal, 20, 2_000L, 4_000L),
            "T2 派发到提交（大图库）：" + judge(commitLarge, 10, 3_000L, 6_000L),
            "T3 锁屏周期：共 ${cycles.size} 个；0 次 ${cycles.count { it == 0 }}、1 次 ${cycles.count { it == 1 }}、" +
                "重复（≥ 2 次）$repeated → " + if (repeated == 0) "无重复" else "存在重复，未达标",
            "T3 说明：只计占用本周期名额的调度切换和锁屏/熄屏/解锁触发的切换；名额用掉后按自身时间到期的间隔、每日定时不计入。" +
                "开启锁屏/熄屏/解锁任一触发时每周期应恰好 1 次，全部触发（含间隔、每日定时）关闭时应为 0 次，请按当时设置判断",
            "T4 不可见合并延迟：" + judge(merge, 3, null, 1_000L),
            "T5 规则边界延迟：" + judge(ruleLate, 4, null, 2_000L),
            "计数：" + counts.entries.sortedBy { it.key }.joinToString("，") { "${it.key}=${it.value}" }.ifEmpty { "无" },
        )
    }

    private fun judge(values: List<Long>, minSamples: Int, p95Limit: Long?, maxLimit: Long): String {
        val summary = summarize(values) ?: return "无样本"
        val pass = (p95Limit == null || summary.p95 <= p95Limit) && summary.max <= maxLimit
        val verdict = when {
            !pass -> "未达标"
            summary.n < minSamples -> "样本不足（需 ≥ $minSamples）"
            else -> "达标"
        }
        val limits = listOfNotNull(p95Limit?.let { "p95 ≤ $it" }, "最大 ≤ $maxLimit").joinToString("，")
        return "n=${summary.n}，p50=${summary.p50}，p95=${summary.p95}，最大=${summary.max} ms（阈值 $limits ms）→ $verdict"
    }

    companion object {
        const val NORMAL_LIBRARY_MAX = 500
        const val SAMPLE_CAP = 200
        private val SINCE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private fun List<Long>.cap(): List<Long> = takeLast(SAMPLE_CAP)

        private fun Map<Long, Int>.capCycles(): Map<Long, Int> =
            if (size <= SAMPLE_CAP) this
            else entries.sortedBy { it.key }.takeLast(SAMPLE_CAP).associate { it.key to it.value }
    }
}

data class SampleSummary(val n: Int, val p50: Long, val p95: Long, val max: Long)

/** Nearest-rank percentiles. */
fun summarize(values: List<Long>): SampleSummary? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    fun rank(p: Double): Long = sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    return SampleSummary(sorted.size, rank(0.50), rank(0.95), sorted.last())
}
