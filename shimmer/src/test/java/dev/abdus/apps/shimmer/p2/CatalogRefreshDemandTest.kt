package dev.abdus.apps.shimmer.p2

import org.junit.Assert.*
import org.junit.Test

class CatalogRefreshDemandTest {
    @Test fun initialReadIsRequired() { assertTrue(CatalogRefreshDemand().requiresRead()) }
    @Test fun successfulCurrentReadAcknowledgesOnlyItsTicket() {
        val demand = CatalogRefreshDemand()
        demand.confirm(demand.capture())
        assertFalse(demand.requiresRead())
    }
    @Test fun sourceEditRemainsRequiredAfterSnapshotNotification() {
        val demand = CatalogRefreshDemand()
        demand.confirm(demand.capture())
        demand.requestFull()
        demand.capture() // Lightweight request does not acknowledge any demand.
        assertTrue(demand.requiresRead())
    }
    @Test fun editDuringQueryCannotBeAcknowledgedByOldRead() {
        val demand = CatalogRefreshDemand()
        val old = demand.capture()
        demand.requestFull()
        demand.confirm(old)
        assertTrue(demand.requiresRead())
    }
    @Test fun failureWithoutConfirmationKeepsDemand() {
        val demand = CatalogRefreshDemand()
        demand.capture()
        assertTrue(demand.requiresRead())
    }
    @Test fun cancelledReadThenLightweightRetryStillReads() {
        val demand = CatalogRefreshDemand()
        demand.confirm(demand.capture())
        demand.requestFull()
        val cancelled = demand.capture()
        demand.requestFull()
        demand.confirm(cancelled)
        assertTrue(demand.requiresRead())
    }
    @Test fun duplicateOldCompletionDoesNotOverrideNewDemand() {
        val demand = CatalogRefreshDemand()
        val read = demand.capture()
        demand.confirm(read)
        demand.requestFull()
        demand.confirm(read)
        assertTrue(demand.requiresRead())
    }
    @Test fun ticketFromAnotherEngineCannotAcknowledgeThisEngine() {
        val demand = CatalogRefreshDemand()
        demand.confirm(CatalogRefreshDemand().capture())
        assertTrue(demand.requiresRead())
    }
    @Test fun confirmingNewestReadRestoresLightweightEligibility() {
        val demand = CatalogRefreshDemand()
        repeat(3) { demand.requestFull() }
        demand.confirm(demand.capture())
        assertFalse(demand.requiresRead())
    }
    @Test fun ticketUsesIdentityNotCounterOrWeakHash() {
        val demand = CatalogRefreshDemand()
        val first = demand.capture()
        demand.requestFull()
        assertNotSame(first, demand.capture())
        demand.confirm(first)
        assertTrue(demand.requiresRead())
    }
}
