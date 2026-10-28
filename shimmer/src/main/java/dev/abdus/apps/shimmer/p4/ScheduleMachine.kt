package dev.abdus.apps.shimmer.p4

import java.time.Instant

/** What the coordinator must do after one machine call. */
data class ScheduleEffects(
    val dispatch: ScheduleDispatch? = null,
    /** The matching rules changed: every Engine re-reads its effective catalog. */
    val refreshCatalog: Boolean = false,
) {
    companion object {
        val NONE = ScheduleEffects()
    }
}

/**
 * Pure scheduling state machine implementing the P4 event table. Every call takes one
 * [ScheduleNow] reading, so tests drive it with a fake clock. Main thread only.
 *
 * - Coincident triggers merge into one dispatch; triggers due while nothing is visible become
 *   [ScheduleState.pending] and dispatch once at the next visible moment, with no catch-up.
 * - A lock cycle (away window) opens at screen-off and closes at `USER_PRESENT` or when visible,
 *   interactive and unlocked. LOCK, SCREEN_OFF and UNLOCK share one switch per cycle; any switch
 *   inside the cycle uses it up.
 * - Persisted fields live in [state]; the coordinator saves it when it changes and drains
 *   [drainEvents] into the diagnostics log.
 */
class ScheduleMachine(settings: ScheduleSettings, state: ScheduleState) {
    var settings: ScheduleSettings = settings
        private set
    var state: ScheduleState = state
        private set

    var visible = false
        private set
    private var started = false
    private var visibleSince: Long? = null
    private var invisibleSince: Long? = null
    /** Start of the visible stretch not yet folded into [ScheduleState.visibleAccumMillis]. */
    private var segmentStart: Long? = null
    /** False right after a settings or clock change, so their dues are not lateness samples. */
    private var measurable = true
    private var lastOpenElapsed: Long? = null
    private var lastCloseElapsed: Long? = null
    private var lastWall: Long? = null
    private var lastElapsed: Long? = null
    private var retries = 0
    private val inFlight = LinkedHashMap<Long, Flight>()
    private val events = mutableListOf<ScheduleEvent>()

    private data class Flight(
        val dispatchElapsed: Long,
        val reasons: Set<ScheduleReason>,
        val cycle: Long?,
        /** Held by the editor; its commit comes after the edit, so it is not a T2 sample. */
        val held: Boolean = false,
    )

    private data class Due(val reason: ScheduleReason, val lateMs: Long?, val hidden: Boolean)

    fun drainEvents(): List<ScheduleEvent> = events.toList().also { events.clear() }

    // ---- Entry points ------------------------------------------------------------------------

    /** Timer tick, registration or any other re-check. */
    fun evaluate(now: ScheduleNow): ScheduleEffects {
        begin(now)
        return step(now)
    }

    fun updateSettings(next: ScheduleSettings, now: ScheduleNow): ScheduleEffects {
        begin(now)
        foldVisible(now)
        settings = next
        measurable = false
        log(now, EventKind.SETTINGS, detail = summary(next))
        return step(now)
    }

    /** Visibility union over the registered formal Engines. */
    fun onVisibility(anyVisible: Boolean, now: ScheduleNow): ScheduleEffects {
        begin(now)
        if (anyVisible == visible) return step(now)
        if (anyVisible) {
            visible = true
            visibleSince = now.elapsed
            invisibleSince = null
            segmentStart = now.elapsed
            log(now, EventKind.VISIBLE, detail = deviceText(now))
        } else {
            foldVisible(now)
            visible = false
            visibleSince = null
            invisibleSince = now.elapsed
            segmentStart = null
            log(now, EventKind.INVISIBLE, detail = deviceText(now))
        }
        return step(now)
    }

    fun onScreenOff(now: ScheduleNow): ScheduleEffects {
        begin(now)
        // Rapid double presses: a late SCREEN_OFF may land after its cycle already ended.
        val late = !state.awayOpen && now.interactive && !now.locked && recentEdge(now)
        log(now, EventKind.SCREEN_OFF, detail = deviceText(now) + if (late) "，迟到的熄屏广播，忽略" else "")
        if (!late && !state.awayOpen) openWindow(now, "熄屏")
        return step(now)
    }

