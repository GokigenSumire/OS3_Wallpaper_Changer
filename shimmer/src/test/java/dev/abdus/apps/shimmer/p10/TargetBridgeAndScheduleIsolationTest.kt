package dev.abdus.apps.shimmer.p10

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p2.PlaybackSelectionBridge
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p4.EventKind
import dev.abdus.apps.shimmer.p4.PlaybackRule
import dev.abdus.apps.shimmer.p4.ScheduleCoordinator
import dev.abdus.apps.shimmer.p4.ScheduleDispatch
import dev.abdus.apps.shimmer.p4.ScheduleEnvironment
import dev.abdus.apps.shimmer.p4.ScheduleNow
import dev.abdus.apps.shimmer.p4.ScheduleReason
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import dev.abdus.apps.shimmer.p4.ScheduleStore
import dev.abdus.apps.shimmer.p4.ScheduleTarget
import dev.abdus.apps.shimmer.p4.SwitchOutcome
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The same image/source IDs may exist in both domains; Engine IDs remain process-unique. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TargetBridgeAndScheduleIsolationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val ids = listOf("p10-bridge-home-a", "p10-bridge-home-b", "p10-bridge-lock-a", "p10-bridge-lock-b")
    private val coordinators = mutableListOf<ScheduleCoordinator>()
    private val env = FakeEnvironment()

    private class SelectionReceiver(private val response: PlaybackSelectionBridge.Result) : PlaybackSelectionBridge.Target {
        val images = mutableListOf<String>()
        override fun select(itemId: String): PlaybackSelectionBridge.Result = response.also { images += itemId }
    }

    private class EditReceiver : LiveEditBridge.Target {
        override fun beginEdit(): LiveEditBridge.EditSession? = null
    }

    private class ScheduledReceiver : ScheduleTarget {
        val switches = mutableListOf<ScheduleDispatch>()
        var refreshes = 0
        override fun onScheduledSwitch(dispatch: ScheduleDispatch) { switches += dispatch }
        override fun onScheduleCatalogChanged() { refreshes++ }
    }

    private class FakeEnvironment : ScheduleEnvironment {
        var elapsed = 5_000_000L
        var wall = 1_790_000_000_000L
        override fun now() = ScheduleNow(elapsed, wall, ZoneId.of("Asia/Shanghai"), 2, true, false)
        fun advance(ms: Long) { elapsed += ms; wall += ms }
    }

    @Before fun clearOnlyTestState() {
        ids.forEach { PlaybackSelectionBridge.unregister(it); LiveEditBridge.unregister(it) }
        WallpaperTarget.entries.forEach {
            app.getSharedPreferences(ScheduleStore.nameFor(it), Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun removeTestReceivers() {
        ids.forEach { PlaybackSelectionBridge.unregister(it); LiveEditBridge.unregister(it) }
        coordinators.forEach { it.unregister("same-local-id") }
        idle()
    }

    @Test fun sameImageIdIsSentOnlyToTheRequestedDomainEvenAfterOtherDomainBecomesVisible() {
        val home = SelectionReceiver(PlaybackSelectionBridge.Result.ACCEPTED)
        val lock = SelectionReceiver(PlaybackSelectionBridge.Result.QUEUED)
        PlaybackSelectionBridge.register(ids[0], home, WallpaperTarget.HOME)
        PlaybackSelectionBridge.register(ids[2], lock, WallpaperTarget.LOCK)
        PlaybackSelectionBridge.markVisible(ids[2])
        PlaybackSelectionBridge.markVisible(ids[0])
        assertEquals(PlaybackSelectionBridge.Result.QUEUED, PlaybackSelectionBridge.submit("same-image", WallpaperTarget.LOCK))
        assertTrue(home.images.isEmpty())
        assertEquals(PlaybackSelectionBridge.Result.ACCEPTED, PlaybackSelectionBridge.submit("same-image", WallpaperTarget.HOME))
        assertEquals(listOf("same-image"), home.images)
        assertEquals(listOf("same-image"), lock.images)
    }

    @Test fun noLockEngineDoesNotFallBackToTheVisibleHomeEngine() {
        val home = SelectionReceiver(PlaybackSelectionBridge.Result.ACCEPTED)
        PlaybackSelectionBridge.register(ids[0], home)
        PlaybackSelectionBridge.markVisible(ids[0])
        assertEquals(PlaybackSelectionBridge.Result.NO_ENGINE, PlaybackSelectionBridge.submit("same-image", WallpaperTarget.LOCK))
        assertTrue(home.images.isEmpty())
    }

    @Test fun mostRecentlyVisibleSelectionIsTrackedWithinEachDomain() {
        val home = SelectionReceiver(PlaybackSelectionBridge.Result.ACCEPTED)
        val lockA = SelectionReceiver(PlaybackSelectionBridge.Result.BUSY_EDIT)
        val lockB = SelectionReceiver(PlaybackSelectionBridge.Result.ACCEPTED)
        PlaybackSelectionBridge.register(ids[0], home, WallpaperTarget.HOME)
        PlaybackSelectionBridge.register(ids[2], lockA, WallpaperTarget.LOCK)
        PlaybackSelectionBridge.register(ids[3], lockB, WallpaperTarget.LOCK)
        PlaybackSelectionBridge.markVisible(ids[3])
        PlaybackSelectionBridge.markVisible(ids[2])
        PlaybackSelectionBridge.markVisible(ids[0])
        assertEquals(PlaybackSelectionBridge.Result.BUSY_EDIT, PlaybackSelectionBridge.submit("same-image", WallpaperTarget.LOCK))
        assertEquals(listOf("same-image"), lockA.images)
        assertTrue(lockB.images.isEmpty())
        assertTrue(home.images.isEmpty())
    }

    @Test fun unregisteringTheLastLockEngineDoesNotRedirectItsSelectionToHome() {
        val home = SelectionReceiver(PlaybackSelectionBridge.Result.ACCEPTED)
        val lock = SelectionReceiver(PlaybackSelectionBridge.Result.ACCEPTED)
        PlaybackSelectionBridge.register(ids[0], home, WallpaperTarget.HOME)
        PlaybackSelectionBridge.register(ids[2], lock, WallpaperTarget.LOCK)
        PlaybackSelectionBridge.markVisible(ids[2])
        PlaybackSelectionBridge.unregister(ids[2])
        assertEquals(PlaybackSelectionBridge.Result.NO_ENGINE, PlaybackSelectionBridge.submit("same-image", WallpaperTarget.LOCK))
        assertTrue(home.images.isEmpty())
        assertTrue(lock.images.isEmpty())
    }

    @Test fun unspecifiedEditFindStaysInItsDomainAndDefaultRemainsHome() {
        val home = EditReceiver()
        val lock = EditReceiver()
        LiveEditBridge.register(ids[0], home)
        LiveEditBridge.register(ids[2], lock, WallpaperTarget.LOCK)
        LiveEditBridge.markVisible(ids[0])
        LiveEditBridge.markVisible(ids[2])
        assertSame(home, LiveEditBridge.find(null))
        assertSame(lock, LiveEditBridge.find(null, WallpaperTarget.LOCK))
    }

    @Test fun explicitEditIdCannotSelectTheOtherDomain() {
        val home = EditReceiver()
        val lock = EditReceiver()
        LiveEditBridge.register(ids[0], home, WallpaperTarget.HOME)
        LiveEditBridge.register(ids[2], lock, WallpaperTarget.LOCK)
        assertNull(LiveEditBridge.find(ids[0], WallpaperTarget.LOCK))
        assertNull(LiveEditBridge.find(ids[2], WallpaperTarget.HOME))
        assertSame(home, LiveEditBridge.find(ids[0], WallpaperTarget.HOME))
        assertSame(lock, LiveEditBridge.find(ids[2], WallpaperTarget.LOCK))
    }

    @Test fun explicitStaleEditIdFailsClosedEvenWhenAnotherSameDomainEngineExists() {
        val stale = EditReceiver()
        val current = EditReceiver()
        LiveEditBridge.register(ids[2], stale, WallpaperTarget.LOCK)
        LiveEditBridge.register(ids[3], current, WallpaperTarget.LOCK)
        LiveEditBridge.markVisible(ids[3])
        LiveEditBridge.unregister(ids[2])
        assertNull(LiveEditBridge.find(ids[2], WallpaperTarget.LOCK))
        assertSame(current, LiveEditBridge.find(null, WallpaperTarget.LOCK))
    }

    @Test fun noLockEditorNeverFallsBackToAHomeEditor() {
        val home = EditReceiver()
        LiveEditBridge.register(ids[0], home)
        LiveEditBridge.markVisible(ids[0])
        assertNull(LiveEditBridge.find(null, WallpaperTarget.LOCK))
        assertNull(LiveEditBridge.find("missing", WallpaperTarget.LOCK))
        assertSame(home, LiveEditBridge.find(null))
    }

    @Test fun newestVisibleEditEngineIsChosenInsideOnlyItsOwnDomain() {
        val home = EditReceiver()
        val lockA = EditReceiver()
        val lockB = EditReceiver()
        LiveEditBridge.register(ids[0], home, WallpaperTarget.HOME)
        LiveEditBridge.register(ids[2], lockA, WallpaperTarget.LOCK)
        LiveEditBridge.register(ids[3], lockB, WallpaperTarget.LOCK)
        LiveEditBridge.markVisible(ids[3])
        LiveEditBridge.markVisible(ids[2])
        LiveEditBridge.markVisible(ids[0])
        assertSame(lockA, LiveEditBridge.find(null, WallpaperTarget.LOCK))
        LiveEditBridge.unregister(ids[2])
        assertSame(lockB, LiveEditBridge.find(null, WallpaperTarget.LOCK))
    }

    @Test fun theSameUnlockEventHasIndependentDispatchAndCommitReceiptsInEachDomain() {
        val homeStore = ScheduleStore(app, WallpaperTarget.HOME)
        val lockStore = ScheduleStore(app, WallpaperTarget.LOCK)
        homeStore.saveSettings(ScheduleSettings(onUnlock = true))
        lockStore.saveSettings(ScheduleSettings(onUnlock = true))
        val home = schedule(WallpaperTarget.HOME)
        val lock = schedule(WallpaperTarget.LOCK)
        val homeReceiver = ScheduledReceiver()
        val lockReceiver = ScheduledReceiver()
        registerVisible(home, homeReceiver)
        registerVisible(lock, lockReceiver)
        env.advance(10_000)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        val homeDispatch = homeReceiver.switches.single()
        val lockDispatch = lockReceiver.switches.single()
        assertEquals(setOf(ScheduleReason.UNLOCK), homeDispatch.reasons)
        assertEquals(setOf(ScheduleReason.UNLOCK), lockDispatch.reasons)
        env.advance(120)
        home.onCommitted("same-local-id", homeDispatch.seq, 10)
        assertEquals(listOf(120L), homeStore.loadStats().commitNormal)
        assertTrue(lockStore.loadStats().commitNormal.isEmpty())
        env.advance(80)
        lock.onCommitted("same-local-id", lockDispatch.seq, 12)
        assertEquals(listOf(200L), lockStore.loadStats().commitNormal)
        assertEquals(listOf(120L), homeStore.loadStats().commitNormal)
    }

    @Test fun aHeldLockDispatchDoesNotHoldOrConsumeTheHomeReceipt() {
        val homeStore = ScheduleStore(app, WallpaperTarget.HOME)
        val lockStore = ScheduleStore(app, WallpaperTarget.LOCK)
        homeStore.saveSettings(ScheduleSettings(onUnlock = true))
        lockStore.saveSettings(ScheduleSettings(onUnlock = true))
        val home = schedule(WallpaperTarget.HOME)
        val lock = schedule(WallpaperTarget.LOCK)
        val homeReceiver = ScheduledReceiver()
        val lockReceiver = ScheduledReceiver()
        registerVisible(home, homeReceiver)
        registerVisible(lock, lockReceiver)
        env.advance(10_000)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        val homeDispatch = homeReceiver.switches.single()
        val lockDispatch = lockReceiver.switches.single()
        lock.onOutcome("same-local-id", lockDispatch.seq, SwitchOutcome.HELD_EDIT)
        env.advance(150)
        home.onCommitted("same-local-id", homeDispatch.seq, 15)
        assertEquals(listOf(150L), homeStore.loadStats().commitNormal)
        assertTrue(lockStore.loadLog().none { it.kind == EventKind.COMMIT })
        env.advance(200)
        lock.onCommitted("same-local-id", lockDispatch.seq, 15)
        assertEquals(1, lockStore.loadLog().count { it.kind == EventKind.COMMIT })
        assertTrue(lockStore.loadStats().commitNormal.isEmpty())
        assertEquals(1, homeStore.loadLog().count { it.kind == EventKind.COMMIT })
        assertTrue(homeStore.loadLog().none { it.detail.contains("HELD_EDIT") })
    }

    @Test fun unregisteringHomeDoesNotUnregisterOrDispatchThroughTheLockCoordinator() {
        ScheduleStore(app, WallpaperTarget.HOME).saveSettings(ScheduleSettings(onUnlock = true))
        ScheduleStore(app, WallpaperTarget.LOCK).saveSettings(ScheduleSettings(onUnlock = true))
        val home = schedule(WallpaperTarget.HOME)
        val lock = schedule(WallpaperTarget.LOCK)
        val homeReceiver = ScheduledReceiver()
        val lockReceiver = ScheduledReceiver()
        registerVisible(home, homeReceiver)
        registerVisible(lock, lockReceiver)
        assertEquals(2, registeredScheduleReceivers())
        home.unregister("same-local-id")
        assertEquals(1, registeredScheduleReceivers())
        env.advance(10_000)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        assertTrue(homeReceiver.switches.isEmpty())
        assertEquals(1, lockReceiver.switches.size)
        lock.unregister("same-local-id")
        assertEquals(0, registeredScheduleReceivers())
    }

    @Test fun changingLockRulesDoesNotRefreshOrWriteTheHomeCoordinator() {
        val homeStore = ScheduleStore(app, WallpaperTarget.HOME)
        val lockStore = ScheduleStore(app, WallpaperTarget.LOCK)
        val home = schedule(WallpaperTarget.HOME)
        val lock = schedule(WallpaperTarget.LOCK)
        val homeReceiver = ScheduledReceiver()
        val lockReceiver = ScheduledReceiver()
        registerVisible(home, homeReceiver)
        registerVisible(lock, lockReceiver)
        val homeStateBefore = homeStore.loadState()
        val homeLogBefore = homeStore.loadLog()
        env.advance(5_000)
        lockStore.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("same-rule-id", sourceIds = listOf("same-source-id")))))
        idle()
        assertEquals(0, homeReceiver.refreshes)
        assertTrue(homeReceiver.switches.isEmpty())
        assertEquals(1, lockReceiver.refreshes)
        assertEquals(setOf(ScheduleReason.RULE), lockReceiver.switches.single().reasons)
        assertEquals(homeStateBefore, homeStore.loadState())
        assertEquals(homeLogBefore, homeStore.loadLog())
        assertEquals(ScheduleSettings(), homeStore.loadSettings())
    }

    private fun schedule(target: WallpaperTarget) = ScheduleCoordinator(app, env, target).also { coordinators += it }
    private fun registerVisible(coordinator: ScheduleCoordinator, receiver: ScheduledReceiver) {
        coordinator.register("same-local-id", receiver)
        coordinator.onVisibility("same-local-id", true)
        idle()
    }
    private fun registeredScheduleReceivers() = shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED) }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
