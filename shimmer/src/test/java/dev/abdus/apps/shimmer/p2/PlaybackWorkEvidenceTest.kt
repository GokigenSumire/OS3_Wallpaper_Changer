package dev.abdus.apps.shimmer.p2

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class PlaybackWorkEvidenceTest {
    @Test fun missingStagesAreUnknownRatherThanClaimedZeroWork() {
        val report = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL).finish()!!
        assertFalse(report.preparationObserved)
        assertFalse(report.storageObserved)
        assertTrue(report.diagnosticFields().contains("catalogItemsVisited=UNKNOWN"))
        assertTrue(report.diagnosticFields().contains("manualIdsEncoded=UNKNOWN"))
    }
    @Test fun actualZeroPreparationIsDistinguishedFromAnUnobservedStage() {
        val work = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
        work.preparation(CoordinatorPreparationWork())
        val report = work.finish()!!
        assertTrue(report.preparationObserved)
        assertFalse(report.storageObserved)
        assertTrue(report.diagnosticFields().contains("catalogItemsVisited=0"))
        assertTrue(report.diagnosticFields().contains("manualIdsEncoded=UNKNOWN"))
    }
    @Test fun preparationCountsAccumulateOnlySuppliedActualCalls() {
        val work = PlaybackWorkEvidence(PlaybackWorkScope.EDITOR)
        work.preparation(CoordinatorPreparationWork(7, 1, 2))
        work.preparation(CoordinatorPreparationWork(3, 0, 1))
        val report = work.finish()!!
        assertEquals(10L, report.catalogItemsVisited)
        assertEquals(1L, report.fullRestoreCount)
        assertEquals(3L, report.preparationQueueRebuilds)
    }
    @Test fun storageCountsUseActualStoreWriteResultsWithoutPretendingBytes() {
        val work = PlaybackWorkEvidence(PlaybackWorkScope.SELECTED)
        work.storage(StoreWriteWork(120, 50, 1, 50))
        work.storage(StoreWriteWork(80, 0, 0, 0))
        val report = work.finish()!!
        assertEquals(200L, report.runtimeCharacters)
        assertEquals(50L, report.manualIdsEncoded)
        assertEquals(1L, report.manualConfigurationWrites)
        assertEquals(50L, report.manualIdsCopied)
        assertFalse(report.diagnosticFields().contains("bytes="))
    }
    @Test fun completedReportIsReturnedExactlyOnceAndLaterMarksCannotAlterIt() {
        val work = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
        work.preparation(CoordinatorPreparationWork(1, 1, 1))
        val report = work.finish()!!
        work.preparation(CoordinatorPreparationWork(900, 1, 1))
        work.storage(StoreWriteWork(900, 20, 1, 20))
        assertNull(work.finish())
        assertEquals(1L, report.catalogItemsVisited)
        assertFalse(report.storageObserved)
    }
    @Test fun simultaneousCompletionPublishesOnlyOneReport() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
            val results = executor.invokeAll((1..8).map { Callable { work.finish() } }).map { it.get() }
            assertEquals(1, results.count { it != null })
        } finally { executor.shutdownNow() }
    }
    @Test fun simultaneousMarksDoNotLoseRequestLocalWork() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
            executor.invokeAll((1..40).map { Callable { work.preparation(CoordinatorPreparationWork(1, 1, 1)) } }).forEach { it.get() }
            val report = work.finish()!!
            assertEquals(40L, report.catalogItemsVisited)
            assertEquals(40L, report.fullRestoreCount)
        } finally { executor.shutdownNow() }
    }
    @Test fun attemptsAndScopesHaveIndependentCountsAndUniqueTraceIds() {
        val first = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
        val second = PlaybackWorkEvidence(PlaybackWorkScope.SELECTED)
        val third = PlaybackWorkEvidence(PlaybackWorkScope.EDITOR)
        first.preparation(CoordinatorPreparationWork(1, 0, 0))
        assertEquals(3, setOf(first.traceId, second.traceId, third.traceId).size)
        assertEquals(PlaybackWorkScope.NORMAL, first.finish()!!.scope)
        assertFalse(second.finish()!!.preparationObserved)
        assertEquals(PlaybackWorkScope.EDITOR, third.finish()!!.scope)
    }
    @Test fun concurrentTraceAllocationDoesNotReuseAttemptIdentity() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val ids = executor.invokeAll((1..80).map { Callable { PlaybackWorkEvidence(PlaybackWorkScope.NORMAL).traceId } }).map { it.get() }
            assertEquals(ids.size, ids.toSet().size)
            assertTrue(ids.all { it > 0 })
        } finally { executor.shutdownNow() }
    }
    @Test fun veryLargeWorkSaturatesRatherThanOverflowingIntoNegativeCounts() {
        assertEquals(Long.MAX_VALUE, PlaybackWorkEvidence.saturatingAdd(Long.MAX_VALUE - 1, 2))
        val work = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
        work.preparation(CoordinatorPreparationWork(Long.MAX_VALUE, 0, 0))
        work.preparation(CoordinatorPreparationWork(1, 0, 0))
        assertEquals(Long.MAX_VALUE, work.finish()!!.catalogItemsVisited)
    }
    @Test fun negativeInputIsRejectedWithoutPublishingFalseCounters() {
        val work = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
        assertThrows(IllegalArgumentException::class.java) { work.preparation(CoordinatorPreparationWork(-1, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { work.preparation(CoordinatorPreparationWork(1, -1, 0)) }
        assertThrows(IllegalArgumentException::class.java) { work.storage(StoreWriteWork(manualIdsEncoded = -1)) }
        assertFalse(work.finish()!!.preparationObserved)
    }
    @Test fun diagnosticFieldsContainNoInventedDecodeGpuOrPrivateImageInformation() {
        val work = PlaybackWorkEvidence(PlaybackWorkScope.EDITOR)
        work.preparation(CoordinatorPreparationWork(12, 1, 2))
        val fields = work.finish()!!.diagnosticFields()
        assertTrue(fields.contains("scope=EDITOR"))
        assertFalse(fields.contains("content://"))
        assertFalse(fields.contains("file:///"))
        assertFalse(fields.contains("decode="))
        assertFalse(fields.contains("gpu="))
        assertFalse(fields.contains("elapsed="))
        assertTrue(fields.contains("preparationQueueRebuilds=2"))
    }
}