    fun onScreenOn(now: ScheduleNow): ScheduleEffects {
        begin(now)
        log(now, EventKind.SCREEN_ON, detail = deviceText(now))
        return step(now)
    }
    fun onUserPresent(now: ScheduleNow): ScheduleEffects {
        begin(now)
        log(now, EventKind.USER_PRESENT, detail = deviceText(now))
        // The cycle belongs to this unlock while open, or just after a visible-unlocked close.
        val ownCycle = state.awayOpen || closedRecently(now)
        val skip = when {
            !settings.onUnlock -> null
            ownCycle && state.windowSwitched -> "本周期已切换"
            recentActivity(now) -> "距上次派发或提交不足 3 秒"
            else -> null
        }
        if (skip != null) log(now, EventKind.SKIP_UNLOCK, listOf(ScheduleReason.UNLOCK), cycle = state.cycleId, detail = skip)
        val extra = if (settings.onUnlock && skip == null) setOf(ScheduleReason.UNLOCK) else emptySet()
        val effects = step(now, extra)
        closeWindow(now, "解锁")
        return effects
    }

    /** `TIME_SET` or `TIMEZONE_CHANGED`: re-evaluate against the new local time. */
    fun onClockChanged(now: ScheduleNow, detail: String): ScheduleEffects {
        begin(now)
        measurable = false
        log(now, EventKind.CLOCK, detail = detail)
        return step(now)
    }

    /** A scheduled switch reached the screen. [catalogSize] is the effective catalog it used. */
    fun onCommitted(seq: Long, catalogSize: Int, now: ScheduleNow): ScheduleEffects {
        begin(now)
        val flight = inFlight.remove(seq)
        if (flight == null) {
            log(now, EventKind.OUTCOME, seq = seq, detail = "COMMITTED，未知或已结束的序号，忽略")
            return ScheduleEffects.NONE
        }
        retries = 0
        markSwitched(now)
        if (flight.cycle != null && flight.cycle == state.cycleId) {
            state = state.copy(cycleCommits = state.cycleCommits + 1)
        }
        log(
            now, EventKind.COMMIT, flight.reasons.sorted(), seq = seq, cycle = flight.cycle,
            commitMs = if (flight.held) null else now.elapsed - flight.dispatchElapsed,
            catalogSize = catalogSize, detail = if (flight.held) "编辑结束后提交，不计提交耗时" else "",
        )
        return ScheduleEffects.NONE
    }

    /** Gesture, shortcut, P2 page or direct set: not scheduled, but it restarts the interval. */
    fun onManualCommit(now: ScheduleNow, detail: String): ScheduleEffects {
        begin(now)
        val cycle = state.cycleId.takeIf { state.awayOpen }
        markSwitched(now)
        log(now, EventKind.MANUAL, cycle = cycle, detail = detail)
        return ScheduleEffects.NONE
    }

    private fun markSwitched(now: ScheduleNow) {
        state = state.copy(lastCommitElapsed = now.elapsed, windowSwitched = state.windowSwitched || state.awayOpen)
        resetInterval(now)
    }
    /**
     * Every non-commit result of a dispatch. Retryable outcomes re-dispatch the same reasons at
     * most [MAX_RETRIES] times in a row while visible; otherwise they wait for the next visible.
     */
    fun onOutcome(seq: Long, outcome: SwitchOutcome, now: ScheduleNow, detail: String = ""): ScheduleEffects {
        begin(now)
        require(outcome != SwitchOutcome.COMMITTED) { "use onCommitted" }
        val flight = inFlight[seq] ?: return ScheduleEffects.NONE
        val text = listOf(outcome.name, detail).filter { it.isNotEmpty() }.joinToString("，")
        if (outcome == SwitchOutcome.HELD_EDIT) {
            inFlight[seq] = flight.copy(held = true)
            log(now, EventKind.OUTCOME, flight.reasons.sorted(), seq = seq, cycle = flight.cycle, detail = "$text，编辑结束后执行")
            return ScheduleEffects.NONE
        }
        inFlight.remove(seq)
        log(now, EventKind.OUTCOME, flight.reasons.sorted(), seq = seq, cycle = flight.cycle, detail = text)
        if (!outcome.retry) {
            retries = 0
            return ScheduleEffects.NONE
        }
        if (visible && !settings.pauseAll) {
            if (retries >= MAX_RETRIES) {
                retries = 0
                log(now, EventKind.RETRY, flight.reasons.sorted(), seq = seq, detail = "连续 $MAX_RETRIES 次未提交，放弃本次切换")
                return ScheduleEffects.NONE
            }
            retries++
            log(now, EventKind.RETRY, flight.reasons.sorted(), seq = seq, detail = "第 $retries 次重试")
        } else {
            retries = 0
            log(now, EventKind.RETRY, flight.reasons.sorted(), seq = seq, detail = "等待下一次可见")
        }
        return step(now, flight.reasons, flight.cycle)
    }

