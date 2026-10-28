package dev.abdus.apps.shimmer.p0

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPageSessionTest {
    private var time = 1000L
    private fun core() = DiagnosticRunCore("test-process", { DiagnosticClockValue(time, time, time) })

    @Test fun capturedNameAndBodyRemainFromTheSameRunAcrossPickerAndReset() {
        val run = core(); run.startPreserving()
        val page = DiagnosticPageSession()
        val original = run.snapshot("identity", "EXPORT_METADATA")
        val captured = page.beginExport(original)!!
        time += 50; run.resetAndStart()
        val selected = page.takeExport()!!
        assertEquals(captured, selected)
        assertEquals(original.fileName, selected.fileName)
        assertEquals(original.content, selected.content)
        assertEquals(original.token, selected.token)
        assertNotEquals(run.token(), selected.token)
    }

    @Test fun aSecondPickerCannotOverwriteTheFirstPendingSnapshot() {
        val run = core(); run.startPreserving()
        val page = DiagnosticPageSession()
        val first = page.beginExport(run.snapshot("identity", "EXPORT_METADATA"))!!
        run.resetAndStart()
        assertNull(page.beginExport(run.snapshot("identity", "EXPORT_METADATA")))
        assertTrue(page.hasPendingExport())
        assertEquals(first, page.takeExport())
    }

    @Test fun cancelExportOnlyReleasesPendingSlotAndDoesNotChangeObservation() {
        val run = core(); run.startPreserving()
        val page = DiagnosticPageSession()
        val before = run.snapshot("identity", "SUMMARY_CACHE")
        page.beginExport(before)
        page.takeExport()
        assertFalse(page.hasPendingExport())
        assertNull(page.takeExport())
        assertEquals(before, run.snapshot("identity", "SUMMARY_CACHE"))
        assertNotNull(page.beginExport(before))
    }

    @Test fun missingSnapshotAfterNewPageSessionNeverRecapturesAnotherRun() {
        val oldPage = DiagnosticPageSession(); val run = core()
        oldPage.beginExport(run.snapshot("identity", "EXPORT_METADATA"))
        val freshPage = DiagnosticPageSession()
        assertNull(freshPage.takeExport())
        assertTrue(oldPage.hasPendingExport())
    }

    @Test fun displayInvalidationDoesNotEraseTheCapturedFile() {
        val run = core(); val page = DiagnosticPageSession()
        val captured = page.beginExport(run.snapshot("identity", "EXPORT_METADATA"))!!
        val oldDisplay = page.displayVersion()
        assertTrue(page.invalidateDisplay() > oldDisplay)
        assertEquals(captured, page.takeExport())
    }
}
