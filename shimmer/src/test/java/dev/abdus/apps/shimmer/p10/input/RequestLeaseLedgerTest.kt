package dev.abdus.apps.shimmer.p10.input

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RequestLeaseLedgerTest {
    @Test fun grantAuthorizesOnlyExactIdBeforeDeadline() {
        val ledger = RequestLeaseLedger()
        assertTrue(ledger.grant("owner", "request", 15000))
        assertTrue(ledger.authorized("request", 14999))
        assertFalse(ledger.authorized("request", 15000))
        assertFalse(ledger.authorized("other", 1))
    }

    @Test fun closeIsAlreadyUnauthorizedBeforeMainCancellationRuns() {
        val ledger = RequestLeaseLedger()
        ledger.grant("old", "request", 15000)
        var mainCancellationRan = false
        val queuedMainCleanup = { mainCancellationRan = true }
        ledger.revokeOwner("old")
        assertFalse(mainCancellationRan)
        assertFalse(ledger.authorized("request", 1))
        queuedMainCleanup()
        assertTrue(mainCancellationRan)
    }

    @Test fun oldConnectionRevocationDoesNotTouchNewOwner() {
        val ledger = RequestLeaseLedger()
        ledger.grant("old", "old-request", 15000)
        ledger.revokeOwner("old")
        ledger.grant("new", "new-request", 15000)
        ledger.revokeOwner("old")
        assertFalse(ledger.authorized("old-request", 1))
        assertTrue(ledger.authorized("new-request", 1))
    }

    @Test fun timeoutRevokesOnlyItsExactRequest() {
        val ledger = RequestLeaseLedger()
        ledger.grant("owner", "editing", 15000)
        ledger.complete("owner", "editing", true)
        ledger.grant("owner", "pending", 15000)
        assertTrue(ledger.revokeRequest("owner", "pending"))
        assertFalse(ledger.authorized("pending", 1))
        assertTrue(ledger.authorized("editing", 30000))
    }

    @Test fun normalCompletionImmediatelyLosesAuthority() {
        val ledger = RequestLeaseLedger()
        ledger.grant("owner", "request", 15000)
        assertTrue(ledger.complete("owner", "request", false))
        assertFalse(ledger.authorized("request", 1))
        assertEquals(0, ledger.size())
    }

    @Test fun editReadySurvivesCommandDeadlineButNotConnectionClose() {
        val ledger = RequestLeaseLedger()
        ledger.grant("owner", "request", 15000)
        assertTrue(ledger.complete("owner", "request", true))
        assertTrue(ledger.authorized("request", 1000000))
        ledger.revokeOwner("owner")
        assertFalse(ledger.authorized("request", 1000001))
    }

    @Test fun nextEditReplacesPreviousEditWithinSameOwner() {
        val ledger = RequestLeaseLedger()
        ledger.grant("owner", "first", 15000)
        ledger.complete("owner", "first", true)
        ledger.grant("owner", "second", 15000)
        ledger.complete("owner", "second", true)
        assertFalse(ledger.authorized("first", 1))
        assertTrue(ledger.authorized("second", 50000))
        assertEquals(1, ledger.size())
    }

    @Test fun editPromotionNeverRevokesAnotherOwnersEdit() {
        val ledger = RequestLeaseLedger()
        ledger.grant("first-owner", "first", 15000)
        ledger.complete("first-owner", "first", true)
        ledger.grant("second-owner", "second", 15000)
        ledger.complete("second-owner", "second", true)
        assertTrue(ledger.authorized("first", 50000))
        assertTrue(ledger.authorized("second", 50000))
    }

    @Test fun wrongOwnerCannotCompleteOrCancelExistingLease() {
        val ledger = RequestLeaseLedger()
        ledger.grant("owner", "request", 15000)
        assertFalse(ledger.complete("wrong", "request", false))
        assertFalse(ledger.revokeRequest("wrong", "request"))
        assertTrue(ledger.authorized("request", 1))
    }

    @Test fun lateCompletionCannotRecreateRevokedLease() {
        val ledger = RequestLeaseLedger()
        ledger.grant("old", "request", 15000)
        ledger.revokeOwner("old")
        assertFalse(ledger.complete("old", "request", true))
        assertFalse(ledger.authorized("request", 1))
    }

    @Test fun capacityIsBoundedWithoutEvictingAnAuthorizedRequest() {
        val ledger = RequestLeaseLedger()
        assertTrue(ledger.grant("owner", "first", 15000))
        assertTrue(ledger.grant("owner", "second", 15000))
        assertFalse(ledger.grant("owner", "third", 15000))
        assertTrue(ledger.authorized("first", 1))
        assertTrue(ledger.authorized("second", 1))
        assertEquals(2, ledger.size())
    }

    @Test fun duplicateGrantCannotExtendDeadlineOrChangeOwner() {
        val ledger = RequestLeaseLedger()
        ledger.grant("owner", "request", 10)
        assertFalse(ledger.grant("new-owner", "request", Long.MAX_VALUE))
        assertFalse(ledger.grant("owner", "request", Long.MAX_VALUE))
        assertFalse(ledger.authorized("request", 10))
    }

    @Test fun unknownMalformedAndNegativeStateFailsClosed() {
        val ledger = RequestLeaseLedger()
        assertFalse(ledger.grant("", "request", 1))
        assertFalse(ledger.grant("owner", "content://private", 1))
        assertFalse(ledger.grant("owner", "request", -1))
        assertFalse(ledger.authorized(null, 1))
        assertFalse(ledger.authorized("request", -1))
        assertEquals(0, ledger.size())
    }

    @Test fun reusedWireIdIsNamespacedByConnection() {
        val oldKey = RequestLeaseLedger.scopedRequestId("old-owner", "same-wire-id")
        val newKey = RequestLeaseLedger.scopedRequestId("new-owner", "same-wire-id")
        assertEquals(64, oldKey.length)
        assertNotEquals(oldKey, newKey)
        assertEquals(oldKey, RequestLeaseLedger.scopedRequestId("old-owner", "same-wire-id"))
        val ledger = RequestLeaseLedger()
        ledger.grant("old-owner", oldKey, 15000)
        ledger.revokeOwner("old-owner")
        ledger.grant("new-owner", newKey, 15000)
        ledger.revokeRequest("old-owner", oldKey)
        ledger.complete("old-owner", oldKey, false)
        assertTrue(ledger.authorized(newKey, 1))
    }

    @Test fun concurrentOwnerClosePublishesRevocationWithoutMainWork() {
        val ledger = RequestLeaseLedger()
        ledger.grant("owner", "request", 15000)
        val revoked = CountDownLatch(1)
        val observedAuthorized = AtomicBoolean(true)
        val done = CountDownLatch(1)
        Thread {
            ledger.revokeOwner("owner")
            revoked.countDown()
        }.start()
        Thread {
            try {
                if (revoked.await(5, TimeUnit.SECONDS)) observedAuthorized.set(ledger.authorized("request", 1))
            } finally { done.countDown() }
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(observedAuthorized.get())
    }

    @Test fun simultaneousGrantsCannotExceedCapacity() {
        val ledger = RequestLeaseLedger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(20)
        val accepted = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        repeat(20) { index ->
            Thread {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    if (ledger.grant("owner", "request-$index", 15000)) accepted.incrementAndGet()
                } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
                finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(2, accepted.get())
        assertEquals(2, ledger.size())
    }
}