    /** The editor closed: restart the interval. A held switch is replayed by the Engine itself. */
    fun onEditEnded(now: ScheduleNow): ScheduleEffects {
        begin(now)
        resetInterval(now)
        log(now, EventKind.EDIT_END, detail = "间隔重新计时")
        return step(now)
    }

    // ---- Evaluation --------------------------------------------------------------------------

    private fun begin(now: ScheduleNow) {
        if (started) return
        started = true
        checkBoot(now)
    }

    /** A new boot invalidates monotonic times and ends any lock cycle. */
    private fun checkBoot(now: ScheduleNow) {
        val s = state
        val rebooted = if (s.bootCount >= 0 && now.bootCount >= 0) {
            s.bootCount != now.bootCount
        } else {
            listOfNotNull(s.anchorElapsed, s.lastDispatchElapsed, s.lastCommitElapsed).any { it > now.elapsed }
        }
        val bootCount = if (now.bootCount >= 0) now.bootCount else s.bootCount
        state = if (rebooted) {
            // WALL: carry the elapsed wall time across the reboot; a clock moved back counts as 0.
            val anchor = s.anchorWall?.let { now.elapsed - (now.wall - it).coerceAtLeast(0L) }
            s.copy(
                bootCount = bootCount, anchorElapsed = anchor, awayOpen = false, lockConfirmed = false,
                windowSwitched = false, lastDispatchElapsed = null, lastCommitElapsed = null,
            )
        } else {
            s.copy(bootCount = bootCount)
        }
        log(now, EventKind.BOOT, detail = if (rebooted) "检测到重启，锁屏周期已清空" else "调度开始，开机次数 $bootCount")
    }
    /** [extra] are reasons without a due time (UNLOCK, retries); [cycleHint] their lock cycle. */
    private fun step(now: ScheduleNow, extra: Set<ScheduleReason> = emptySet(), cycleHint: Long? = null): ScheduleEffects {
        detectClockJump(now)
        syncInterval(now)
        prunePending()
        updateCycle(now)
        val dues = mutableListOf<Due>()
        val refresh = ruleDue(now, dues)
        intervalDue(now, dues)
        dailyDue(now, dues)
        measurable = true
        val effects = emit(now, dues, armedLockReasons() + extra, cycleHint)
        if (state.awayOpen && visible && now.interactive && !now.locked) closeWindow(now, "可见且未锁定")
        return if (refresh) effects.copy(refreshCatalog = true) else effects
    }

    private fun updateCycle(now: ScheduleNow) {
        // Fallback when the SCREEN_OFF broadcast is late or was missed.
        if (!state.awayOpen && !visible && !now.interactive) openWindow(now, "不可见且非交互")
        if (state.awayOpen && !state.lockConfirmed && now.locked) {
            state = state.copy(lockConfirmed = true)
            log(now, EventKind.LOCKED, cycle = state.cycleId, detail = "离开窗口内已确认锁定")
        }
    }

