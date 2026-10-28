package dev.abdus.apps.shimmer.ui.components

import dev.abdus.apps.shimmer.p10.WallpaperTarget.HOME
import dev.abdus.apps.shimmer.p10.WallpaperTarget.LOCK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 真实生产读取／解码交帧函数的故障与竞态测试；不冒充 Compose 绘制或目标机观感验证。 */
class BackdropFrameCoordinatorTest {
    private fun shownHome(): BackdropFrameCoordinator<String> = BackdropFrameCoordinator<String>().apply {
        selectTarget(HOME)
        assertTrue(ready(begin(HOME)!!, "home-blurred-bitmap"))
    }

    @Test fun firstTargetIsPendingWithoutInventingAnImage() {
        val state = BackdropFrameCoordinator<String>()
        assertEquals(BackdropPhase.EMPTY, state.phase)
        state.selectTarget(LOCK)
        assertEquals(BackdropPhase.PENDING, state.phase)
        assertNull(state.frame)
    }

    @Test fun changingTargetKeepsOnlyPreviousDecorativeFrameUntilDecodeCompletes() {
        val state = shownHome()
        val oldFrame = state.frame
        state.selectTarget(LOCK)
        assertSame(oldFrame, state.frame)
        assertEquals(HOME, state.frame!!.ticket.target)
        assertEquals(LOCK, state.selectedTarget)
        assertEquals(BackdropPhase.PENDING, state.phase)
        val request = state.begin(LOCK)!!
        assertSame(oldFrame, state.frame)
        assertTrue(state.ready(request, "lock-blurred-bitmap"))
        assertEquals(LOCK, state.frame!!.ticket.target)
        assertEquals("lock-blurred-bitmap", state.frame!!.content)
        assertEquals(BackdropPhase.READY, state.phase)
    }

    @Test fun sameTargetRefreshKeepsItsReadyFrameWhilePending() {
        val state = shownHome()
        val oldFrame = state.frame
        state.selectTarget(HOME)
        assertEquals(BackdropPhase.READY, state.phase)
        state.begin(HOME)
        assertEquals(BackdropPhase.PENDING, state.phase)
        assertSame(oldFrame, state.frame)
    }

    @Test fun emptyTargetReleasesPreviousFrameInsteadOfDisplayingAnotherDomainIndefinitely() {
        val state = shownHome()
        state.selectTarget(LOCK)
        assertTrue(state.empty(state.begin(LOCK)!!))
        assertEquals(BackdropPhase.EMPTY, state.phase)
        assertNull(state.frame)
    }

    @Test fun failedTargetReleasesPreviousFrameAndCanRecoverOnNextRead() {
        val state = shownHome()
        state.selectTarget(LOCK)
        assertTrue(state.error(state.begin(LOCK)!!))
        assertEquals(BackdropPhase.ERROR, state.phase)
        assertNull(state.frame)
        assertTrue(state.ready(state.begin(LOCK)!!, "recovered"))
        assertEquals("recovered", state.frame!!.content)
    }

    @Test fun oldTargetCannotStartAReadAfterSelectionChanged() {
        val state = shownHome()
        state.selectTarget(LOCK)
        assertNull(state.begin(HOME))
        assertNotNull(state.begin(LOCK))
    }

    @Test fun secondReadRejectsOldSuccessEmptyAndErrorWithoutClearingCurrentFrame() {
        val state = shownHome()
        val first = state.begin(HOME)!!
        val latest = state.begin(HOME)!!
        assertTrue(state.ready(latest, "latest"))
        val currentFrame = state.frame
        assertFalse(state.ready(first, "stale"))
        assertFalse(state.empty(first))
        assertFalse(state.error(first))
        assertSame(currentFrame, state.frame)
        assertEquals(BackdropPhase.READY, state.phase)
    }

    @Test fun homeLockHomeDoesNotReviveFirstHomeRequestEvenForIdenticalContent() {
        val state = shownHome()
        val firstHome = state.begin(HOME)!!
        state.selectTarget(LOCK)
        val lock = state.begin(LOCK)!!
        state.selectTarget(HOME)
        val latestHome = state.begin(HOME)!!
        assertFalse(state.ready(firstHome, "same-uri-content"))
        assertFalse(state.error(lock))
        assertTrue(state.ready(latestHome, "same-uri-content"))
        assertSame(latestHome, state.frame!!.ticket)
    }

