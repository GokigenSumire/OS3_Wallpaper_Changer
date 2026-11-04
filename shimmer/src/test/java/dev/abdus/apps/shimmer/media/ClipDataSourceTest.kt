package dev.abdus.apps.shimmer.media

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClipDataSourceTest {
    private val bytes = ByteArray(100) { it.toByte() }
    private fun spec(position: Long = 0, length: Long = -1) = DataSpec.Builder().setUri(Uri.parse("content://media/original.jpg")).setPosition(position).setLength(length).build()
    @Test fun everySeekIsRelativeToVideoAndStopsAtItsEnd() {
        val source = ClipDataSource(ByteArrayDataSource(bytes), MediaByteRange(20, 10))
        assertEquals(10L, source.open(spec()))
        val out = ByteArray(100)
        assertEquals(10, source.read(out, 0, out.size))
        assertArrayEquals(bytes.copyOfRange(20, 30), out.copyOf(10))
        assertEquals(-1, source.read(out, 0, 1))
        source.close()
        assertEquals(5L, source.open(spec(5)))
        assertEquals(5, source.read(out, 0, 100))
        assertArrayEquals(bytes.copyOfRange(25, 30), out.copyOf(5))
        source.close()
    }
    @Test fun requestedSubrangeIsHonouredAndCanReopen() {
        val source = ClipDataSource(ByteArrayDataSource(bytes), MediaByteRange(20, 10))
        assertEquals(2L, source.open(spec(3, 2)))
        val out = ByteArray(8)
        assertEquals(2, source.read(out, 0, 8))
        assertArrayEquals(byteArrayOf(23, 24), out.copyOf(2))
        assertEquals(-1, source.read(out, 0, 1))
        source.close()
        assertEquals(0L, source.open(spec(10)))
        assertEquals(-1, source.read(out, 0, 1))
        source.close()
    }
    @Test fun seekBeyondOriginalRangeIsRejected() {
        val source = ClipDataSource(ByteArrayDataSource(bytes), MediaByteRange(20, 10))
        assertThrows(IllegalArgumentException::class.java) { source.open(spec(11)) }
        source.close()
    }
}
