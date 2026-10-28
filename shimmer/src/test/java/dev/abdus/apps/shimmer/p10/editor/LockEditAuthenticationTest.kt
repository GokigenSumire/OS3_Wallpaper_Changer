package dev.abdus.apps.shimmer.p10.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockEditAuthenticationTest {
    private fun ticket() = LockEditAuthentication(1_000L, 61_000L)

    @Test fun normalSystemAuthenticationConsumesExactlyOnce() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertTrue(auth.systemSucceeded(5_000L, true, deviceStillLocked = false))
        assertTrue(auth.consume(5_001L, true))
        assertEquals(LockEditAuthentication.Phase.CONSUMED, auth.phase)
        assertFalse(auth.consume(5_002L, true))
        assertFalse(auth.systemSucceeded(5_002L, true, false))
    }

    @Test fun expectedBouncerMayRejectNewInputWithoutCancellingOriginalTicket() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        val inputAdmissionPermitsNewGesture = false
        assertFalse(inputAdmissionPermitsNewGesture)
        // Only the captured long-lived ticket is queried, not the new-gesture epoch.
        assertTrue(auth.valid(10_000L, ticketValid = true))
        assertTrue(auth.systemSucceeded(15_000L, true, false))
        assertTrue(auth.consume(15_001L, true))
    }

    @Test fun merelySeeingUnlockedStateDoesNotAuthorizeOrConsume() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertFalse(auth.consume(2_000L, true))
        assertEquals(LockEditAuthentication.Phase.AUTHENTICATING, auth.phase)
    }

    @Test fun successBeforeAuthenticationWasRequestedIsRejected() {
        val auth = ticket()
        assertFalse(auth.systemSucceeded(1_001L, true, false))
        assertFalse(auth.consume(1_002L, true))
    }

    @Test fun successWhileStillLockedCancelsWithoutExposingImage() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertFalse(auth.systemSucceeded(2_000L, true, true))
        assertEquals(LockEditAuthentication.Phase.CANCELLED, auth.phase)
        assertFalse(auth.consume(2_001L, true))
    }

    @Test fun cancelOrFailureCannotBeReopenedByLateSuccess() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        auth.cancel()
        assertFalse(auth.systemSucceeded(2_000L, true, false))
        assertFalse(auth.begin(2_001L, true))
        assertFalse(auth.consume(2_002L, true))
    }

    @Test fun timeoutBeforeBeginCancels() {
        val auth = ticket()
        assertFalse(auth.begin(61_000L, true))
        assertEquals(LockEditAuthentication.Phase.CANCELLED, auth.phase)
    }

    @Test fun timeoutDuringAuthenticationCancels() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertFalse(auth.systemSucceeded(61_000L, true, false))
        assertEquals(LockEditAuthentication.Phase.CANCELLED, auth.phase)
    }

    @Test fun timeoutBetweenCallbackAndEditorConsumptionCancels() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertTrue(auth.systemSucceeded(60_999L, true, false))
        assertFalse(auth.consume(61_000L, true))
    }

    @Test fun wrongFlagsOrImageConnectionDeathRevokeLongLivedTicket() {
        for (ignored in listOf("flags", "image", "catalog", "binderDeath", "hostEpoch", "engine")) {
            val auth = ticket()
            assertTrue(auth.begin(1_001L, true))
            assertFalse(auth.systemSucceeded(2_000L, ticketValid = false, deviceStillLocked = false))
            assertEquals(LockEditAuthentication.Phase.CANCELLED, auth.phase)
            assertFalse(auth.consume(2_001L, true))
        }
    }

    @Test fun revocationAfterAuthenticationCannotEditAnotherImage() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertTrue(auth.systemSucceeded(2_000L, true, false))
        assertFalse(auth.consume(2_001L, ticketValid = false))
        assertFalse(auth.consume(2_002L, true))
    }

    @Test fun duplicateBeginAndSuccessDoNotResetDeadlineOrPhase() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertFalse(auth.begin(2_000L, true))
        assertTrue(auth.systemSucceeded(3_000L, true, false))
        assertFalse(auth.systemSucceeded(4_000L, true, false))
        assertEquals(61_000L, auth.deadlineElapsedMillis)
        assertTrue(auth.consume(4_001L, true))
    }

    @Test fun elapsedClockBeforeCapturedCreationIsRejected() {
        val auth = ticket()
        assertFalse(auth.begin(999L, true))
        assertEquals(LockEditAuthentication.Phase.CANCELLED, auth.phase)
    }

    @Test fun invalidOrOverflowedDeadlineFailsClosed() {
        val invalid = listOf(-1L to 100L, 1_000L to 1_000L, 1_000L to 999L,
            1_000L to 61_001L, Long.MAX_VALUE - 2 to Long.MIN_VALUE)
        invalid.forEach { (created, deadline) ->
            val auth = LockEditAuthentication(created, deadline)
            assertFalse(auth.begin(created.coerceAtLeast(0L), true))
            assertEquals(LockEditAuthentication.Phase.CANCELLED, auth.phase)
        }
    }

    @Test fun cancellationAfterConsumeDoesNotReuseTransferredOwnership() {
        val auth = ticket()
        assertTrue(auth.begin(1_001L, true))
        assertTrue(auth.systemSucceeded(2_000L, true, false))
        assertTrue(auth.consume(2_001L, true))
        auth.cancel()
        assertEquals(LockEditAuthentication.Phase.CONSUMED, auth.phase)
    }
}
