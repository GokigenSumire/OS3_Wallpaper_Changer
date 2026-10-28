package dev.abdus.apps.shimmer.p1

import org.junit.Assert.*
import org.junit.Test

class P1ScanProgressReporterTest {
    @Test fun manyNodesDoNotFloodMainThread() {
        var clock = 0L
        val rows = mutableListOf<P1ScanProgress>()
        val reporter = P1ScanProgressReporter({ rows += it }, { clock })
        repeat(11890) { reporter.report(P1ScanProgress(it, it, "")) }
        assertEquals(1, rows.size)
        clock = 150_000_000
        reporter.report(P1ScanProgress(11890, 11890, ""))
        assertEquals(2, rows.size)
        assertEquals(150L, rows.last().elapsedMillis)
    }
    @Test fun phaseTransitionsAndForcedCompletionAreNeverDropped() {
        val rows = mutableListOf<P1ScanProgress>()
        val reporter = P1ScanProgressReporter({ rows += it }, { 0L })
        reporter.report(P1ScanProgress(1, 0, ""))
        reporter.report(P1ScanProgress(2, 1, "", P1ScanPhase.METADATA))
        reporter.report(P1ScanProgress(2, 1, "", P1ScanPhase.METADATA, 1, 1), force = true)
        reporter.report(P1ScanProgress(2, 1, "", P1ScanPhase.COMMIT))
        assertEquals(4, rows.size)
        assertEquals(1, rows[2].processedImages)
    }
    @Test(expected = java.util.concurrent.CancellationException::class)
    fun cancellationFromCallbackIsNotSwallowed() {
        P1ScanProgressReporter({ throw java.util.concurrent.CancellationException() }).report(P1ScanProgress(1, 0, ""))
    }
}
