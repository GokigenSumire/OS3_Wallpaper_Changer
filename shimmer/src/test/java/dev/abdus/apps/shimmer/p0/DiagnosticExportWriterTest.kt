package dev.abdus.apps.shimmer.p0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportWriterTest {
    @Test fun emptyNewDocumentGetsThePinnedUtf8Body() {
        val output = ByteArrayOutputStream()
        DiagnosticExportWriter.write("诊断run一", { ByteArrayInputStream(byteArrayOf()) }, { output })
        assertEquals("诊断run一", output.toString("UTF-8"))
    }

    @Test fun existingDocumentIsNeverOpenedForWritingOrTruncated() {
        var opened = false
        try {
            DiagnosticExportWriter.write("new", { ByteArrayInputStream("old".toByteArray()) }, { opened = true; ByteArrayOutputStream() })
            fail("Must refuse a nonempty target")
        } catch (expected: IllegalStateException) { }
        assertFalse(opened)
    }

    @Test fun unreadableDocumentCannotBeAssumedEmpty() {
        var opened = false
        try { DiagnosticExportWriter.write("new", { null }, { opened = true; ByteArrayOutputStream() }); fail() }
        catch (expected: IllegalStateException) { }
        assertFalse(opened)
    }

    @Test fun readFailureNeverOpensOutputAndDoesNotAlterTheSnapshot() {
        var opened = false; val content = "immutable"
        try { DiagnosticExportWriter.write(content, { throw IOException("injected") }, { opened = true; ByteArrayOutputStream() }); fail() }
        catch (expected: IOException) { }
        assertFalse(opened); assertEquals("immutable", content)
    }

    @Test fun outputFailureIsVisibleAndDoesNotMutateOrClearObservation() {
        val core = DiagnosticRunCore("p", { DiagnosticClockValue(1, 1, 1) })
        core.startPreserving()
        val before = core.snapshot("identity", "EXPORT_METADATA")
        try { DiagnosticExportWriter.write(before.content, { ByteArrayInputStream(byteArrayOf()) }, { throw IOException("injected") }); fail() }
        catch (expected: IOException) { }
        assertEquals(before, core.snapshot("identity", "EXPORT_METADATA"))
    }
}
