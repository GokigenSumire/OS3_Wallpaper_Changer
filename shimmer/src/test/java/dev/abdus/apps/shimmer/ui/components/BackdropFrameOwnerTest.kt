package dev.abdus.apps.shimmer.ui.components

import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p10.WallpaperTarget.HOME
import dev.abdus.apps.shimmer.p10.WallpaperTarget.LOCK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 生产 Owner 的真实协程／互斥通道，注入单调时钟和定时等待，不依赖实时时间睡眠。 */
class BackdropFrameOwnerTest {
    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 0L
        val timers = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        val published = mutableListOf<BackdropFrame<String>?>()
        val readTargets = mutableListOf<WallpaperTarget>()
        var read: suspend (WallpaperTarget) -> String? = { "${it.name}-uri" }
        var decode: suspend (String) -> String = { "pixels-$it" }
        val owner = BackdropFrameOwner(scope, { now },
            readUri = { readTargets += it; read(it) },
            decode = { decode(it) },
            onFrame = { published += it },
            timeoutMs = 5_000,
            waitForDeadline = { wait ->
                val timer = CompletableDeferred<Unit>()
                timers += (now + wait) to timer
                timer.await()
            },
        )
        fun start(target: WallpaperTarget = HOME) { owner.selectTarget(target); owner.setVisible(true) }
        fun advanceTo(time: Long) {
            now = time
            timers.toList().filter { it.first <= now }.forEach { it.second.complete(Unit) }
        }
        fun close() { owner.close(); scope.cancel() }
    }

    @Test fun visibleOwnerReallyReadsSelectedTargetDecodesAndPublishesOneReadyFrame() {
        val h = Harness()
        try {
            h.start(LOCK)
            assertEquals(listOf(LOCK), h.readTargets)
            assertEquals("pixels-LOCK-uri", h.published.single()!!.content)
            assertEquals(BackdropPhase.READY, h.owner.coordinator.phase)
        } finally { h.close() }
    }

    @Test fun hiddenOwnerDoesNotStartIoUntilResume() {
        val h = Harness()
        try {
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            assertTrue(h.readTargets.isEmpty())
            h.owner.setVisible(true)
            assertEquals(listOf(LOCK), h.readTargets)
        } finally { h.close() }
    }

    @Test fun pendingDecodeKeepsDecorationButIndependentDeadlineClearsItWithoutIoReturning() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String>()
        try {
            h.start()
            val previous = h.owner.coordinator.frame
            h.decode = { withContext(NonCancellable) { blocked.await() } }
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            assertSame(previous, h.owner.coordinator.frame)
            h.advanceTo(5_000)
            assertNull(h.owner.coordinator.frame)
            assertNull(h.published.last())
            assertEquals(BackdropPhase.ERROR, h.owner.coordinator.phase)
            blocked.complete("late-pixels")
            assertNull(h.owner.coordinator.frame)
        } finally { blocked.complete("cleanup"); h.close() }
    }

    @Test fun rapidTargetSwitchesCannotRenewRetentionDeadline() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String>()
        try {
            h.start()
            h.decode = { withContext(NonCancellable) { blocked.await() } }
            h.owner.refresh()
            h.advanceTo(2_000)
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            h.advanceTo(4_000)
            h.owner.selectTarget(HOME)
            h.owner.refresh()
            assertNotNull(h.owner.coordinator.frame)
            h.advanceTo(5_000)
            assertNull(h.owner.coordinator.frame)
            assertEquals(BackdropPhase.ERROR, h.owner.coordinator.phase)
        } finally { blocked.complete("cleanup"); h.close() }
    }

    @Test fun sameTargetRefreshesCannotRenewRetentionDeadline() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String>()
        try {
            h.start()
            h.decode = { withContext(NonCancellable) { blocked.await() } }
            h.owner.refresh()
            h.advanceTo(4_000)
            h.owner.refresh()
            h.advanceTo(5_000)
            assertNull(h.owner.coordinator.frame)
        } finally { blocked.complete("cleanup"); h.close() }
    }

    @Test fun singleIoLaneWaitsForUncooperativeOldReadAndOnlyLatestTargetCanProceed() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String?>()
        var active = 0
        var maximum = 0
        var calls = 0
        try {
            h.start()
            h.readTargets.clear()
            h.read = { target ->
                calls++
                active++
                maximum = maxOf(maximum, active)
                try {
                    if (calls == 1) withContext(NonCancellable) { blocked.await() }
                    else "${target.name}-latest"
                } finally { active-- }
            }
            h.owner.refresh()
            repeat(12) {
                h.owner.selectTarget(if (it % 2 == 0) LOCK else HOME)
                h.owner.refresh()
            }
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            assertEquals(1, calls)
            assertEquals(1, maximum)
            blocked.complete("old-home")
            assertEquals(2, calls)
            assertEquals(1, maximum)
            assertEquals(0, active)
            assertEquals(LOCK, h.owner.coordinator.frame!!.ticket.target)
            assertEquals("pixels-LOCK-latest", h.owner.coordinator.frame!!.content)
        } finally { blocked.complete("cleanup"); h.close() }
    }

    @Test fun stopClearsPendingFrameImmediatelyEvenWhenProviderCannotCancelYet() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String?>()
        try {
            h.start()
            h.read = { withContext(NonCancellable) { blocked.await() } }
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            h.owner.setVisible(false)
            assertNull(h.owner.coordinator.frame)
            assertNull(h.published.last())
            h.advanceTo(100_000)
            blocked.complete("late-lock")
            assertNull(h.owner.coordinator.frame)
        } finally { blocked.complete("cleanup"); h.close() }
    }

    @Test fun targetChangedBeforeNextEffectIsAlsoClearedOnStop() {
        val h = Harness()
        try {
            h.start()
            h.owner.selectTarget(LOCK)
            assertNotNull(h.owner.coordinator.frame)
            h.owner.setVisible(false)
            assertNull(h.owner.coordinator.frame)
            assertEquals(BackdropPhase.ERROR, h.owner.coordinator.phase)
        } finally { h.close() }
    }

    @Test fun returningAfterLongBackgroundCannotRevivePreviousDomainDecoration() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String?>()
        try {
            h.start()
            h.read = { withContext(NonCancellable) { blocked.await() } }
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            h.advanceTo(2_000)
            h.owner.setVisible(false)
            assertNull(h.owner.coordinator.frame)
            h.advanceTo(100_000)
            h.owner.setVisible(true)
            assertNull(h.owner.coordinator.frame)
            h.advanceTo(105_000)
            assertNull(h.owner.coordinator.frame)
        } finally { blocked.complete(null); h.close() }
    }

    @Test fun readyFrameIsNotClearedByCancelledOldDeadline() {
        val h = Harness()
        try {
            h.start()
            val ready = h.owner.coordinator.frame
            h.advanceTo(500_000)
            assertSame(ready, h.owner.coordinator.frame)
            assertEquals(BackdropPhase.READY, h.owner.coordinator.phase)
        } finally { h.close() }
    }

    @Test fun emptyAndErrorEndRetentionThenNextRefreshCanRecover() {
        val h = Harness()
        try {
            h.start()
            h.read = { null }
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            assertEquals(BackdropPhase.EMPTY, h.owner.coordinator.phase)
            assertNull(h.owner.coordinator.frame)
            h.read = { throw SecurityException("撤权") }
            h.owner.refresh()
            assertEquals(BackdropPhase.ERROR, h.owner.coordinator.phase)
            assertNull(h.owner.coordinator.frame)
            h.read = { "recovered" }
            h.advanceTo(10_000)
            h.owner.refresh()
            assertEquals("pixels-recovered", h.owner.coordinator.frame!!.content)
        } finally { h.close() }
    }

    @Test fun currentJobCancellationReleasesPendingFrameInsteadOfLeavingPendingForever() {
        val h = Harness()
        try {
            h.start()
            val blocked = CompletableDeferred<String?>()
            h.read = { blocked.await() }
            h.owner.refresh()
            h.scope.cancel()
            assertNull(h.owner.coordinator.frame)
            assertEquals(BackdropPhase.ERROR, h.owner.coordinator.phase)
        } finally { h.close() }
    }

    @Test fun closeDropsReadyReferencesWithoutRecyclingSharedContent() {
        val h = Harness()
        try {
            h.start()
            val previousContent = h.owner.coordinator.frame!!.content
            h.owner.close()
            assertNull(h.owner.coordinator.frame)
            assertNull(h.published.last())
            assertEquals(BackdropPhase.EMPTY, h.owner.coordinator.phase)
            assertEquals("pixels-HOME-uri", previousContent)
        } finally { h.close() }
    }

    @Test fun disposeRejectsLateReadAndDoesNotStartQueuedOtherDomainIo() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String?>()
        try {
            h.start()
            h.readTargets.clear()
            h.read = { withContext(NonCancellable) { blocked.await() } }
            h.owner.refresh()
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            h.owner.close()
            blocked.complete("late-home")
            assertNull(h.owner.coordinator.frame)
            assertEquals(listOf(HOME), h.readTargets)
            assertEquals(BackdropPhase.EMPTY, h.owner.coordinator.phase)
        } finally { blocked.complete(null); h.close() }
    }

    @Test fun timeoutRejectsLatePixelsButANewExplicitRefreshCanRecover() = runBlocking {
        val h = Harness()
        val blocked = CompletableDeferred<String>()
        try {
            h.start()
            h.decode = { withContext(NonCancellable) { blocked.await() } }
            h.owner.selectTarget(LOCK)
            h.owner.refresh()
            h.advanceTo(5_000)
            blocked.complete("late-lock")
            assertNull(h.owner.coordinator.frame)
            assertEquals(BackdropPhase.ERROR, h.owner.coordinator.phase)
            h.decode = { "recovered-$it" }
            h.owner.refresh()
            assertEquals(LOCK, h.owner.coordinator.frame!!.ticket.target)
            assertEquals("recovered-LOCK-uri", h.owner.coordinator.frame!!.content)
        } finally { blocked.complete("cleanup"); h.close() }
    }
}