    /** RULE when the matching rules or their sources change, including through a settings edit. */
    private fun ruleDue(now: ScheduleNow, dues: MutableList<Due>): Boolean {
        val instant = Instant.ofEpochMilli(now.wall)
        val active = ScheduleRules.activeRules(settings, instant, now.zone)
        val key = ScheduleRules.ruleKey(active)
        if (key == state.ruleKey) return false
        state = state.copy(ruleKey = key)
        val boundary = ScheduleRules.previousRuleBoundary(settings, instant, now.zone)?.toEpochMilli()
        dues += due(ScheduleReason.RULE, now, boundary?.let { now.wall - it })
        val names = active.joinToString("、") { it.displayName }.ifEmpty { "无（普通目录）" }
        log(now, EventKind.RULE_CHANGE, detail = "匹配规则：$names")
        return true
    }

    /**
     * [lateMs] is now minus the due moment, when known. A due moment before the current visible
     * stretch is "hidden": merged at the visible edge (T4) and not a T1/T5 sample.
     */
    private fun due(reason: ScheduleReason, now: ScheduleNow, lateMs: Long?): Due {
        val since = visibleSince
        // Dues from a settings or clock change have no meaningful due moment: neither a sample nor hidden.
        val hidden = !visible || since == null || (measurable && lateMs != null && now.elapsed - lateMs < since)
        val sample = lateMs?.takeIf { measurable && !hidden && it >= 0L }
        return Due(reason, sample, hidden)
    }

    private fun intervalDue(now: ScheduleNow, dues: MutableList<Due>) {
        if (!intervalRunning()) return
        val over = intervalElapsed(now) - settings.interval.millis
        if (over >= 0L) dues += due(ScheduleReason.INTERVAL, now, over)
    }
    /** Each daily time fires at most once per local date; crossed times collapse into one. */
    private fun dailyDue(now: ScheduleNow, dues: MutableList<Due>) {
        val times = settings.dailyTimes.filter { it.enabled && it.minuteOfDay in 0 until ScheduleRules.MINUTES_PER_DAY }
        val keys = times.map { it.firedKey }.toSet()
        val fired = state.dailyFired.filterKeys { it in keys }.toMutableMap()
        val instant = Instant.ofEpochMilli(now.wall)
        var best: Due? = null
        for (time in times) {
            val (date, at) = ScheduleRules.latestDaily(time.minuteOfDay, instant, now.zone)
            val last = ScheduleRules.parseDate(fired[time.firedKey])
            val occurrence = at.toEpochMilli()
            if (last != null && !last.isBefore(date)) continue
            if (occurrence < time.editedAtWall || occurrence <= state.dailyGuardWall) continue
            fired[time.firedKey] = date.toString()
            val candidate = due(ScheduleReason.DAILY, now, now.wall - occurrence)
            // Coinciding times: keep the latest occurrence, i.e. the smallest lateness.
            if (best == null || (candidate.lateMs ?: Long.MAX_VALUE) < (best.lateMs ?: Long.MAX_VALUE)) best = candidate
        }
        if (fired != state.dailyFired) state = state.copy(dailyFired = fired)
        best?.let { dues += it }
    }

    /** Wall clock moved back against the monotonic clock: occurrences up to now must not fire again. */
    private fun detectClockJump(now: ScheduleNow) {
        val wall = lastWall
        val elapsed = lastElapsed
        lastWall = now.wall
        lastElapsed = now.elapsed
        if (wall == null || elapsed == null) return
        if (now.wall - wall < (now.elapsed - elapsed) - CLOCK_JUMP_TOLERANCE_MILLIS) {
            state = state.copy(dailyGuardWall = now.wall)
            measurable = false
            log(now, EventKind.CLOCK, detail = "墙钟倒退，已触发的每日定时不再重复")
        }
    }

    private fun emit(now: ScheduleNow, dues: List<Due>, extra: Set<ScheduleReason>, cycleHint: Long?): ScheduleEffects {
        val fresh = dues.map { it.reason }.toSet() + extra
        if (settings.pauseAll) {
            if (fresh.isEmpty() && state.pending.isEmpty()) return ScheduleEffects.NONE
            val all = state.pending + fresh
            val lockSlot = state.awayOpen && all.any { it in LOCK_CYCLE_REASONS }
            state = state.copy(pending = emptySet(), pendingCycle = null, windowSwitched = state.windowSwitched || lockSlot)
            log(now, EventKind.SKIP_PAUSED, all.sorted(), detail = "暂停全部调度")
            return ScheduleEffects.NONE
        }
        if (!visible) {
            val added = fresh - state.pending
            if (added.isEmpty()) return ScheduleEffects.NONE
            val cycle = cycleFor(now, fresh) ?: cycleHint ?: state.pendingCycle
            state = state.copy(pending = state.pending + added, pendingCycle = cycle)
            log(now, EventKind.PENDING, added.sorted(), cycle = cycle, detail = "不可见，下一次可见时切换")
            return ScheduleEffects.NONE
        }
        val all = state.pending + fresh
        if (all.isEmpty()) return ScheduleEffects.NONE
        val cycle = cycleFor(now, all) ?: cycleHint ?: state.pendingCycle
        return ScheduleEffects(dispatch = dispatch(now, all, dues, cycle))
    }

