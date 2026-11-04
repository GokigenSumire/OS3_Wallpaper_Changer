package dev.abdus.apps.shimmer.media

import org.junit.Assert.*
import org.junit.Test

class MotionPhotoFileTest {
    private fun inspect(bytes: ByteArray) = MotionPhotoParser.inspect(bytes.size.toLong()) { p, n ->
        if (p < 0 || p > bytes.size || n.toLong() > bytes.size - p) null else bytes.copyOfRange(p.toInt(), p.toInt() + n)
    }
    private fun sample(name: String) = checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }
    @Test fun generatedJpegBothFormatsLocateSameCompleteRealMp4Payload() {
        val legacy = sample("P11-legacy-motion.jpg")
        val modern = sample("P11-standard-motion.MP.jpg")
        val a = inspect(legacy)!!; val b = inspect(modern)!!
        assertEquals(1000000L, a.photoTimestampUs)
        assertEquals(a.video.length, b.video.length)
        assertArrayEquals(legacy.copyOfRange(a.video.offset.toInt(), legacy.size), modern.copyOfRange(b.video.offset.toInt(), modern.size))
        assertTrue(a.video.offset > 1000)
        assertEquals(legacy.size.toLong(), a.video.offset + a.video.length)
    }
    @Test fun strippedTruncatedAndFakeVideoKeepStillInsteadOfGuessing() {
        val bytes = sample("P11-standard-motion.MP.jpg")
        val range = inspect(bytes)!!.video
        assertNull(inspect(bytes.copyOf(range.offset.toInt())))
        assertNull(inspect(bytes.copyOf(bytes.size - 10)))
        val fake = bytes.clone(); fake[range.offset.toInt() + 4] = 'x'.code.toByte()
        assertNull(inspect(fake))
    }
    @Test fun malformedOrUnavailableInputDoesNotReadUnboundedVideo() {
        assertNull(inspect(byteArrayOf(1, 2, 3)))
        assertNull(MotionPhotoParser.inspect(Long.MAX_VALUE) { _, _ -> null })
        assertNull(inspect(byteArrayOf(-1, -40, -1, -31, -1, -1, 0, 0, 0, 0, 0, 0)))
    }
    @Test fun largeVirtualMovieOnlyReadsBoundedHeaderAndItsVideoHeader() {
        val bytes = sample("P11-legacy-motion.jpg")
        val range = inspect(bytes)!!.video
        var totalRead = 0
        val result = MotionPhotoParser.inspect(bytes.size.toLong()) { position, count ->
            totalRead += count
            bytes.copyOfRange(position.toInt(), position.toInt() + count)
        }
        assertEquals(range, result!!.video)
        assertTrue(totalRead < 4096)
    }
}
