package dev.abdus.apps.shimmer.p10.editor

import org.junit.Assert.*
import org.junit.Test

/** 纯状态与内存预算，不冒充指定手机认证结果。 */
class LockEditDiagnosticLedgerTest {
    @Test fun navigationCaptureNeedsRecentExplicitReadAndFailsClosedAtBoundaryOrClockRollback() {
        assertFalse(LockEditDiagnostics.navigationCaptureActive(0L, -1L))
        assertFalse(LockEditDiagnostics.navigationCaptureActive(-1L, 0L))
        assertTrue(LockEditDiagnostics.navigationCaptureActive(10L, 10L))
        assertTrue(LockEditDiagnostics.navigationCaptureActive(2_509L, 10L))
        assertFalse(LockEditDiagnostics.navigationCaptureActive(2_510L, 10L))
        assertFalse(LockEditDiagnostics.navigationCaptureActive(9L, 10L))
        assertFalse(LockEditDiagnostics.navigationCaptureActive(Long.MAX_VALUE, 0L))
        assertTrue(LockEditDiagnostics.navigationCaptureActive(Long.MAX_VALUE, Long.MAX_VALUE - 1L))
    }
    private fun ledger() = LockEditDiagnosticLedger().also { it.openOwner("owner") }
    @Test fun freshOwnerHasNoInventedAuthenticationOrResult() {
        val page = ledger().read("owner",0)!!
        assertEquals(0L,page.latestSequence); assertFalse(page.truncated); assertTrue(page.rows.isEmpty())
    }
    @Test fun everyFixedCauseIsAcceptedAndUnknownIsRejected() {
        val ledger = ledger(); val request = ledger.begin("owner","request")!!
        assertFalse(request.record(0)); assertFalse(request.record(19)); assertFalse(request.record(Int.MAX_VALUE))
        for (cause in 1..18) assertTrue(request.record(cause))
        assertEquals((1..18).toList(),ledger.read("owner",0)!!.rows.map { it.cause })
    }
    @Test fun duplicateLifecycleCallbackDoesNotGrowLedger() {
        val ledger = ledger(); val request = ledger.begin("owner","request")!!
        assertTrue(request.record(LockEditDiagnosticCause.AUTH_WAITING))
        repeat(100) { assertFalse(request.record(LockEditDiagnosticCause.AUTH_WAITING)) }
        assertEquals(1,ledger.read("owner",0)!!.rows.size)
    }
    @Test fun rowsCarryOnlySequenceAndFixedCause() {
        val ledger = ledger(); val request = ledger.begin("owner","secret-request")!!
        request.record(LockEditDiagnosticCause.AUTH_WAITING)
        val text = ledger.read("owner",0).toString()
        assertFalse(text.contains("secret-request")); assertFalse(text.contains("owner")); assertFalse(text.contains("content://"))
    }
    @Test fun readingDoesNotConsumeOrCreateEvents() {
        val ledger = ledger(); ledger.begin("owner","request")!!.record(1)
        assertEquals(ledger.read("owner",0),ledger.read("owner",0))
    }
    @Test fun cursorReturnsOnlyNewRowsAndRejectsFutureOrNegative() {
        val ledger = ledger(); val request = ledger.begin("owner","request")!!
        request.record(1); request.record(2)
        assertEquals(listOf(2),ledger.read("owner",1)!!.rows.map { it.cause })
        assertTrue(ledger.read("owner",2)!!.rows.isEmpty())
        assertNull(ledger.read("owner",-1)); assertNull(ledger.read("owner",3))
    }
    @Test fun unknownOwnerCannotReadOrCreateRequest() {
        val ledger = ledger(); assertNull(ledger.read("other",0)); assertNull(ledger.begin("other","request"))
    }
    @Test fun invalidRequestTokenCannotCreateRecord() {
        val ledger = ledger(); assertNull(ledger.begin("owner","secret/path")); assertNull(ledger.begin("owner",""))
    }
    @Test fun sameRequestReturnsSamePrivateHandle() {
        val ledger = ledger(); assertSame(ledger.begin("owner","request"),ledger.begin("owner","request"))
    }
    @Test fun currentOwnerRevocationPermanentlyRejectsOldCallbacks() {
        val ledger = ledger(); val request = ledger.begin("owner","request")!!
        ledger.revokeOwner("owner"); assertFalse(request.record(1)); assertNull(ledger.read("owner",0))
    }
    @Test fun oldOwnerRevocationDoesNotEraseNewOwner() {
        val ledger = ledger(); val old = ledger.begin("owner","request")!!
        ledger.openOwner("new-owner"); val next = ledger.begin("new-owner","request")!!
        ledger.revokeOwner("owner"); assertFalse(old.record(1)); assertTrue(next.record(1))
    }
    @Test fun reusedSessionTextStillCannotAcceptOldGeneration() {
        val ledger = ledger(); val old = ledger.begin("owner","request")!!
        ledger.openOwner("owner"); assertFalse(old.record(1)); assertTrue(ledger.begin("owner","request")!!.record(1))
    }
    @Test fun oldestRequestEvictionStopsItsLateResult() {
        val ledger = ledger(); val old = ledger.begin("owner","first")!!
        ledger.begin("owner","second"); ledger.begin("owner","third")
        assertFalse(old.record(LockEditDiagnosticCause.EDITOR_SAVED))
    }
    @Test fun retainedRowsNeverExceedThirtyTwoAndTruncationIsExplicit() {
        val ledger = ledger()
        for (index in 0..3) { val request = ledger.begin("owner","request-$index")!!; for (cause in 1..12) request.record(cause) }
        val page = ledger.read("owner",0)!!
        assertEquals(48L,page.latestSequence); assertEquals(32,page.rows.size); assertTrue(page.truncated)
        assertEquals(17L,page.rows.first().sequence); assertFalse(ledger.read("owner",16)!!.truncated)
    }
    @Test fun requestDispatchScopeRestoresOnExceptionAndNestedDispatch() {
        val ledger = ledger(); val first = ledger.begin("owner","first")!!; val second = ledger.begin("owner","second")!!
        assertNull(LockEditDiagnostics.currentRequest())
        LockEditDiagnostics.withRequest(first) {
            try { LockEditDiagnostics.withRequest(second) { assertSame(second,LockEditDiagnostics.currentRequest()); error("EXPECTED") } }
            catch (_: IllegalStateException) { }
            assertSame(first,LockEditDiagnostics.currentRequest())
        }
        assertNull(LockEditDiagnostics.currentRequest())
    }
    @Test fun requestDispatchScopeDoesNotLeakToWorkerThread() {
        val request = ledger().begin("owner","request")!!
        LockEditDiagnostics.withRequest(request) {
            var captured: LockEditDiagnosticLedger.Request? = request
            Thread { captured = LockEditDiagnostics.currentRequest() }.apply { start(); join() }
            assertNull(captured)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun invalidOwnerTokenRejected() { LockEditDiagnosticLedger().openOwner("a/b") }
    @Test(expected = IllegalArgumentException::class) fun excessiveRowsRejected() { LockEditDiagnosticLedger(33) }
    @Test(expected = IllegalArgumentException::class) fun excessiveRequestLimitRejected() { LockEditDiagnosticLedger(requestLimit=3) }
}