    /** The open cycle, or the one an UNLOCK just closed; null outside any observed cycle. */
    private fun cycleFor(now: ScheduleNow, reasons: Set<ScheduleReason>): Long? = when {
        state.awayOpen -> state.cycleId
        ScheduleReason.UNLOCK in reasons && closedRecently(now) -> state.cycleId
        else -> null
    }
    private fun dispatch(now: ScheduleNow, reasons: Set<ScheduleReason>, dues: List<Due>, cycle: Long?): ScheduleDispatch {
        val seq = state.seq + 1
        val merged = state.pending.any { it !in LOCK_CYCLE_REASONS } || dues.any { it.hidden }
        val mergeMs = visibleSince?.let { now.elapsed - it }?.takeIf { merged }
        val late = dues.mapNotNull { due -> due.lateMs?.let { due.reason to it } }.toMap()
        val result = ScheduleDispatch(seq, reasons)
        // T3 counts the switch that used the cycle's slot and any lock-cycle trigger after it;
        // a later INTERVAL/DAILY inside a long cycle runs on its own time and is not a repeat.
        val counted = cycle?.takeIf { reasons.any { it in LOCK_CYCLE_REASONS } || !state.windowSwitched }
        state = state.copy(
            seq = seq, pending = emptySet(), pendingCycle = null, lastDispatchElapsed = now.elapsed,
            // A conditional RULE may keep the image; it uses up the cycle only if it commits.
            windowSwitched = state.windowSwitched || (state.awayOpen && !result.conditional),
        )
        if (!result.conditional) resetInterval(now)
        inFlight[seq] = Flight(now.elapsed, reasons, counted)
        while (inFlight.size > MAX_IN_FLIGHT) inFlight.remove(inFlight.keys.first())
        log(
            now, EventKind.DISPATCH, reasons.sorted(), seq = seq, cycle = counted, late = late, mergeMs = mergeMs,
            detail = if (cycle != null && counted == null) "周期 $cycle 名额已用，按自身时间切换，不计入锁屏周期" else "",
        )
        return result
    }

    /** LOCK/SCREEN_OFF armed by the open cycle until something switches in it. */
    private fun armedLockReasons(): Set<ScheduleReason> {
        if (!state.awayOpen || state.windowSwitched) return emptySet()
        return buildSet {
            if (settings.onScreenOff) add(ScheduleReason.SCREEN_OFF)
            if (settings.onLock && state.lockConfirmed) add(ScheduleReason.LOCK)
        }
    }

    /** A trigger switched off while its reason was pending no longer fires. */
    private fun prunePending() {
        val kept = state.pending.filter { reasonEnabled(it) }.toSet()
        if (kept != state.pending) {
            state = state.copy(pending = kept, pendingCycle = state.pendingCycle.takeIf { kept.isNotEmpty() })
        }
    }

    private fun reasonEnabled(reason: ScheduleReason): Boolean = when (reason) {
        ScheduleReason.INTERVAL -> intervalRunning()
        ScheduleReason.DAILY -> settings.dailyTimes.any { it.enabled }
        ScheduleReason.RULE -> true
        ScheduleReason.LOCK -> settings.onLock
        ScheduleReason.SCREEN_OFF -> settings.onScreenOff
        ScheduleReason.UNLOCK -> settings.onUnlock
    }

    // ---- Lock cycle --------------------------------------------------------------------------

