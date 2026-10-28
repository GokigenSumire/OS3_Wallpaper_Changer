package dev.abdus.apps.shimmer.p1

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewThumbnailLoaderTest {
    @Test
    fun cameraPhotoIsSampledDownToCardSizeWithoutGoingBelowIt() {
        // 4000x3000 to a 216px card: 3000 / 8 = 375 >= 216 but 3000 / 16 = 187 < 216.
        assertEquals(8, PreviewThumbnailLoader.calculateSampleSize(4000, 3000, 216))
        assertEquals(64, PreviewThumbnailLoader.calculateSampleSize(12_000, 16_000, 144))
    }

    @Test
    fun smallOrUnknownImagesAreNotSampled() {
        assertEquals(1, PreviewThumbnailLoader.calculateSampleSize(200, 200, 216))
        assertEquals(1, PreviewThumbnailLoader.calculateSampleSize(-1, -1, 216))
        assertEquals(1, PreviewThumbnailLoader.calculateSampleSize(4000, 3000, 0))
    }
}
