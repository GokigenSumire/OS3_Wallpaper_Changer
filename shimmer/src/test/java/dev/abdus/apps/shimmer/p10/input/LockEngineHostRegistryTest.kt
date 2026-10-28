package dev.abdus.apps.shimmer.p10.input

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production registry; its detached Binder snapshot is not a fake endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockEngineHostRegistryTest {
    private val first = "registry-test-first"
    private val second = "registry-test-second"

    private class Target(var value: LockHostState) : LockEngineHostRegistry.Target {
        val commands = mutableListOf<Triple<Int, String, String>>()
        val cancellations = mutableListOf<String>()
        var retired = 0
        override fun state() = value
        override fun command(action: Int, ticket: String, requestId: String, complete: (LockHostResult) -> Unit) {
            commands += Triple(action, ticket, requestId)
            complete(LockHostResult("COMMITTED"))
        }
        override fun cancel(requestId: String) { cancellations += requestId }
        override fun retire() { retired++; value = value.copy(ready = false, reason = "NO_ENGINE") }
    }

    @After fun unregisterOnlyThisTestsTargets() {
        LockEngineHostRegistry.unregister(first)
        LockEngineHostRegistry.unregister(second)
    }

    @Test fun emptyRegistryReturnsNoEngineAndCannotDispatch() {
        LockEngineHostRegistry.refresh()
        assertFalse(LockEngineHostRegistry.snapshot().ready)
        var result: LockHostResult? = null
        LockEngineHostRegistry.dispatch(1, "stale", "request", { result = it })
        assertEquals("NO_ENGINE", result!!.status)
    }

    @Test fun newFormalAuthorityRetiresOldReadyTargetRatherThanFallingBackWhenBusy() {
        val a = Target(LockHostState(true, "ticket-A", "READY"))
        val b = Target(LockHostState(false, "ticket-B", "BUSY_EDIT"))
        LockEngineHostRegistry.register(first, a)
        LockEngineHostRegistry.register(second, b)
        assertEquals(1, a.retired)
        assertEquals(b.value, LockEngineHostRegistry.snapshot())
        var blocked = ""
        LockEngineHostRegistry.dispatch(1, "ticket-A", "old-request", { blocked = it.status })
        assertEquals("BUSY_EDIT", blocked)
        assertTrue(a.commands.isEmpty())
        assertTrue(b.commands.isEmpty())
        b.value = LockHostState(true, "ticket-B", "READY")
        LockEngineHostRegistry.refresh()
        assertEquals(b.value, LockEngineHostRegistry.snapshot())
        LockEngineHostRegistry.dispatch(1, "ticket-B", "request", {})
        assertTrue(a.commands.isEmpty())
        assertEquals(1, b.commands.size)
    }

    @Test fun snapshotIsDetachedUntilExplicitMainRefresh() {
        val target = Target(LockHostState(true, "old", "READY"))
        LockEngineHostRegistry.register(first, target)
        val detached = LockEngineHostRegistry.snapshot()
        target.value = LockHostState(false, "new", "BUSY_EDIT")
        assertEquals(detached, LockEngineHostRegistry.snapshot())
        LockEngineHostRegistry.refresh()
        assertEquals(target.value, LockEngineHostRegistry.snapshot())
        assertEquals("old", detached.ticket)
    }

    @Test fun dispatchRechecksTargetStateRatherThanTrustingEarlierReadySnapshot() {
        val target = Target(LockHostState(true, "ticket", "READY"))
        LockEngineHostRegistry.register(first, target)
        assertTrue(LockEngineHostRegistry.snapshot().ready)
        target.value = LockHostState(false, "ticket", "BUSY_EDIT")
        var result = ""
        LockEngineHostRegistry.dispatch(1, "ticket", "request", { result = it.status })
        assertEquals("BUSY_EDIT", result)
        assertTrue(target.commands.isEmpty())
    }

    @Test fun staleTicketCannotFallBackToEitherOldOrNewEngine() {
        val a = Target(LockHostState(true, "old", "READY"))
        val b = Target(LockHostState(true, "new", "READY"))
        LockEngineHostRegistry.register(first, a)
        LockEngineHostRegistry.register(second, b)
        var result = ""
        LockEngineHostRegistry.dispatch(1, "old", "request", { result = it.status })
        assertEquals("STALE_TICKET", result)
        assertTrue(a.commands.isEmpty())
        assertTrue(b.commands.isEmpty())
    }

    @Test fun oldEngineUnregisterCannotRemoveNewAuthorityAndNoRetiredFallbackCanRevive() {
        val a = Target(LockHostState(true, "A", "READY"))
        val b = Target(LockHostState(true, "B", "READY"))
        LockEngineHostRegistry.register(first, a)
        LockEngineHostRegistry.register(second, b)
        LockEngineHostRegistry.unregister(first)
        assertEquals(b.value, LockEngineHostRegistry.snapshot())
        var result = ""
        LockEngineHostRegistry.dispatch(1, "A", "request", { result = it.status })
        assertEquals("STALE_TICKET", result)
        assertTrue(a.commands.isEmpty())
        LockEngineHostRegistry.dispatch(1, "B", "next", { result = it.status })
        assertEquals("COMMITTED", result)
        assertEquals(1, b.commands.size)
        LockEngineHostRegistry.unregister(second)
        assertEquals("NO_ENGINE", LockEngineHostRegistry.snapshot().reason)
    }

    @Test fun revokePassesOnlyExactRequestIdAndRefreshesDetachedState() {
        val a = Target(LockHostState(true, "A", "READY"))
        val b = Target(LockHostState(false, "B", "BUSY_EDIT"))
        LockEngineHostRegistry.register(first, a)
        LockEngineHostRegistry.register(second, b)
        LockEngineHostRegistry.revoke("old-request")
        assertTrue(a.cancellations.isEmpty())
        assertEquals(listOf("old-request"), b.cancellations)
        assertEquals(b.value, LockEngineHostRegistry.snapshot())
    }

    @Test fun exactSameRegistrationDoesNotRetireOrInterruptTheCurrentAuthority() {
        val target = Target(LockHostState(true, "A", "READY"))
        LockEngineHostRegistry.register(first, target)
        LockEngineHostRegistry.register(first, target)
        assertEquals(0, target.retired)
        assertEquals(target.value, LockEngineHostRegistry.snapshot())
    }

    @Test fun replacingTargetUnderSameIdStillRetiresOldAuthority() {
        val old = Target(LockHostState(true, "A", "READY"))
        val next = Target(LockHostState(true, "B", "READY"))
        LockEngineHostRegistry.register(first, old)
        LockEngineHostRegistry.register(first, next)
        assertEquals(1, old.retired)
        assertEquals(next.value, LockEngineHostRegistry.snapshot())
    }

    @Test fun oldSameIdTargetsLateUnregisterCannotRemoveItsReplacement() {
        val old = Target(LockHostState(true, "A", "READY"))
        val next = Target(LockHostState(true, "B", "READY"))
        LockEngineHostRegistry.register(first, old)
        LockEngineHostRegistry.register(first, next)
        LockEngineHostRegistry.unregister(first, old)
        assertEquals(next.value, LockEngineHostRegistry.snapshot())
        var result = ""
        LockEngineHostRegistry.dispatch(1, "B", "new-request", { result = it.status })
        assertEquals("COMMITTED", result)
        LockEngineHostRegistry.unregister(first, next)
        assertEquals("NO_ENGINE", LockEngineHostRegistry.snapshot().reason)
    }
}
