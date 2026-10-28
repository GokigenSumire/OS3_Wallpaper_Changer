package dev.abdus.apps.shimmer.p4

import dev.abdus.apps.shimmer.p4.ScheduleReason.DAILY
import dev.abdus.apps.shimmer.p4.ScheduleReason.INTERVAL
import dev.abdus.apps.shimmer.p4.ScheduleReason.LOCK
import dev.abdus.apps.shimmer.p4.ScheduleReason.RULE
import dev.abdus.apps.shimmer.p4.ScheduleReason.SCREEN_OFF
import dev.abdus.apps.shimmer.p4.ScheduleReason.UNLOCK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** P4 event table (P4_PLAN.md) driven with a fake clock; device sequences follow the P0 T08 baseline. */
class ScheduleMachineTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")
    private var zone = shanghai
    private var elapsed = 1_000_000L
    private var wall = at("2026-10-02T12:00:00")
    private var bootCount = 5
    private var interactive = true
    private var locked = false

    private lateinit var m: ScheduleMachine
    private val events = mutableListOf<ScheduleEvent>()
    private val dispatches = mutableListOf<ScheduleDispatch>()
    /** The executing Engine commits every dispatch [COMMIT_MS] later unless a test reports otherwise. */
    private var autoCommit = true

    private fun at(local: String, z: ZoneId = zone): Long = LocalDateTime.parse(local).atZone(z).toInstant().toEpochMilli()
    private fun now() = ScheduleNow(elapsed, wall, zone, bootCount, interactive, locked)
    private fun advance(ms: Long) {
        elapsed += ms
        wall += ms
    }
    private fun advanceTo(local: String) = advance(at(local) - wall)

    private fun start(settings: ScheduleSettings, state: ScheduleState = ScheduleState()) {
        m = ScheduleMachine(settings, state)
        events.clear()
        dispatches.clear()
    }
    /** Runs one machine call; a dispatch is committed [COMMIT_MS] later while [autoCommit] is on. */
    private fun act(call: () -> ScheduleEffects): ScheduleEffects {
        val effects = call()
        events += m.drainEvents()
        effects.dispatch?.let { d ->
            dispatches += d
            if (autoCommit) {
                advance(COMMIT_MS)
                m.onCommitted(d.seq, 12, now())
                events += m.drainEvents()
            }
        }
        return effects
    }

    private fun visible(on: Boolean) = act { m.onVisibility(on, now()) }
    private fun evaluate() = act { m.evaluate(now()) }
    private fun stats() = events.fold(ScheduleStats()) { s, e -> s.record(e) }
    private fun kinds(kind: String) = events.filter { it.kind == kind }
    private fun lastDispatch() = kinds(EventKind.DISPATCH).last()
    private fun minutes(hhmm: String) = LocalTime.parse(hhmm).let { it.hour * 60 + it.minute }
    private fun daily(hhmm: String, id: String = "d1") = DailyTime(id, minutes(hhmm), editedAtWall = wall)
    private fun rule(id: String, from: String, to: String) =
        PlaybackRule(id, name = "午间", sourceIds = listOf("s1"), windowStart = minutes(from), windowEnd = minutes(to))
    private fun every(seconds: Int, clock: IntervalClock = IntervalClock.VISIBLE) =
        IntervalSettings(enabled = true, value = seconds, unit = IntervalUnit.SECONDS, clock = clock)

    // Device sequences recorded on the target (P0 T08): the Engine goes invisible first and the
    // SCREEN_OFF broadcast lands about 0.8 s later; the wallpaper is visible on the keyguard.
    private fun powerOff() {
        interactive = false
        visible(false)
        advance(800)
        locked = true
        act { m.onScreenOff(now()) }
    }

    private fun powerOn() {
        interactive = true
        act { m.onScreenOn(now()) }
        advance(100)
        visible(true)
    }

    private fun unlock() {
        locked = false
        act { m.onUserPresent(now()) }
    }

    /** Screen off, keyguard shown for a while, then unlock. */
    private fun lockCycle() {
        powerOff()
        advance(10_000)
        powerOn()
        advance(7_000)
        unlock()
        advance(20_000)
    }
    // ---- Interval ----------------------------------------------------------------------------

    @Test
    fun visibleIntervalCountsOnlyVisibleTime() {
        start(ScheduleSettings(interval = every(10)))
        visible(true)
        advance(6_000)
        visible(false)
        advance(60_000)
        assertNull(visible(true).dispatch)
        advance(3_999)
        assertNull(evaluate().dispatch)
        advance(1)
        assertEquals(setOf(INTERVAL), evaluate().dispatch!!.reasons)
        assertEquals(mapOf(INTERVAL to 0L), lastDispatch().late)
        assertNull(lastDispatch().mergeMs)
    }

    @Test
    fun wallIntervalDueWhileHiddenSwitchesOnceAtTheNextVisible() {
        start(ScheduleSettings(interval = every(10, IntervalClock.WALL)))
        visible(true)
        advance(2_000)
        visible(false)
        advance(60_000)
        assertNull(evaluate().dispatch)
        advance(60_000)
        assertNull(evaluate().dispatch)
        assertEquals(setOf(INTERVAL), m.state.pending)
        assertEquals(1, kinds(EventKind.PENDING).size)
        assertEquals(setOf(INTERVAL), visible(true).dispatch!!.reasons)
        assertEquals(0L, lastDispatch().mergeMs)
        assertTrue(lastDispatch().late.isEmpty())
        advance(1_000)
        assertNull(evaluate().dispatch)
        assertEquals(1, dispatches.size)
        assertEquals(listOf(0L), stats().merge)
    }

    @Test
    fun anyCommitRestartsTheInterval() {
        start(ScheduleSettings(interval = every(10)))
        visible(true)
        advance(8_000)
        act { m.onManualCommit(now(), "双击") }
        advance(9_999)
        assertNull(evaluate().dispatch)
        advance(1)
        assertEquals(setOf(INTERVAL), evaluate().dispatch!!.reasons)
    }
    @Test
    fun shorteningTheIntervalRestartsItInsteadOfFiringAtOnce() {
        start(ScheduleSettings(interval = every(30)))
        visible(true)
        advance(15_000)
        assertNull(act { m.updateSettings(m.settings.copy(interval = every(10)), now()) }.dispatch)
        advance(9_999)
        assertNull(evaluate().dispatch)
        advance(1)
        assertEquals(setOf(INTERVAL), evaluate().dispatch!!.reasons)
    }

    @Test
    fun intervalTicksWhileEditingAreDroppedAndTheEditEndRestartsTheInterval() {
        autoCommit = false
        start(ScheduleSettings(interval = every(10)))
        visible(true)
        repeat(3) {
            advance(10_000)
            val tick = evaluate().dispatch!!
            assertTrue(tick.intervalOnly)
            assertNull(act { m.onOutcome(tick.seq, SwitchOutcome.DROPPED_EDIT, now()) }.dispatch)
        }
        advance(4_000)
        assertNull(act { m.onEditEnded(now()) }.dispatch)
        advance(9_999)
        assertNull(evaluate().dispatch)
        advance(1)
        assertNotNull(evaluate().dispatch)
        assertTrue(kinds(EventKind.COMMIT).isEmpty())
        assertTrue(kinds(EventKind.RETRY).isEmpty())
    }

    @Test
    fun switchHeldByTheEditorCommitsAfterTheEditWithoutACommitSample() {
        autoCommit = false
        start(ScheduleSettings(dailyTimes = listOf(daily("12:01"))))
        visible(true)
        advance(60_000)
        val held = evaluate().dispatch!!
        assertEquals(setOf(DAILY), held.reasons)
        assertNull(act { m.onOutcome(held.seq, SwitchOutcome.HELD_EDIT, now()) }.dispatch)
        advance(20_000)
        act { m.onEditEnded(now()) }
        act { m.onCommitted(held.seq, 12, now()) }
        val commit = kinds(EventKind.COMMIT).single()
        assertNull(commit.commitMs)
        assertEquals(listOf(DAILY), commit.reasons)
        assertTrue(stats().commitNormal.isEmpty())
    }
    // ---- Lock cycle (T3) -----------------------------------------------------------------------

    @Test
    fun everyLockTriggerCombinationSwitchesExactlyOncePerCycle() {
        val cases = mapOf(
            ScheduleSettings(onLock = true) to setOf(LOCK),
            ScheduleSettings(onScreenOff = true) to setOf(SCREEN_OFF),
            ScheduleSettings(onUnlock = true) to setOf(UNLOCK),
            ScheduleSettings(onLock = true, onUnlock = true) to setOf(LOCK),
            ScheduleSettings(onLock = true, onScreenOff = true, onUnlock = true) to setOf(LOCK, SCREEN_OFF),
        )
        for ((settings, reasons) in cases) {
            start(settings)
            visible(true)
            advance(5_000)
            repeat(3) { lockCycle() }
            assertEquals("$settings", List(3) { reasons }, dispatches.map { it.reasons })
            assertEquals("$settings", mapOf(1L to 1, 2L to 1, 3L to 1), stats().cycleCommits)
        }
    }

    @Test
    fun allTriggersOffGivesNoSwitchInAnyCycle() {
        start(ScheduleSettings())
        visible(true)
        repeat(3) { lockCycle() }
        assertTrue(dispatches.isEmpty())
        assertEquals(mapOf(1L to 0, 2L to 0, 3L to 0), stats().cycleCommits)
    }

    @Test
    fun rapidDoublePressSwitchesOnceAndIgnoresTheLateScreenOff() {
        start(ScheduleSettings(onScreenOff = true, onUnlock = true))
        visible(true)
        advance(5_000)
        interactive = false
        visible(false)
        advance(150)
        interactive = true
        visible(true)
        advance(250)
        act { m.onScreenOff(now()) }
        advance(100)
        act { m.onScreenOn(now()) }
        act { m.onUserPresent(now()) }
        assertEquals(listOf(setOf(SCREEN_OFF)), dispatches.map { it.reasons })
        assertTrue(kinds(EventKind.SCREEN_OFF).single().detail.contains("迟到"))
        assertEquals(mapOf(1L to 1), stats().cycleCommits)
    }
    @Test
    fun keyguardShownTwiceBeforeTheUnlockIsOneCycle() {
        start(ScheduleSettings(onLock = true, onScreenOff = true, onUnlock = true))
        visible(true)
        advance(5_000)
        powerOff()
        advance(5_000)
        powerOn()
        advance(3_000)
        powerOff()
        advance(5_000)
        powerOn()
        advance(4_000)
        unlock()
        assertEquals(1, dispatches.size)
        assertEquals(1, kinds(EventKind.CYCLE_OPEN).size)
        assertEquals("本周期已切换", kinds(EventKind.SKIP_UNLOCK).single().detail)
        assertEquals(mapOf(1L to 1), stats().cycleCommits)
    }

    @Test
    fun intervalDueWhileTheScreenIsOffUsesTheCycleSlot() {
        start(ScheduleSettings(interval = every(10, IntervalClock.WALL), onUnlock = true))
        visible(true)
        advance(5_000)
        powerOff()
        advance(60_000)
        powerOn()
        assertEquals(listOf(setOf(INTERVAL)), dispatches.map { it.reasons })
        assertEquals(1L, lastDispatch().cycle)
        advance(3_000)
        unlock()
        assertEquals(1, dispatches.size)
        assertEquals(mapOf(1L to 1), stats().cycleCommits)
    }

    @Test
    fun laterIntervalDuringALongKeyguardStayIsNotARepeat() {
        start(ScheduleSettings(interval = every(10, IntervalClock.WALL), onLock = true))
        visible(true)
        advance(3_000)
        powerOff()
        advance(5_000)
        powerOn()
        assertEquals(setOf(LOCK), dispatches.single().reasons)
        advance(9_850)
        assertNull(evaluate().dispatch)
        advance(150)
        assertEquals(setOf(INTERVAL), evaluate().dispatch!!.reasons)
        assertNull(lastDispatch().cycle)
        assertTrue(lastDispatch().detail.contains("名额已用"))
        advance(2_000)
        unlock()
        assertEquals(2, dispatches.size)
        assertEquals(mapOf(1L to 1), stats().cycleCommits)
    }
    @Test
    fun doublePressThatReachesTheKeyguardStillSwitchesOnce() {
        start(ScheduleSettings(onScreenOff = true, onUnlock = true))
        visible(true)
        advance(5_000)
        interactive = false
        visible(false)
        advance(150)
        interactive = true
        locked = true
        visible(true)
        advance(250)
        act { m.onScreenOff(now()) }
        act { m.onScreenOn(now()) }
        advance(5_000)
        unlock()
        assertEquals(listOf(setOf(SCREEN_OFF)), dispatches.map { it.reasons })
        assertEquals(1, kinds(EventKind.CYCLE_OPEN).size)
        assertEquals(mapOf(1L to 1), stats().cycleCommits)
    }

    @Test
    fun unlockWithinThreeSecondsOfASwitchIsSkipped() {
        start(ScheduleSettings(onUnlock = true))
        visible(true)
        advance(10_000)
        act { m.onManualCommit(now(), "双击") }
        advance(1_000)
        assertNull(act { m.onUserPresent(now()) }.dispatch)
        assertEquals("距上次派发或提交不足 3 秒", kinds(EventKind.SKIP_UNLOCK).single().detail)
        advance(2_500)
        assertEquals(setOf(UNLOCK), act { m.onUserPresent(now()) }.dispatch!!.reasons)
    }

    @Test
    fun manualSwitchOnTheKeyguardUsesUpTheCycle() {
        start(ScheduleSettings(onUnlock = true))
        visible(true)
        advance(5_000)
        powerOff()
        advance(5_000)
        powerOn()
        advance(2_000)
        act { m.onManualCommit(now(), "双击") }
        advance(5_000)
        unlock()
        assertTrue(dispatches.isEmpty())
        assertEquals("本周期已切换", kinds(EventKind.SKIP_UNLOCK).single().detail)
        assertEquals(1L, kinds(EventKind.MANUAL).single().cycle)
        assertEquals(mapOf(1L to 0), stats().cycleCommits)
    }
    @Test
    fun lockTriggerNeedsAConfirmedKeyguard() {
        start(ScheduleSettings(onLock = true))
        visible(true)
        advance(5_000)
        interactive = false
        visible(false)
        advance(800)
        act { m.onScreenOff(now()) }
        advance(1_500)
        interactive = true
        act { m.onScreenOn(now()) }
        advance(100)
        visible(true)
        assertTrue(dispatches.isEmpty())
        assertFalse(m.state.awayOpen)
        assertEquals(mapOf(1L to 0), stats().cycleCommits)
    }

    // ---- Pause -------------------------------------------------------------------------------

    @Test
    fun pauseAllDispatchesNothingButStillTracksRules() {
        val settings = ScheduleSettings(
            pauseAll = true, interval = every(10), onUnlock = true, rules = listOf(rule("r1", "12:01", "13:00")),
        )
        start(settings)
        visible(true)
        advance(20_000)
        assertNull(evaluate().dispatch)
        assertNull(act { m.onUserPresent(now()) }.dispatch)
        assertEquals(listOf(UNLOCK), kinds(EventKind.SKIP_PAUSED).single().reasons)
        advanceTo("2026-10-02T12:01:00")
        val effects = evaluate()
        assertNull(effects.dispatch)
        assertTrue(effects.refreshCatalog)
        assertNull(act { m.updateSettings(settings.copy(pauseAll = false), now()) }.dispatch)
        assertTrue(dispatches.isEmpty())
        assertTrue(m.state.pending.isEmpty())
    }
    // ---- Daily times, clock and DST -------------------------------------------------------------

    @Test
    fun dailyTimeFiresOncePerLocalDate() {
        start(ScheduleSettings(dailyTimes = listOf(daily("12:01"))))
        visible(true)
        advanceTo("2026-10-02T12:01:00")
        assertEquals(setOf(DAILY), evaluate().dispatch!!.reasons)
        assertEquals(mapOf(DAILY to 0L), lastDispatch().late)
        advance(1_000)
        assertNull(evaluate().dispatch)
        advanceTo("2026-10-03T12:00:59.600")
        assertNull(evaluate().dispatch)
        advance(800)
        assertNotNull(evaluate().dispatch)
        assertEquals(mapOf(DAILY to 400L), lastDispatch().late)
        assertEquals(2, dispatches.size)
    }

    @Test
    fun dailyTimeSavedAfterItsMomentWaitsForTomorrowAndAnEditedTimeFiresTheSameDay() {
        start(ScheduleSettings(dailyTimes = listOf(daily("11:00"))))
        assertNull(visible(true).dispatch)
        advanceTo("2026-10-03T10:59:59")
        assertNull(evaluate().dispatch)
        advance(1_000)
        assertEquals(setOf(DAILY), evaluate().dispatch!!.reasons)
        advance(60_000)
        val edited = m.settings.dailyTimes.single().copy(minuteOfDay = minutes("11:05"), editedAtWall = wall)
        assertNull(act { m.updateSettings(m.settings.copy(dailyTimes = listOf(edited)), now()) }.dispatch)
        advanceTo("2026-10-03T11:05:00")
        assertNotNull(evaluate().dispatch)
        assertEquals(2, dispatches.size)
    }

    @Test
    fun clockJumpForwardAcrossSeveralDailyTimesSwitchesOnceWithoutASample() {
        start(ScheduleSettings(dailyTimes = listOf(daily("13:00", "a"), daily("14:00", "b"), daily("15:00", "c"))))
        visible(true)
        advance(5_000)
        wall = at("2026-10-02T15:30:00")
        val jump = act { m.onClockChanged(now(), "TIME_SET") }.dispatch!!
        assertEquals(setOf(DAILY), jump.reasons)
        assertTrue(lastDispatch().late.isEmpty())
        assertNull(evaluate().dispatch)
        assertEquals(1, dispatches.size)
        assertTrue(stats().dueLate.isEmpty())
    }
    @Test
    fun clockMovedBackDoesNotFireADailyTimeAgain() {
        start(ScheduleSettings(dailyTimes = listOf(daily("12:01"))))
        visible(true)
        advanceTo("2026-10-02T12:01:00")
        assertNotNull(evaluate().dispatch)
        advance(60_000)
        wall = at("2026-10-01T12:30:00")
        assertNull(act { m.onClockChanged(now(), "TIME_SET") }.dispatch)
        assertTrue(kinds(EventKind.CLOCK).any { it.detail.contains("墙钟倒退") })
        advanceTo("2026-10-02T12:01:00")
        assertNull(evaluate().dispatch)
        advanceTo("2026-10-03T12:01:00")
        assertNotNull(evaluate().dispatch)
        assertEquals(2, dispatches.size)
    }

    @Test
    fun dailyTimeInsideTheSpringForwardGapFiresOnceAtTheTransition() {
        zone = newYork
        wall = at("2026-03-08T01:59:00")
        start(ScheduleSettings(dailyTimes = listOf(daily("02:30"))))
        visible(true)
        wall = at("2026-03-08T03:00:00")
        elapsed += 60_000
        assertEquals(setOf(DAILY), evaluate().dispatch!!.reasons)
        assertEquals(mapOf(DAILY to 0L), lastDispatch().late)
        advance(3_600_000)
        assertNull(evaluate().dispatch)
        assertEquals(1, dispatches.size)
    }

    @Test
    fun dailyTimeInsideTheFallBackOverlapFiresOnlyForTheFirstPass() {
        zone = newYork
        wall = at("2026-11-01T01:00:00")
        start(ScheduleSettings(dailyTimes = listOf(daily("01:30"))))
        visible(true)
        advance(30 * 60_000L)
        assertEquals(setOf(DAILY), evaluate().dispatch!!.reasons)
        assertEquals(mapOf(DAILY to 0L), lastDispatch().late)
        advance(3_600_000)
        // The second 01:30, now in EST.
        assertEquals(ZoneOffset.ofHours(-5), zone.rules.getOffset(Instant.ofEpochMilli(wall)))
        assertNull(evaluate().dispatch)
        assertEquals(1, dispatches.size)
    }
    // ---- Rules (T5) ----------------------------------------------------------------------------

    @Test
    fun ruleWindowEdgesRefreshTheCatalogAndDispatchAConditionalSwitch() {
        start(ScheduleSettings(rules = listOf(rule("r1", "12:01", "12:02"))))
        visible(true)
        advanceTo("2026-10-02T12:00:59.900")
        assertFalse(evaluate().refreshCatalog)
        advanceTo("2026-10-02T12:01:00.300")
        val enter = evaluate()
        assertTrue(enter.refreshCatalog)
        assertTrue(enter.dispatch!!.conditional)
        assertEquals(mapOf(RULE to 300L), lastDispatch().late)
        advanceTo("2026-10-02T12:02:01")
        val leave = evaluate()
        assertTrue(leave.refreshCatalog)
        assertTrue(leave.dispatch!!.conditional)
        assertEquals(listOf(300L, 1_000L), stats().ruleLate)
        assertEquals(listOf(300L, 1_000L), stats().dueLate)
    }

    @Test
    fun ruleAddedInSettingsIsNotABoundarySample() {
        start(ScheduleSettings())
        visible(true)
        advance(5_000)
        val effects = act { m.updateSettings(ScheduleSettings(rules = listOf(rule("r1", "11:00", "13:00"))), now()) }
        assertTrue(effects.refreshCatalog)
        assertTrue(effects.dispatch!!.conditional)
        assertTrue(lastDispatch().late.isEmpty())
        assertNull(lastDispatch().mergeMs)
        assertTrue(stats().ruleLate.isEmpty())
    }

    @Test
    fun keptRuleSwitchDoesNotRestartTheInterval() {
        autoCommit = false
        start(ScheduleSettings(interval = every(10)))
        visible(true)
        advance(6_000)
        val keep = act { m.updateSettings(m.settings.copy(rules = listOf(rule("r1", "11:00", "13:00"))), now()) }.dispatch!!
        assertNull(act { m.onOutcome(keep.seq, SwitchOutcome.RULE_KEEP, now()) }.dispatch)
        advance(3_999)
        assertNull(evaluate().dispatch)
        advance(1)
        assertEquals(setOf(INTERVAL), evaluate().dispatch!!.reasons)
    }
    // ---- Outcomes and retries ------------------------------------------------------------------

    @Test
    fun retryableOutcomeIsRetriedAtMostTwiceWhileVisible() {
        autoCommit = false
        start(ScheduleSettings(onUnlock = true))
        visible(true)
        advance(5_000)
        var d = act { m.onUserPresent(now()) }.dispatch!!
        repeat(ScheduleMachine.MAX_RETRIES) {
            advance(100)
            d = act { m.onOutcome(d.seq, SwitchOutcome.CANCELLED, now(), "目录刷新") }.dispatch!!
            assertEquals(setOf(UNLOCK), d.reasons)
        }
        advance(100)
        assertNull(act { m.onOutcome(d.seq, SwitchOutcome.STALE, now()) }.dispatch)
        assertTrue(kinds(EventKind.RETRY).last().detail.contains("放弃"))
        assertEquals(3, dispatches.size)
    }

    @Test
    fun switchCancelledBecauseTheEngineWentInvisibleWaitsForTheNextVisible() {
        autoCommit = false
        start(ScheduleSettings(onUnlock = true))
        visible(true)
        advance(5_000)
        val d = act { m.onUserPresent(now()) }.dispatch!!
        visible(false)
        assertNull(act { m.onOutcome(d.seq, SwitchOutcome.CANCELLED, now()) }.dispatch)
        assertEquals(setOf(UNLOCK), m.state.pending)
        assertEquals("等待下一次可见", kinds(EventKind.RETRY).single().detail)
        advance(30_000)
        assertEquals(setOf(UNLOCK), visible(true).dispatch!!.reasons)
        assertTrue(m.state.pending.isEmpty())
    }

    @Test
    fun unknownOrFinishedSeqIsIgnored() {
        start(ScheduleSettings(interval = every(10)))
        visible(true)
        advance(8_000)
        assertEquals(ScheduleEffects.NONE, act { m.onCommitted(99, 1, now()) })
        assertEquals(ScheduleEffects.NONE, act { m.onOutcome(99, SwitchOutcome.CANCELLED, now()) })
        assertNull(m.state.lastCommitElapsed)
        assertEquals(2_000L, m.intervalRemaining(now()))
        assertTrue(kinds(EventKind.OUTCOME).single().detail.contains("未知"))
    }
    // ---- Reboot and process death ---------------------------------------------------------------

    @Test
    fun rebootEndsTheLockCycleAndCarriesTheWallInterval() {
        start(ScheduleSettings(interval = every(40, IntervalClock.WALL), onUnlock = true))
        visible(true)
        advance(5_000)
        act { m.onManualCommit(now(), "双击") }
        advance(3_000)
        powerOff()
        val saved = m.state
        assertTrue(saved.awayOpen)
        // New boot: the monotonic clock restarts, wall time goes on.
        bootCount = 6
        elapsed = 2_000L
        wall += 15_000
        interactive = true
        start(m.settings, saved)
        visible(true)
        val boot = kinds(EventKind.BOOT).single()
        assertTrue(boot.detail.contains("重启"))
        assertFalse(m.state.awayOpen)
        assertNull(m.state.lastCommitElapsed)
        assertEquals(40_000L - 800L - 15_000L - 3_000L, m.intervalRemaining(now()))
    }

    @Test
    fun rebootIsDetectedFromTheMonotonicClockWhenBootCountIsUnreadable() {
        bootCount = -1
        start(ScheduleSettings(interval = every(10)))
        visible(true)
        advance(5_000)
        act { m.onManualCommit(now(), "双击") }
        val saved = m.state
        elapsed = 1_000L
        start(m.settings, saved)
        visible(true)
        assertTrue(kinds(EventKind.BOOT).single().detail.contains("重启"))
        assertNull(m.state.lastCommitElapsed)
    }

    @Test
    fun processDeathInTheSameBootResumesTheVisibleInterval() {
        start(ScheduleSettings(interval = every(10)))
        visible(true)
        advance(6_000)
        visible(false)
        val saved = m.state
        advance(60_000)
        start(m.settings, saved)
        visible(true)
        assertTrue(kinds(EventKind.BOOT).single().detail.contains("调度开始"))
        assertEquals(4_000L, m.intervalRemaining(now()))
        advance(4_000)
        assertEquals(setOf(INTERVAL), evaluate().dispatch!!.reasons)
    }
    // ---- Timer, merging and status -------------------------------------------------------------

    @Test
    fun nextCheckFollowsTheNearestDueAndPollsBrieflyAfterGoingInvisible() {
        start(ScheduleSettings(interval = every(10)))
        assertNull(m.nextCheckDelay(now()))
        visible(true)
        assertEquals(10_000L, m.nextCheckDelay(now()))
        advance(9_950)
        assertEquals(ScheduleMachine.MIN_CHECK_MILLIS, m.nextCheckDelay(now()))
        visible(false)
        assertEquals(ScheduleMachine.FALLBACK_POLL_MILLIS, m.nextCheckDelay(now()))
        advance(10_001)
        assertNull(m.nextCheckDelay(now()))
        // A missed SCREEN_OFF: invisible and non-interactive opens the cycle by itself.
        interactive = false
        evaluate()
        assertTrue(m.state.awayOpen)
        assertEquals("不可见且非交互", kinds(EventKind.CYCLE_OPEN).single().detail)
        assertNull(m.nextCheckDelay(now()))
    }

    @Test
    fun coincidentIntervalAndDailyTimeMergeIntoOneDispatch() {
        start(ScheduleSettings(interval = every(60), dailyTimes = listOf(daily("12:01"))))
        visible(true)
        advance(60_000)
        assertEquals(setOf(INTERVAL, DAILY), evaluate().dispatch!!.reasons)
        assertEquals(mapOf(INTERVAL to 0L, DAILY to 0L), lastDispatch().late)
        assertEquals(1, dispatches.size)
    }

    @Test
    fun triggerSwitchedOffWhilePendingNoLongerFires() {
        start(ScheduleSettings(interval = every(10, IntervalClock.WALL)))
        visible(true)
        visible(false)
        advance(30_000)
        evaluate()
        assertEquals(setOf(INTERVAL), m.state.pending)
        act { m.updateSettings(ScheduleSettings(), now()) }
        assertTrue(m.state.pending.isEmpty())
        assertNull(visible(true).dispatch)
    }

    @Test
    fun statusShowsTheOpenCycleAndThePendingLockSwitch() {
        start(ScheduleSettings(onLock = true))
        visible(true)
        advance(5_000)
        powerOff()
        val lines = m.statusLines(now())
        assertTrue(lines.any { it == "锁屏周期：进行中（第 1 个，已锁定，未切换）" })
        assertTrue(lines.any { it == "待办：锁屏" })
        assertTrue(lines.any { it.startsWith("壁纸可见：否") })
    }

    private companion object {
        const val COMMIT_MS = 150L
    }
}