    private fun openWindow(now: ScheduleNow, cause: String) {
        val cycle = state.cycleId + 1
        state = state.copy(
            awayOpen = true, lockConfirmed = now.locked, windowSwitched = false, cycleId = cycle, cycleCommits = 0,
        )
        lastOpenElapsed = now.elapsed
        log(now, EventKind.CYCLE_OPEN, cycle = cycle, detail = cause + if (now.locked) "，已锁定" else "")
    }

    private fun closeWindow(now: ScheduleNow, cause: String) {
        if (!state.awayOpen) return
        state = state.copy(awayOpen = false)
        lastCloseElapsed = now.elapsed
        log(now, EventKind.CYCLE_CLOSE, cycle = state.cycleId, detail = cause + if (state.windowSwitched) "，本周期已切换" else "，本周期未切换")
    }

    private fun recentEdge(now: ScheduleNow): Boolean =
        listOfNotNull(lastOpenElapsed, lastCloseElapsed).any { now.elapsed - it < UNLOCK_DEDUPE_MILLIS }

    private fun closedRecently(now: ScheduleNow): Boolean =
        lastCloseElapsed?.let { now.elapsed - it < UNLOCK_DEDUPE_MILLIS } == true

    private fun recentActivity(now: ScheduleNow): Boolean =
        listOfNotNull(state.lastDispatchElapsed, state.lastCommitElapsed).any { now.elapsed - it < UNLOCK_DEDUPE_MILLIS }
    // ---- Interval ----------------------------------------------------------------------------

    private fun intervalRunning(): Boolean =
        settings.interval.enabled && settings.interval.valid && !settings.pauseAll

    private fun intervalKey(): String = with(settings.interval) { "$enabled/$millis/$clock/${settings.pauseAll}" }

    private fun syncInterval(now: ScheduleNow) {
        if (state.intervalKey != intervalKey() || (intervalRunning() && state.anchorElapsed == null)) resetInterval(now)
    }

    private fun resetInterval(now: ScheduleNow) {
        state = state.copy(
            intervalKey = intervalKey(), visibleAccumMillis = 0L, anchorElapsed = now.elapsed, anchorWall = now.wall,
        )
        segmentStart = now.elapsed.takeIf { visible }
    }

    /** Folds the running visible stretch into the persisted total (VISIBLE clock only). */
    private fun foldVisible(now: ScheduleNow) {
        val start = segmentStart
        if (visible && start != null && intervalRunning() && settings.interval.clock == IntervalClock.VISIBLE) {
            state = state.copy(visibleAccumMillis = state.visibleAccumMillis + (now.elapsed - start).coerceAtLeast(0L))
        }
        segmentStart = now.elapsed.takeIf { visible }
    }

    private fun intervalElapsed(now: ScheduleNow): Long = when (settings.interval.clock) {
        IntervalClock.VISIBLE -> state.visibleAccumMillis + (segmentStart?.let { now.elapsed - it } ?: 0L)
        IntervalClock.WALL -> now.elapsed - (state.anchorElapsed ?: now.elapsed)
    }.coerceAtLeast(0L)

    /** Time left before the interval is due, or null when it is not running. */
    fun intervalRemaining(now: ScheduleNow): Long? =
        if (intervalRunning()) (settings.interval.millis - intervalElapsed(now)).coerceAtLeast(0L) else null

    // ---- Timer and status --------------------------------------------------------------------