    @Test fun copiedTargetAndReadNumbersDoNotForgeRequestIdentity() {
        val state = shownHome()
        val actual = state.begin(HOME)!!
        val forged = BackdropTicket(actual.target, actual.read)
        assertFalse(state.ready(forged, "forged"))
        assertFalse(state.error(forged))
        assertTrue(state.ready(actual, "actual"))
    }

    @Test fun terminalTicketCannotPublishOrClearTwice() {
        val state = shownHome()
        val ticket = state.begin(HOME)!!
        assertTrue(state.ready(ticket, "ready"))
        assertFalse(state.empty(ticket))
        assertFalse(state.error(ticket))
        assertFalse(state.ready(ticket, "duplicate"))
        assertEquals("ready", state.frame!!.content)
    }

    @Test fun delayedReadFromOtherTargetIsNotEvenDecoded() = runBlocking {
        val state = shownHome()
        val read = CompletableDeferred<String?>()
        var decoded = 0
        val ticket = state.begin(HOME)!!
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            loadBackdropFrame(state, ticket, { read.await() }, { decoded++; "decoded-$it" })
        }
        state.selectTarget(LOCK)
        read.complete("home-uri")
        assertFalse(work.await())
        assertEquals(0, decoded)
        assertEquals(LOCK, state.selectedTarget)
        assertEquals(BackdropPhase.PENDING, state.phase)
    }

    @Test fun delayedDecodeCannotOverwriteNewTargetFrame() = runBlocking {
        val state = shownHome()
        val decode = CompletableDeferred<String>()
        val old = state.begin(HOME)!!
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            loadBackdropFrame(state, old, { "home-uri" }, { decode.await() })
        }
        state.selectTarget(LOCK)
        val current = state.begin(LOCK)!!
        assertTrue(loadBackdropFrame(state, current, { "lock-uri" }, { "lock-pixels" }))
        val lockFrame = state.frame
        decode.complete("stale-home-pixels")
        assertFalse(work.await())
        assertSame(lockFrame, state.frame)
    }

    @Test fun delayedDecodeFailureCannotClearNewTargetFrame() = runBlocking {
        val state = shownHome()
        val decode = CompletableDeferred<String>()
        val old = state.begin(HOME)!!
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            loadBackdropFrame(state, old, { "home-uri" }, { decode.await() })
        }
        state.selectTarget(LOCK)
        assertTrue(state.ready(state.begin(LOCK)!!, "lock-pixels"))
        val lockFrame = state.frame
        decode.completeExceptionally(IllegalStateException("读取失败"))
        assertFalse(work.await())
        assertSame(lockFrame, state.frame)
        assertEquals(BackdropPhase.READY, state.phase)
    }

    @Test fun emptySnapshotDoesNotDecodeAndReleasesDecoration() = runBlocking {
        val state = shownHome()
        val ticket = state.begin(HOME)!!
        var decoded = false
        assertTrue(loadBackdropFrame(state, ticket, { null }, { decoded = true; "unexpected" }))
        assertFalse(decoded)
        assertNull(state.frame)
        assertEquals(BackdropPhase.EMPTY, state.phase)
    }

    @Test fun snapshotReadExceptionIsErrorAndReleasesDecoration() = runBlocking {
        val state = shownHome()
        val ticket = state.begin(HOME)!!
        assertTrue(loadBackdropFrame(state, ticket, { throw SecurityException("无读取权限") }, { "unexpected" }))
        assertNull(state.frame)
        assertEquals(BackdropPhase.ERROR, state.phase)
    }

    @Test fun decodeExceptionIsErrorAndReleasesDecoration() = runBlocking {
        val state = shownHome()
        val ticket = state.begin(HOME)!!
        assertTrue(loadBackdropFrame(state, ticket, { "bad-uri" }, { throw IllegalStateException("坏图片") }))
        assertNull(state.frame)
        assertEquals(BackdropPhase.ERROR, state.phase)
    }

    @Test fun cancellationPropagatesRatherThanPublishingAnErrorAsAnEmptyTarget() = runBlocking {
        val state = shownHome()
        val oldFrame = state.frame
        val ticket = state.begin(HOME)!!
        var caught = false
        try {
            loadBackdropFrame(state, ticket, { throw CancellationException("取消旧读取") }, { "unexpected" })
        } catch (_: CancellationException) {
            caught = true
        }
        assertTrue(caught)
        assertSame(oldFrame, state.frame)
        assertEquals(BackdropPhase.PENDING, state.phase)
    }
}
