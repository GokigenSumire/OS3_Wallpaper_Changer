package dev.abdus.apps.shimmer.media

import org.junit.Assert.*
import org.junit.Test

class MediaContractsTest {
    @Test fun rangesNeverOverflowOrEscapeOriginal() {
        assertTrue(MediaByteRange(10, 20).fits(30))
        assertFalse(MediaByteRange(10, 20).fits(29))
        assertEquals(MediaByteRange(15, 4), MediaByteRange(10, 20).subrange(5, 4))
        assertEquals(MediaByteRange(28, 2), MediaByteRange(10, 20).subrange(18, 200))
        assertEquals(MediaByteRange(10, 20), MediaByteRange(10, 20).subrange(0))
    }
    @Test fun providerOffsetAndDeclaredSubrangeDefineTheReadableOriginal() {
        assertEquals(600L, MediaSourceBounds.length(-1, 700, 100))
        assertEquals(300L, MediaSourceBounds.length(300, 700, 100))
        assertEquals(-1L, MediaSourceBounds.length(601, 700, 100))
        assertEquals(-1L, MediaSourceBounds.length(-1, 99, 100))
        assertEquals(300L, MediaSourceBounds.length(300, -1, 100))
        assertEquals(-1L, MediaSourceBounds.length(-1, -1, 0))
    }
    @Test fun invalidBoundsAreRejected() {
        listOf({ MediaByteRange(-1, 2) }, { MediaByteRange(0, 0) }, { MediaByteRange(Long.MAX_VALUE, 1) },
            { MediaByteRange(10, 20).subrange(20) }, { MediaByteRange(10, 20).subrange(-1) },
            { MediaByteRange(10, 20).subrange(1, -2) }).forEach { action ->
            assertThrows(IllegalArgumentException::class.java) { action() }
        }
    }
    @Test fun codecLeaseIsBoundedAndIdempotent() {
        val budget = MediaDecoderBudget(2)
        val a = Any(); val b = Any(); val c = Any()
        assertTrue(budget.acquire(a)); assertTrue(budget.acquire(a)); assertEquals(1, budget.activeCount)
        assertTrue(budget.acquire(b)); assertFalse(budget.acquire(c)); assertEquals(2, budget.activeCount)
        budget.release(a); budget.release(a)
        assertTrue(budget.acquire(c)); assertEquals(2, budget.activeCount)
        budget.release(b); budget.release(c); assertEquals(0, budget.activeCount)
    }
    @Test fun commonVideoHintsAndOpaqueMimeAreRecognised() {
        assertTrue(MediaFileTypes.isVideo("movie.MP4"))
        assertTrue(MediaFileTypes.isVideo("no-extension", "video/hevc"))
        assertTrue(MediaFileTypes.isMedia("photo.HEIC"))
        assertTrue(MediaFileTypes.isMedia("opaque", "image/jpeg"))
        assertFalse(MediaFileTypes.isVideo("photo.jpg"))
        assertFalse(MediaFileTypes.isMedia("secrets.txt", "text/plain"))
    }
    @Test fun decoderInputCannotTurnMetadataIntoNetworkPlayback() {
        assertThrows(IllegalArgumentException::class.java) { MediaClip("https://example.invalid/movie", MediaKind.VIDEO) }
        assertThrows(IllegalArgumentException::class.java) { MediaClip("javascript:ignored", MediaKind.VIDEO) }
        assertEquals("content://media/1", MediaClip("content://media/1", MediaKind.VIDEO).uri)
    }
    @Test fun portraitAndLandscapeFramesCropWithoutStretching() {
        assertEquals(0.75f, MediaFrameGeometry.crop(16f / 9, 4f / 3).first, 0.0001f)
        assertEquals(1f, MediaFrameGeometry.crop(16f / 9, 4f / 3).second, 0f)
        assertEquals(1f, MediaFrameGeometry.crop(9f / 16, 3f / 4).first, 0f)
        assertEquals(0.75f, MediaFrameGeometry.crop(9f / 16, 3f / 4).second, 0.0001f)
        assertEquals(1f to 1f, MediaFrameGeometry.crop(1f, 1f))
        assertThrows(IllegalArgumentException::class.java) { MediaFrameGeometry.crop(Float.NaN, 1f) }
        assertThrows(IllegalArgumentException::class.java) { MediaFrameGeometry.crop(1f, 0f) }
    }
}
