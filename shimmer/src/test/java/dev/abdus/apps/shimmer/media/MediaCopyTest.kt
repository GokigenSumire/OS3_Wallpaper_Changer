package dev.abdus.apps.shimmer.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MediaCopyTest {
    @Test fun originalStillAndAppendedMotionBytesAreCopiedExactly() = runBlocking {
        val original = ByteArray(200_000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(original.size.toLong(), MediaCopy.transfer(ByteArrayInputStream(original), out))
        assertArrayEquals(original, out.toByteArray())
    }
    @Test fun cancellationIsNotReportedAsOrdinaryFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking { MediaCopy.result<Unit> { throw CancellationException("stop") } }
        }
    }
    @Test fun readFailureDoesNotPretendPartialTransferSucceeded() = runBlocking {
        val out = ByteArrayOutputStream()
        val broken = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("failed") }
        val result = MediaCopy.result { MediaCopy.transfer(broken, out) }
        assertTrue(result.isFailure)
        assertEquals(0, out.size())
    }
}