    /**
     * Delay to the next check. While visible: the nearest interval, daily or rule boundary, at
     * most [MAX_CHECK_MILLIS]. Right after going invisible: a short poll for the non-interactive
     * fallback in case `SCREEN_OFF` is late. Otherwise null: no timer while nothing is visible.
     */
    fun nextCheckDelay(now: ScheduleNow): Long? {
        if (!visible) {
            val since = invisibleSince ?: return null
            if (state.awayOpen || now.elapsed - since > FALLBACK_POLL_WINDOW_MILLIS) return null
            return FALLBACK_POLL_MILLIS
        }
        val instant = Instant.ofEpochMilli(now.wall)
        val candidates = mutableListOf(MAX_CHECK_MILLIS)
        intervalRemaining(now)?.let { candidates += it }
        settings.dailyTimes.filter { it.enabled && it.minuteOfDay in 0 until ScheduleRules.MINUTES_PER_DAY }.forEach {
            candidates += ScheduleRules.nextDaily(it.minuteOfDay, instant, now.zone).toEpochMilli() - now.wall
        }
        ScheduleRules.nextRuleBoundary(settings, instant, now.zone)?.let { candidates += it.toEpochMilli() - now.wall }
        return candidates.min().coerceIn(MIN_CHECK_MILLIS, MAX_CHECK_MILLIS)
    }
    /** Chinese status for the P4 page. Only runs the one-time boot check; never dispatches. */
    fun statusLines(now: ScheduleNow): List<String> {
        begin(now)
        val s = state
        val active = ScheduleRules.activeRules(settings, Instant.ofEpochMilli(now.wall), now.zone)
        val interval = when {
            settings.pauseAll -> "已暂停"
            !settings.interval.enabled -> "关闭"
            !settings.interval.valid -> "设置无效"
            else -> "剩余约 ${((intervalRemaining(now) ?: 0L) + 999L) / 1_000L} 秒" +
                if (settings.interval.clock == IntervalClock.VISIBLE && !visible) "（壁纸不可见，暂停累计）" else ""
        }
        val cycle = if (s.awayOpen) {
            "进行中（第 ${s.cycleId} 个，" + (if (s.lockConfirmed) "已锁定" else "未确认锁定") +
                "，" + (if (s.windowSwitched) "已切换" else "未切换") + "）"
        } else {
            "无（上一个为第 ${s.cycleId} 个，调度提交 ${s.cycleCommits} 次）"
        }
        return listOf(
            "调度：" + if (settings.pauseAll) "已暂停全部调度" else "运行中",
            "壁纸可见：" + (if (visible) "是" else "否") + "；" + deviceText(now),
            "间隔：$interval",
            "锁屏周期：$cycle",
            "待办：" + (s.pending.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("、") { it.label } ?: "无"),
            "匹配规则（按优先级）：" + active.joinToString("、") { it.displayName }.ifEmpty { "无（普通目录）" },
            "派发序号：${s.seq}",
        )
    }

    private fun deviceText(now: ScheduleNow): String =
        "可交互=" + (if (now.interactive) "是" else "否") + "，锁定=" + (if (now.locked) "是" else "否")

    private fun summary(s: ScheduleSettings): String = buildString {
        append(if (s.pauseAll) "暂停全部" else "运行")
        with(s.interval) {
            append("；间隔=").append(if (enabled) "$value${unit.label}/${if (clock == IntervalClock.VISIBLE) "可见累计" else "自然时间"}" else "关")
        }
        append("；锁屏=").append(if (s.onLock) "开" else "关")
        append("；熄屏=").append(if (s.onScreenOff) "开" else "关")
        append("；解锁=").append(if (s.onUnlock) "开" else "关")
        append("；每日定时=").append(s.dailyTimes.count { it.enabled })
        append("；规则=").append(s.rules.count { it.enabled })
    }

    private fun log(
        now: ScheduleNow,
        kind: String,
        reasons: List<ScheduleReason> = emptyList(),
        seq: Long? = null,
        cycle: Long? = null,
        late: Map<ScheduleReason, Long> = emptyMap(),
        mergeMs: Long? = null,
        commitMs: Long? = null,
        catalogSize: Int? = null,
        detail: String = "",
    ) {
        events += ScheduleEvent(now.wall, now.elapsed, kind, reasons, seq, cycle, late, mergeMs, commitMs, catalogSize, detail)
    }

    companion object {
        /** Unlock dedupe and late broadcast window, see P4_PLAN. */
        const val UNLOCK_DEDUPE_MILLIS = 3_000L
        const val MAX_RETRIES = 2
        const val MAX_IN_FLIGHT = 16
        const val MIN_CHECK_MILLIS = 200L
        const val MAX_CHECK_MILLIS = 60_000L
        const val CLOCK_JUMP_TOLERANCE_MILLIS = 2_000L
        const val FALLBACK_POLL_MILLIS = 1_000L
        const val FALLBACK_POLL_WINDOW_MILLIS = 10_000L
    }
}
