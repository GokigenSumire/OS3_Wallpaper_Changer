package dev.abdus.apps.shimmer.p10

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p4.EventKind
import dev.abdus.apps.shimmer.p4.ScheduleEvent
import dev.abdus.apps.shimmer.p4.ScheduleReason
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import dev.abdus.apps.shimmer.p4.ScheduleState
import dev.abdus.apps.shimmer.p4.ScheduleStats
import dev.abdus.apps.shimmer.p4.ScheduleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual preference files, not mocked maps: default HOME and new LOCK must never share a keyspace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetStateIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetOnlyTheTestApplicationPreferenceFiles() {
        WallpaperTarget.entries.forEach { target ->
            prefs(PlaybackSnapshotStore.nameFor(target)).edit().clear().commit()
            prefs(PlaybackSnapshotStore.manualNameFor(target)).edit().clear().commit()
            prefs(ScheduleStore.nameFor(target)).edit().clear().commit()
        }
    }

    @Test fun homeFileNamesAreExactlyTheExistingNames() {
        assertEquals("p2_playback_state", PlaybackSnapshotStore.nameFor(WallpaperTarget.HOME))
        assertEquals("p2_manual_orders_v2", PlaybackSnapshotStore.manualNameFor(WallpaperTarget.HOME))
        assertEquals("p4_schedule", ScheduleStore.nameFor(WallpaperTarget.HOME))
        assertEquals(WallpaperTarget.HOME, PlaybackSnapshotStore(context).target)
        assertEquals(WallpaperTarget.HOME, ScheduleStore(context).target)
        WallpaperTarget.entries.forEach { target ->
            assertNotEquals(PlaybackSnapshotStore.nameFor(target), PlaybackSnapshotStore.manualNameFor(target))
            assertNotEquals(PlaybackSnapshotStore.nameFor(target), ScheduleStore.nameFor(target))
        }
    }

    @Test fun firstLockReadIsEmptyAndDoesNotMigrateOrRewriteLegacyHome() {
        val raw = """{"order":"MANUAL","queueIds":["b","a"],"historyIds":["a"],"historyCursor":0,"currentId":"a"}"""
        val homePrefs = prefs(PlaybackSnapshotStore.NAME)
        homePrefs.edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, raw).commit()
        prefs(ScheduleStore.NAME).edit().putString(ScheduleStore.KEY_SETTINGS, """{"onLock":true}""").commit()
        val originalSchedule = prefs(ScheduleStore.NAME).all.toMap()

        assertNull(PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load())
        assertEquals(ScheduleSettings(), ScheduleStore(context, WallpaperTarget.LOCK).loadSettings())
        assertEquals(raw, homePrefs.getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        assertEquals(originalSchedule, prefs(ScheduleStore.NAME).all)
        assertEquals(listOf("b", "a"), PlaybackSnapshotStore(context).load()?.queueIds)
        assertEquals(raw, homePrefs.getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
    }

    @Test fun eachDomainPersistsItsOwnCurrentHistoryRandomRoundAndResumePoints() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val homeState = PlaybackSnapshot(currentId = "same-uri", historyIds = listOf("h1", "same-uri"),
            historyCursor = 1, randomRound = 4, sourcePositions = mapOf("source" to "home-image"))
        val lockState = PlaybackSnapshot(order = PlaybackOrder.RANDOM, currentId = "same-uri",
            historyIds = listOf("l1", "l2", "same-uri"), historyCursor = 2, randomRound = 9,
            sourcePositions = mapOf("source" to "lock-image"))
        home.save(homeState)
        lock.save(lockState)

        assertEquals(homeState, PlaybackSnapshotStore(context).load())
        assertEquals(lockState, PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load())
    }

    @Test fun manualCopyOnWriteSlotsRemainIndependentAcrossRepeatedReorders() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val homeState = manual("h2", "h1")
        home.save(homeState)
        val homeRuntime = prefs(PlaybackSnapshotStore.NAME).all.toMap()
        val homeManual = prefs(PlaybackSnapshotStore.MANUAL_NAME).all.toMap()
        lock.save(manual("l1", "l2"))
        lock.save(manual("l2", "l1"))
        lock.save(manual("l3", "l2", "l1"))

        assertEquals(homeRuntime, prefs(PlaybackSnapshotStore.NAME).all)
        assertEquals(homeManual, prefs(PlaybackSnapshotStore.MANUAL_NAME).all)
        assertEquals(homeState, home.load())
        assertEquals(listOf("l3", "l2", "l1"), PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load()?.queueIds)
    }

    @Test fun sameImageCanHaveDifferentSavedAndInheritedTransforms() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val homeTransform = ImageTransform(offsetX = 0.2f)
        val lockTransform = ImageTransform(rotationDegrees = 90f)
        val state = PlaybackSnapshot(currentId = "shared-image", historyIds = listOf("shared-image"),
            historyCursor = 0, historyTransforms = listOf(ImageTransform(userScale = 2f)))
        home.save(state)
        lock.save(state)
        home.commitImageTransform("shared-image", homeTransform)
        lock.commitImageTransform("shared-image", lockTransform)

        assertEquals(homeTransform, home.loadImageTransform("shared-image"))
        assertEquals(lockTransform, lock.loadImageTransform("shared-image"))
        lock.commitImageTransform("shared-image", null)
        assertNull(lock.loadImageTransform("shared-image"))
        assertEquals(homeTransform, home.loadImageTransform("shared-image"))
    }

    @Test fun lockTransformWriteDoesNotRemoveHomeInheritedHistory() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val state = PlaybackSnapshot(currentId = "image", historyIds = listOf("image"),
            historyCursor = 0, historyTransforms = listOf(ImageTransform(userScale = 3f)))
        home.save(state)
        lock.save(state)
        lock.commitImageTransform("image", ImageTransform())

        assertEquals(state, home.load())
        assertTrue(lock.load()!!.historyTransforms.isEmpty())
    }

    @Test fun anotherDomainsCommitDoesNotInvalidateThisDomainsCompareAndSave() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val original = PlaybackSnapshot(currentId = "original")
        home.save(original)
        lock.save(original)
        lock.save(original.copy(currentId = "lock-new"))

        assertTrue(home.compareAndSave(original, original.copy(currentId = "home-new")))
        assertFalse(lock.compareAndSave(original, original.copy(currentId = "lock-stale")))
        assertEquals("home-new", home.load()?.currentId)
        assertEquals("lock-new", lock.load()?.currentId)
    }

    @Test fun clearHistoryAndResetManualOrderAreLocalToLock() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        home.save(manual("h2", "h1"))
        lock.save(manual("l2", "l1"))
        val originalHome = prefs(PlaybackSnapshotStore.NAME).all.toMap()
        lock.clearHistory()
        assertEquals(listOf("l2", "l1"), lock.load()?.queueIds)
        lock.clearHistory(resetManualOrder = true)
        assertEquals(originalHome, prefs(PlaybackSnapshotStore.NAME).all)
        assertEquals(listOf("h2", "h1"), home.load()?.queueIds)
        assertEquals(PlaybackOrder.NATURAL_ASC, lock.load()?.order)
    }

    @Test fun removingAResumePointDoesNotForgetTheSameSourceInAnotherDomain() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val state = PlaybackSnapshot(sourcePositions = mapOf("same-source" to "image"),
            resumeSourceIds = listOf("same-source"))
        home.save(state)
        lock.save(state)
        lock.forgetSource("same-source")
        assertEquals(state, home.load())
        assertTrue(lock.load()!!.sourcePositions.isEmpty())
        assertTrue(lock.load()!!.resumeSourceIds.isEmpty())
    }

    @Test fun corruptHomeIsNotOverwrittenWhenConfiguringLock() {
        prefs(PlaybackSnapshotStore.NAME).edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, "{corrupt-home").commit()
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        lock.save(PlaybackSnapshot(currentId = "lock"))
        assertEquals("lock", lock.load()?.currentId)
        assertEquals("{corrupt-home", prefs(PlaybackSnapshotStore.NAME).getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
    }

    @Test fun corruptLockRejectsCommitWithoutAffectingHome() {
        val home = PlaybackSnapshotStore(context)
        home.save(PlaybackSnapshot(currentId = "home"))
        val homeBefore = prefs(PlaybackSnapshotStore.NAME).all.toMap()
        val lockPrefs = prefs(PlaybackSnapshotStore.LOCK_NAME)
        lockPrefs.edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, "{corrupt-lock").commit()
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        assertFalse(lock.compareAndSave(null, PlaybackSnapshot(currentId = "replacement")))
        assertThrows(IllegalStateException::class.java) { lock.save(PlaybackSnapshot()) }
        assertEquals("{corrupt-lock", lockPrefs.getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        assertEquals(homeBefore, prefs(PlaybackSnapshotStore.NAME).all)
    }

    @Test fun clearingLockDoesNotClearHome() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        home.save(PlaybackSnapshot(currentId = "home"))
        lock.save(PlaybackSnapshot(currentId = "lock"))
        lock.clear()
        assertNull(lock.load())
        assertEquals("home", home.load()?.currentId)
    }

    @Test fun snapshotListenersDoNotReceiveTheOtherDomainsWrites() {
        val home = PlaybackSnapshotStore(context)
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val homeKeys = mutableListOf<String?>()
        val lockKeys = mutableListOf<String?>()
        val homeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> homeKeys += key }
        val lockListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> lockKeys += key }
        home.registerListener(homeListener)
        lock.registerListener(lockListener)
        try {
            lock.save(PlaybackSnapshot(currentId = "lock"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(homeKeys.isEmpty())
            assertEquals(listOf(PlaybackSnapshotStore.SNAPSHOT_KEY), lockKeys)
        } finally {
            home.unregisterListener(homeListener)
            lock.unregisterListener(lockListener)
        }
    }

    @Test fun independentSchedulesKeepSettingsDedupStateLogsAndStatisticsSeparate() {
        val home = ScheduleStore(context)
        val lock = ScheduleStore(context, WallpaperTarget.LOCK)
        home.saveSettings(ScheduleSettings(onUnlock = true))
        lock.saveSettings(ScheduleSettings(onLock = true))
        home.commitRun(ScheduleState(seq = 3, cycleId = 8), listOf(event(3)), 100)
        lock.commitRun(ScheduleState(seq = 7, cycleId = 12), listOf(event(7), event(8)), 200)

        assertEquals(ScheduleSettings(onUnlock = true), ScheduleStore(context).loadSettings())
        assertEquals(ScheduleSettings(onLock = true), ScheduleStore(context, WallpaperTarget.LOCK).loadSettings())
        assertEquals(3L, home.loadState().seq)
        assertEquals(7L, lock.loadState().seq)
        assertEquals(listOf(3L), home.loadLog().map { it.seq })
        assertEquals(listOf(7L, 8L), lock.loadLog().map { it.seq })
        assertEquals(1, home.loadStats().counts[EventKind.DISPATCH])
        assertEquals(2, lock.loadStats().counts[EventKind.DISPATCH])
    }

    @Test fun clearingLockScheduleDiagnosticsKeepsBothSettingsAndAllHomeData() {
        val home = ScheduleStore(context)
        val lock = ScheduleStore(context, WallpaperTarget.LOCK)
        home.saveSettings(ScheduleSettings(onUnlock = true))
        home.commitRun(ScheduleState(seq = 3), listOf(event(3)), 100)
        lock.saveSettings(ScheduleSettings(onLock = true))
        lock.commitRun(ScheduleState(seq = 7), listOf(event(7)), 200)
        val homeBefore = prefs(ScheduleStore.NAME).all.toMap()
        lock.clearDiagnostics(900)

        assertEquals(homeBefore, prefs(ScheduleStore.NAME).all)
        assertEquals(ScheduleSettings(onLock = true), lock.loadSettings())
        assertEquals(7L, lock.loadState().seq)
        assertEquals(ScheduleStats(sinceWall = 900), lock.loadStats())
        assertTrue(lock.loadLog().isEmpty())
    }

    @Test fun lockScheduleListenersAreIsolatedFromHomeSettingsChanges() {
        val home = ScheduleStore(context)
        val lock = ScheduleStore(context, WallpaperTarget.LOCK)
        val lockKeys = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> lockKeys += key }
        lock.registerListener(listener)
        try {
            home.saveSettings(ScheduleSettings(onUnlock = true))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(lockKeys.isEmpty())
            assertEquals(ScheduleSettings(), lock.loadSettings())
        } finally {
            lock.unregisterListener(listener)
        }
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun manual(vararg ids: String) = PlaybackSnapshot(order = PlaybackOrder.MANUAL,
        queueIds = ids.toList(), currentId = ids.first(), historyIds = ids.toList(), historyCursor = 0)
    private fun event(seq: Long) = ScheduleEvent(wall = seq, elapsed = seq,
        kind = EventKind.DISPATCH, reasons = listOf(ScheduleReason.INTERVAL), seq = seq)
}
