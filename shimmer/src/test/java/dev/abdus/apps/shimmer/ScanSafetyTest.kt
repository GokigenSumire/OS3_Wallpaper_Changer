package dev.abdus.apps.shimmer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * P0-A01: exercise the actual scanner/commit gate with injected source failures. These are
 * pure JVM boundary tests, not evidence for Android providers or Room rollback on a device.
 */
class ScanSafetyTest {
    private data class Image(val id: String, val favoriteRank: Int)

    private val original = Image("A", 9)

    private class Index(initial: Image) {
        val records = mutableMapOf(initial.id to initial)
        var successfulScans = 0
        suspend fun accept(result: ScanResult<Image>, checkActive: () -> Unit = {}): Boolean =
            commitCompletedScan(result, checkActive) { images ->
                // Mirrors the Room INSERT IGNORE contract; no deletion capability is needed.
                images.forEach { records.putIfAbsent(it.id, it) }
                successfulScans++
            }
    }

    private fun assertFailureRetainsIndex(expected: ScanFailure, enumerate: () -> List<Image>) = runBlocking {
        val index = Index(original)
        val outcome = captureScan({}, enumerate)
        assertEquals(ScanResult.Failed(expected), outcome)
        assertFalse(index.accept(outcome))
        assertEquals(mapOf("A" to original), index.records)
        assertEquals(0, index.successfulScans)
    }

    @Test fun nullQueryHandleDoesNotBecomeAnEmptyCommit() {
        assertFailureRetainsIndex(ScanFailure.NULL_RESULT) {
            throw ScanException(ScanFailure.NULL_RESULT)
        }
    }

    @Test fun exceptionAfterPartialEnumerationDoesNotCommitThePartialSet() {
        assertFailureRetainsIndex(ScanFailure.READ) {
            buildList {
                add(Image("B", 0))
                throw IOException("provider stopped mid-cursor")
            }
        }
    }

    @Test fun providerLoadingResultDoesNotCommit() {
        assertFailureRetainsIndex(ScanFailure.INCOMPLETE_RESULT) {
            throw ScanException(ScanFailure.INCOMPLETE_RESULT)
        }
    }

    @Test fun revokedPermissionRetainsCommittedConfiguration() {
        assertFailureRetainsIndex(ScanFailure.PERMISSION) { throw SecurityException() }
    }

    @Test fun temporaryDecodeFailureRetainsCommittedConfiguration() {
        assertFailureRetainsIndex(ScanFailure.METADATA_DECODE) {
            throw ScanException(ScanFailure.METADATA_DECODE)
        }
    }

    @Test fun cancellationDuringEnumerationIsRethrownWithoutCommit() = runBlocking {
        val index = Index(original)
        val cancelled = CancellationException("injected during enumeration")
        try {
            val outcome = captureScan<Image>({}) {
                buildList {
                    add(Image("B", 0))
                    throw cancelled
                }
            }
            index.accept(outcome)
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertEquals(mapOf("A" to original), index.records)
        assertEquals(0, index.successfulScans)
    }

    @Test fun cancellationAfterLastEnumeratedItemPreventsCommit() = runBlocking {
        val index = Index(original)
        var cancelled = false
        try {
            val outcome = captureScan({ if (cancelled) throw CancellationException() }) {
                cancelled = true
                listOf(Image("B", 0))
            }
            index.accept(outcome)
            fail("Post-enumeration cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(mapOf("A" to original), index.records)
            assertEquals(0, index.successfulScans)
        }
    }

    @Test fun cancellationBetweenScanAndTransactionPreventsCommit() = runBlocking {
        val index = Index(original)
        val outcome = captureScan({}) { listOf(Image("B", 0)) }
        try {
            index.accept(outcome) { throw CancellationException() }
            fail("Pre-commit cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(mapOf("A" to original), index.records)
            assertEquals(0, index.successfulScans)
        }
    }

    @Test fun completeEmptyScanIsDistinctFromFailureAndRetainsConfiguration() = runBlocking {
        val index = Index(original)
        val outcome = captureScan<Image>({}) { emptyList() }
        assertTrue(outcome is ScanResult.Complete)
        assertTrue(index.accept(outcome))
        assertEquals(mapOf("A" to original), index.records)
        assertEquals(1, index.successfulScans)
    }

    @Test fun successfulRetryAddsNewImagesWithoutOverwritingExistingConfiguration() = runBlocking {
        val index = Index(original)
        assertFalse(index.accept(captureScan<Image>({}) { throw SecurityException() }))
        assertTrue(index.accept(captureScan({}) { listOf(Image("A", 0), Image("B", 0)) }))
        assertEquals(mapOf("A" to original, "B" to Image("B", 0)), index.records)
        assertEquals(1, index.successfulScans)
    }

    @Test fun transactionFailurePropagatesInsteadOfReportingSuccess() = runBlocking {
        val failure = IOException("injected transaction failure")
        try {
            commitCompletedScan(ScanResult.Complete(listOf(original)), {}) { throw failure }
            fail("A failed transaction cannot be reported as a successful scan")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
    }
}
