package dev.abdus.apps.shimmer.p4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(ScheduleStore.NAME, Context.MODE_PRIVATE)
    private val store = ScheduleStore(context)

    @Before
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun settingsAndStateRoundTrip() {
        val settings = ScheduleSettings(
            interval = IntervalSettings(enabled = true, value = 10, unit = IntervalUnit.SECONDS, clock = IntervalClock.WALL),
            onLock = true,
            dailyTimes = listOf(DailyTime("d1", 7 * 60 + 30, editedAtWall = 42L)),
            rules = listOf(
                PlaybackRule("r1", "周末", sourceIds = listOf("s1"), repeat = RuleRepeat.WEEKLY, weekdays = setOf(6, 7)),
                PlaybackRule("r2", sourceIds = listOf("s2"), repeat = RuleRepeat.YEARLY, startMonthDay = 1220, endMonthDay = 105),
            ),
        )
        store.saveSettings(settings)
        assertEquals(settings, store.loadSettings())

        val state = ScheduleState(
            bootCount = 3, pending = setOf(ScheduleReason.LOCK), pendingCycle = 4L, awayOpen = true,
            cycleId = 4L, dailyFired = mapOf("d1@450" to "2026-10-02"), anchorElapsed = 10L, seq = 9L,
        )
        store.commitRun(state, emptyList(), 1_000L)
        assertEquals(state, store.loadState())
        // A run without events writes no log and no statistics.
        assertTrue(store.loadLog().isEmpty())
        assertEquals(ScheduleStats(), store.loadStats())
    }

    @Test
    fun unreadableValuesFallBackToDefaultsWithSchedulingOff() {
        preferences.edit()
            .putString(ScheduleStore.KEY_SETTINGS, "{not json")
            .putString(ScheduleStore.KEY_STATE, "[]")
            .putString(ScheduleStore.KEY_LOG, "{}")
            .putString(ScheduleStore.KEY_STATS, "{\"dueLate\":\"x\"}")
            .commit()
        assertEquals(ScheduleSettings(), store.loadSettings())
        assertEquals(ScheduleState(), store.loadState())
        assertTrue(store.loadLog().isEmpty())
        assertEquals(ScheduleStats(), store.loadStats())
    }

    @Test
    fun fieldsFromANewerVersionAreIgnored() {
        preferences.edit()
            .putString(ScheduleStore.KEY_SETTINGS, "{\"onUnlock\":true,\"futureOption\":{\"a\":1}}")
            .commit()
        assertEquals(ScheduleSettings(onUnlock = true), store.loadSettings())
    }

    @Test
    fun logKeepsTheLatestEventsAndStatisticsCountEveryEvent() {
        val first = (1..40).map { event(it.toLong()) }
        val second = (41..70).map { event(it.toLong()) }
        store.commitRun(ScheduleState(), first, 5_000L)
        store.commitRun(ScheduleState(), second, 9_000L)

        val log = store.loadLog()
        assertEquals(ScheduleStore.LOG_CAP, log.size)
        assertEquals(11L, log.first().wall)
        assertEquals(70L, log.last().wall)
        val stats = store.loadStats()
        // The statistics start at the first recorded run and are not reset by later runs.
        assertEquals(5_000L, stats.sinceWall)
        assertEquals(70, stats.counts[EventKind.DISPATCH])
        assertEquals(70, stats.dueLate.size)
    }

    @Test
    fun clearDiagnosticsStartsANewMeasurementAndKeepsSettingsAndState() {
        val settings = ScheduleSettings(onUnlock = true)
        store.saveSettings(settings)
        val state = ScheduleState(cycleId = 7L, seq = 3L)
        store.commitRun(state, listOf(event(1L)), 1_000L)

        store.clearDiagnostics(8_000L)
        assertTrue(store.loadLog().isEmpty())
        assertEquals(ScheduleStats(sinceWall = 8_000L), store.loadStats())
        assertEquals(settings, store.loadSettings())
        assertEquals(state, store.loadState())

        store.commitRun(state, listOf(event(2L)), 9_000L)
        assertEquals(8_000L, store.loadStats().sinceWall)
    }

    private fun event(wall: Long) = ScheduleEvent(
        wall = wall, elapsed = wall, kind = EventKind.DISPATCH, reasons = listOf(ScheduleReason.INTERVAL),
        seq = wall, late = mapOf(ScheduleReason.INTERVAL to 5L),
    )
}
