package dev.abdus.apps.shimmer.p4

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.time.ZoneId

/** Coordinator plumbing: executor choice, delivery, store listener, receivers and timer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleCoordinatorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val preferences = app.getSharedPreferences(ScheduleStore.NAME, Context.MODE_PRIVATE)
    private val store = ScheduleStore(app)
    private val env = FakeEnv()

    private class FakeEnv : ScheduleEnvironment {
        var elapsed = 5_000_000L
        var wall = 1_790_000_000_000L
        var interactive = true
        var locked = false
        override fun now() = ScheduleNow(elapsed, wall, ZoneId.of("Asia/Shanghai"), 2, interactive, locked)
        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    private class FakeTarget : ScheduleTarget {
        val switches = mutableListOf<ScheduleDispatch>()
        var catalogChanges = 0
        override fun onScheduledSwitch(dispatch: ScheduleDispatch) {
            switches += dispatch
        }
        override fun onScheduleCatalogChanged() {
            catalogChanges++
        }
    }

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }
    @Test
    fun theMostRecentlyShownVisibleEngineExecutesAndItsCommitIsRecorded() {
        store.saveSettings(ScheduleSettings(onUnlock = true))
        val coordinator = ScheduleCoordinator(app, env)
        val first = FakeTarget()
        val second = FakeTarget()
        coordinator.register("a", first)
        coordinator.register("b", second)
        coordinator.onVisibility("a", true)
        coordinator.onVisibility("b", true)
        env.advance(10_000)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        assertTrue(first.switches.isEmpty())
        val dispatch = second.switches.single()
        assertEquals(setOf(ScheduleReason.UNLOCK), dispatch.reasons)

        env.advance(120)
        coordinator.onCommitted("b", dispatch.seq, 12)
        assertEquals(listOf(120L), store.loadStats().commitNormal)
        assertEquals(dispatch.seq, store.loadState().seq)
    }

    @Test
    fun switchWithNoVisibleEngineAtDeliveryWaitsForTheNextVisible() {
        val coordinator = ScheduleCoordinator(app, env)
        val target = FakeTarget()
        coordinator.register("a", target)
        coordinator.onVisibility("a", true)
        idle()
        env.advance(5_000)
        // Saved from the P4 page: the store listener reaches the machine at once.
        store.saveSettings(ScheduleSettings(rules = listOf(ALL_DAY)))
        coordinator.onVisibility("a", false)
        idle()
        assertTrue(target.switches.isEmpty())
        assertEquals(1, target.catalogChanges)
        assertTrue(store.loadLog().any { it.kind == EventKind.OUTCOME && it.detail.contains("没有可见的正式 Engine") })
        assertEquals(setOf(ScheduleReason.RULE), store.loadState().pending)

        env.advance(10_000)
        coordinator.onVisibility("a", true)
        idle()
        assertEquals(setOf(ScheduleReason.RULE), target.switches.single().reasons)
    }

    @Test
    fun callsForUnregisteredEnginesAreIgnored() {
        val coordinator = ScheduleCoordinator(app, env)
        coordinator.onVisibility("ghost", true)
        coordinator.onManualCommit("ghost", "双击")
        coordinator.onCommitted("ghost", 1, 1)
        coordinator.onOutcome("ghost", 1, SwitchOutcome.CANCELLED)
        coordinator.onEditEnded("ghost")
        coordinator.unregister("ghost")
        val status = coordinator.statusLines()
        assertTrue(status.contains("已注册正式 Engine：0（可见 0）"))
        assertTrue(status.any { it.startsWith("壁纸可见：否") })
        assertTrue(store.loadLog().none { it.kind == EventKind.MANUAL })
    }
    @Test
    fun screenAndClockReceiversExistOnlyWhileAnEngineIsRegistered() {
        val coordinator = ScheduleCoordinator(app, env)
        assertEquals(0, scheduleReceivers())
        coordinator.register("a", FakeTarget())
        coordinator.register("b", FakeTarget())
        assertEquals(1, scheduleReceivers())
        val filter = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) }.intentFilter
        listOf(
            Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        ).forEach { assertTrue(it, filter.hasAction(it)) }
        coordinator.unregister("a")
        assertEquals(1, scheduleReceivers())
        coordinator.unregister("b")
        assertEquals(0, scheduleReceivers())
    }

    @Test
    fun theTimerRunsOnlyWhileAWallpaperIsVisible() {
        store.saveSettings(ScheduleSettings(interval = IntervalSettings(enabled = true, value = 10, unit = IntervalUnit.SECONDS)))
        val coordinator = ScheduleCoordinator(app, env)
        val target = FakeTarget()
        coordinator.register("a", target)
        assertTrue(coordinator.statusLines().contains("下次检查：无（壁纸不可见时不计时）"))
        coordinator.onVisibility("a", true)
        assertTrue(coordinator.statusLines().contains("下次检查：约 10 秒后"))
        advance(9_000)
        assertTrue(target.switches.isEmpty())
        advance(1_000)
        val dispatch = target.switches.single()
        assertEquals(setOf(ScheduleReason.INTERVAL), dispatch.reasons)
        coordinator.onCommitted("a", dispatch.seq, 12)

        coordinator.onVisibility("a", false)
        // Hidden but interactive: the short fallback poll runs for its window, then stops.
        advance(60_000)
        assertEquals(1, target.switches.size)
        assertTrue(coordinator.statusLines().contains("下次检查：无（壁纸不可见时不计时）"))
    }

    @Test
    fun diagnosticsTextHasItsSectionsAndClearingKeepsTheSettings() {
        val settings = ScheduleSettings(onUnlock = true)
        store.saveSettings(settings)
        val coordinator = ScheduleCoordinator(app, env)
        val target = FakeTarget()
        coordinator.register("a", target)
        coordinator.onVisibility("a", true)
        env.advance(10_000)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        env.advance(200)
        coordinator.onCommitted("a", target.switches.single().seq, 12)

        val text = coordinator.diagnosticsText()
        assertTrue(text.startsWith("调度：运行中"))
        val (status, rest) = text.split("\n\n【统计】\n", limit = 2)
        assertTrue(status.contains("已注册正式 Engine：1（可见 1）"))
        val (report, events) = rest.split("\n\n【最近事件】\n", limit = 2)
        assertTrue(report.contains("T2 派发到提交（≤ 500 张）：n=1，p50=200，p95=200，最大=200 ms"))
        assertTrue(events.lines().any { it.contains("DISPATCH #1") && it.contains("UNLOCK") })
        assertTrue(events.lines().any { it.contains("COMMIT #1") && it.contains("提交=200ms") })

        coordinator.clearDiagnostics()
        assertEquals(settings, store.loadSettings())
        assertEquals(1L, store.loadState().seq)
        assertTrue(store.loadLog().isEmpty())
        assertTrue(store.loadStats().commitNormal.isEmpty())
        assertTrue(coordinator.diagnosticsText().contains("T2 派发到提交（≤ 500 张）：无样本"))
    }

    @Test
    fun aRuleChangeRefreshesEveryRegisteredEngineIncludingHiddenOnes() {
        val coordinator = ScheduleCoordinator(app, env)
        val shown = FakeTarget()
        val hidden = FakeTarget()
        coordinator.register("shown", shown)
        coordinator.register("hidden", hidden)
        coordinator.onVisibility("shown", true)
        idle()
        env.advance(5_000)
        store.saveSettings(ScheduleSettings(rules = listOf(ALL_DAY)))
        idle()
        assertEquals(1, shown.catalogChanges)
        assertEquals(1, hidden.catalogChanges)
        val dispatch = shown.switches.single()
        assertEquals(setOf(ScheduleReason.RULE), dispatch.reasons)
        assertTrue(hidden.switches.isEmpty())
        coordinator.onOutcome("shown", dispatch.seq, SwitchOutcome.RULE_KEEP)

        // A refresh posted before an Engine goes away never reaches it.
        env.advance(5_000)
        store.saveSettings(ScheduleSettings())
        coordinator.unregister("hidden")
        idle()
        assertEquals(2, shown.catalogChanges)
        assertEquals(1, hidden.catalogChanges)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Moves both clocks together in small steps so delayed ticks run at their own time. */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(STEP_MS, left)
            env.advance(step)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun scheduleReceivers(): Int =
        shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED) }

    private companion object {
        const val STEP_MS = 100L
        val ALL_DAY = PlaybackRule("r1", name = "全天", sourceIds = listOf("s1"))
    }
}