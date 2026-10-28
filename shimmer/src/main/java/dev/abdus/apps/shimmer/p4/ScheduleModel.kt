package dev.abdus.apps.shimmer.p4

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the interval counts time. */
@Serializable
enum class IntervalClock {
    /** Only time while at least one formal Engine is visible (union over Engines). */
    @SerialName("VISIBLE") VISIBLE,

    /** Wall time: monotonic within one boot, clamped wall-clock delta across a reboot. */
    @SerialName("WALL") WALL,
}

@Serializable
enum class IntervalUnit(val millis: Long, val label: String) {
    @SerialName("SECONDS") SECONDS(1_000L, "秒"),
    @SerialName("MINUTES") MINUTES(60_000L, "分钟"),
    @SerialName("HOURS") HOURS(3_600_000L, "小时"),
}

@Serializable
data class IntervalSettings(
    val enabled: Boolean = false,
    val value: Int = 30,
    val unit: IntervalUnit = IntervalUnit.MINUTES,
    val clock: IntervalClock = IntervalClock.VISIBLE,
) {
    val millis: Long get() = value.toLong() * unit.millis
    val valid: Boolean get() = millis in MIN_MILLIS..MAX_MILLIS

    companion object {
        const val MIN_MILLIS = 10_000L
        const val MAX_MILLIS = 24L * 3_600_000L
    }
}

/** One "switch at HH:mm" entry. [minuteOfDay] is local time, 0..1439. */
@Serializable
data class DailyTime(
    val id: String,
    val minuteOfDay: Int,
    val enabled: Boolean = true,
    /** Wall time of the last change: an occurrence already past at save time does not fire. */
    val editedAtWall: Long = 0L,
) {
    /** Dedupe key; editing the time gives a new key, so the new time may fire the same day. */
    val firedKey: String get() = "$id@$minuteOfDay"
}

@Serializable
enum class RuleRepeat {
    @SerialName("DAILY") DAILY,

    /** One date or an inclusive date range ([PlaybackRule.startDate]..[PlaybackRule.endDate]). */
    @SerialName("DATE_RANGE") DATE_RANGE,

    /** Month-day range every year, which may wrap the year end. */
    @SerialName("YEARLY") YEARLY,

    @SerialName("WEEKLY") WEEKLY,
}

/**
 * A date/time rule whose P1 sources replace the normal catalog while it matches. The list order
 * in [ScheduleSettings.rules] is the priority.
 */
@Serializable
data class PlaybackRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    /** P1 source ids; a source disabled in P1 still counts, directory exclusions still apply. */
    val sourceIds: List<String> = emptyList(),
    val repeat: RuleRepeat = RuleRepeat.DAILY,
    /** DATE_RANGE: ISO local dates, inclusive; a single day has both equal. */
    val startDate: String? = null,
    val endDate: String? = null,
    /** YEARLY: month * 100 + day, inclusive; start after end wraps the year end. */
    val startMonthDay: Int? = null,
    val endMonthDay: Int? = null,
    /** WEEKLY: ISO day numbers, 1 = Monday .. 7 = Sunday. */
    val weekdays: Set<Int> = emptySet(),
    /** Optional local minute-of-day window; start after end crosses midnight into the next day. */
    val windowStart: Int? = null,
    val windowEnd: Int? = null,
) {
    /** Name shown in lists and logs. */
    val displayName: String get() = name.ifBlank { "未命名规则" }
}

@Serializable
data class ScheduleSettings(
    /** Dispatches nothing; the rule catalog is still computed. */
    val pauseAll: Boolean = false,
    val interval: IntervalSettings = IntervalSettings(),
    val onLock: Boolean = false,
    val onScreenOff: Boolean = false,
    val onUnlock: Boolean = false,
    val dailyTimes: List<DailyTime> = emptyList(),
    val rules: List<PlaybackRule> = emptyList(),
)

@Serializable
enum class ScheduleReason(val label: String) {
    @SerialName("INTERVAL") INTERVAL("间隔"),
    @SerialName("DAILY") DAILY("每日定时"),
    @SerialName("RULE") RULE("规则"),
    @SerialName("LOCK") LOCK("锁屏"),
    @SerialName("SCREEN_OFF") SCREEN_OFF("熄屏"),
    @SerialName("UNLOCK") UNLOCK("解锁"),
}

/** Lock-cycle reasons share one switch per cycle. */
val LOCK_CYCLE_REASONS = setOf(ScheduleReason.LOCK, ScheduleReason.SCREEN_OFF, ScheduleReason.UNLOCK)

/** Runtime state; persisted so a reclaimed process resumes the same cycle and interval. */
@Serializable
data class ScheduleState(
    /** `Settings.Global.BOOT_COUNT`, -1 when unreadable. */
    val bootCount: Int = -1,
    /** Interval settings the running interval was started with; a change restarts it. */
    val intervalKey: String = "",
    /** VISIBLE: visible time already counted toward the running interval. */
    val visibleAccumMillis: Long = 0L,
    /** WALL: interval start, monotonic within this boot and wall time for a reboot. */
    val anchorElapsed: Long? = null,
    val anchorWall: Long? = null,
    /** [DailyTime.firedKey] to the ISO local date it last fired for. */
    val dailyFired: Map<String, String> = emptyMap(),
    /** Set when the clock moved back before a fired date; earlier occurrences do not fire. */
    val dailyGuardWall: Long = 0L,
    /** Matching rules and their sources; RULE is produced only when this changes. */
    val ruleKey: String = "",
    /** Reasons due while nothing was visible, dispatched once at the next visible moment. */
    val pending: Set<ScheduleReason> = emptySet(),
    /** Lock cycle the pending reasons belong to, so their later commit still counts toward it. */
    val pendingCycle: Long? = null,
    val awayOpen: Boolean = false,
    val lockConfirmed: Boolean = false,
    val windowSwitched: Boolean = false,
    /** Latest lock cycle, open or closed; commits of its dispatches count toward it (T3). */
    val cycleId: Long = 0L,
    val cycleCommits: Int = 0,
    val lastDispatchElapsed: Long? = null,
    val lastCommitElapsed: Long? = null,
    val seq: Long = 0L,
)

/** One scheduled switch request handed to the executing Engine. */
data class ScheduleDispatch(val seq: Long, val reasons: Set<ScheduleReason>) {
    /** A pure rule change switches only when the current image left the catalog. */
    val conditional: Boolean get() = reasons == setOf(ScheduleReason.RULE)

    /** Dropped while editing; every other reason is held for the end of the edit. */
    val intervalOnly: Boolean get() = reasons == setOf(ScheduleReason.INTERVAL)
}

enum class SwitchOutcome(val retry: Boolean = false) {
    COMMITTED,
    /** The catalog holds only the image already on screen. */
    SKIP_SINGLE,
    /** Rule change and the current image is still in the new catalog. */
    RULE_KEEP,
    NO_CANDIDATE,
    /** Interval tick while editing. */
    DROPPED_EDIT,
    /** Held by the editor; replayed once when editing ends. */
    HELD_EDIT,
    /** A manual gesture replaced the scheduled switch. */
    SUPERSEDED,
    DROPPED(retry = true),
    STALE(retry = true),
    CANCELLED(retry = true),
}

/** One consistent reading of every clock and device state the machine needs. */
data class ScheduleNow(
    val elapsed: Long,
    val wall: Long,
    val zone: java.time.ZoneId,
    val bootCount: Int,
    val interactive: Boolean,
    val locked: Boolean,
)
