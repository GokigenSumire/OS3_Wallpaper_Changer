package dev.abdus.apps.shimmer.p10.lockinput

import dev.abdus.apps.shimmer.p10.lockinput.LockInputEligibility.Decision
import dev.abdus.apps.shimmer.p10.lockinput.LockInputEligibility.Evidence
import dev.abdus.apps.shimmer.p10.lockinput.LockInputEligibility.Fact
import dev.abdus.apps.shimmer.p10.lockinput.LockInputEligibility.Reason
import dev.abdus.apps.shimmer.p10.lockinput.LockInputEligibility.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockInputEligibilityTest {
    private fun completeFacts() = Fact.entries.associateWith { it.required }

    private fun safeSample() = Sample(
        packageName = "com.android.systemui",
        processName = "com.android.systemui",
        displayId = 0,
        targetGeneration = 8,
        expectedTargetGeneration = 8,
        actionMasked = 0,
        pointerCount = 1,
        downTimeUptimeMs = 10,
        eventTimeUptimeMs = 10,
        evidence = Evidence(completeFacts()),
    )

    private fun result(sample: Sample) = LockInputEligibility.evaluate(sample)

    @Test fun completeSafeEvidenceAllowsMetadataOnly() {
        val decision = result(safeSample())
        assertEquals(Reason.ALLOW_METADATA_OBSERVATION, decision.reason)
        assertTrue(decision.allowed)
        assertNull(decision.failedFact)
    }

    @Test fun everyMissingFactIsUnknownRatherThanAssumedSafe() {
        for (fact in Fact.entries) {
            val decision = result(safeSample().copy(evidence = Evidence(completeFacts() - fact)))
            assertEquals(Decision(Reason.REJECT_UNKNOWN_FACT, fact), decision)
            assertFalse(decision.allowed)
        }
    }

    @Test fun emptyEvidenceRejectsEvenOtherwiseMatchingScope() {
        assertEquals(Reason.REJECT_UNKNOWN_FACT, result(safeSample().copy(evidence = Evidence())).reason)
    }

    @Test fun everyInvertedFactRejectsTheWholeGestureCandidate() {
        for (fact in Fact.entries) {
            val values = completeFacts() + (fact to !fact.required)
            assertEquals(
                Decision(Reason.REJECT_UNSAFE_FACT, fact),
                result(safeSample().copy(evidence = Evidence(values))),
            )
        }
    }

    @Test fun homeAndOtherPackagesNeverReuseThisPolicy() {
        for (name in listOf("com.miui.home", "personal.wallpaper.os3", "", "com.android.systemui.other")) {
            assertEquals(Reason.REJECT_SCOPE, result(safeSample().copy(packageName = name)).reason)
        }
    }

    @Test fun secondarySystemUiProcessIsNotSilentlyAccepted() {
        assertEquals(
            Reason.REJECT_SCOPE,
            result(safeSample().copy(processName = "com.android.systemui:other")).reason,
        )
    }

    @Test fun unknownOrSecondaryDisplayRejects() {
        for (display in listOf(-1, 1, 2)) {
            assertEquals(Reason.REJECT_SCOPE, result(safeSample().copy(displayId = display)).reason)
        }
    }

    @Test fun targetGenerationMustBePositiveAndExact() {
        for ((actual, expected) in listOf(0L to 0L, -1L to -1L, 8L to 9L, 9L to 8L)) {
            assertEquals(
                Reason.REJECT_STALE_TARGET,
                result(safeSample().copy(targetGeneration = actual, expectedTargetGeneration = expected)).reason,
            )
        }
    }

    @Test fun cancellationNeverProducesAnObservationPermit() {
        val decision = result(safeSample().copy(actionMasked = 3))
        assertEquals(Reason.RESET_ONLY, decision.reason)
        assertFalse(decision.allowed)
    }

    @Test fun cancellationCannotBypassAuthenticationOrUnknownScope() {
        assertEquals(
            Reason.REJECT_UNSAFE_FACT,
            result(safeSample().copy(
                actionMasked = 3,
                evidence = Evidence(completeFacts() + (Fact.PRIMARY_BOUNCER_SHOWING to true)),
            )).reason,
        )
    }

    @Test fun supportedSinglePointerActionsAreExplicit() {
        for (action in listOf(0, 1, 2)) {
            assertTrue(result(safeSample().copy(actionMasked = action)).allowed)
        }
    }

    @Test fun supportedTwoPointerActionsAreExplicit() {
        for (action in listOf(2, 5, 6)) {
            assertTrue(result(safeSample().copy(actionMasked = action, pointerCount = 2)).allowed)
        }
    }

    @Test fun pointerActionsNeedTheirCompleteCurrentPointerCount() {
        for (action in listOf(5, 6)) {
            assertEquals(
                Reason.REJECT_MALFORMED_EVENT,
                result(safeSample().copy(actionMasked = action, pointerCount = 1)).reason,
            )
        }
        for (action in listOf(0, 1)) {
            assertEquals(
                Reason.REJECT_MALFORMED_EVENT,
                result(safeSample().copy(actionMasked = action, pointerCount = 2)).reason,
            )
        }
    }

    @Test fun zeroOrMoreThanTwoPointersAndNonTouchActionsReject() {
        for (count in listOf(-1, 0, 3, Int.MAX_VALUE)) {
            assertFalse(result(safeSample().copy(actionMasked = 2, pointerCount = count)).allowed)
        }
        for (action in listOf(-1, 4, 7, 8, 9, 10, 11, 12, 255)) {
            assertEquals(
                Reason.REJECT_MALFORMED_EVENT,
                result(safeSample().copy(actionMasked = action)).reason,
            )
        }
    }

    @Test fun missingOrReversedUptimeIsNotACompleteEvent() {
        for ((down, event) in listOf(-1L to 10L, 11L to 10L, 10L to -1L)) {
            assertEquals(
                Reason.REJECT_MALFORMED_EVENT,
                result(safeSample().copy(downTimeUptimeMs = down, eventTimeUptimeMs = event)).reason,
            )
        }
    }

    @Test fun evidenceIsDefensivelyFrozen() {
        val mutable = completeFacts().toMutableMap()
        val evidence = Evidence(mutable)
        mutable[Fact.PRIMARY_BOUNCER_SHOWING] = true
        mutable.clear()
        assertTrue(result(safeSample().copy(evidence = evidence)).allowed)
    }
}
