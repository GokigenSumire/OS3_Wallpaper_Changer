package dev.abdus.apps.shimmer.p1

import org.junit.Assert.*
import org.junit.Test

class P1MediaTypesTest {
    @Test fun supportedVideoTypesAreCaseInsensitive() {
        for (ext in listOf("MP4", "mov", "m4v", "webm", "mkv", "3gp", "3gpp", "ts", "m2ts"))
            assertEquals(P1MediaTypes.VIDEO, P1MediaTypes.classify("clip.$ext"))
    }
    @Test fun dynamicPhotosAndAnimatedImagesBelongToImages() {
        for (name in listOf("motion.jpg", "motion.HEIC", "live.heif", "animated.gif", "animation.webp"))
            assertEquals(P1MediaTypes.IMAGE, P1MediaTypes.classify(name))
    }
    @Test fun mimeTypesHandleExtensionlessMediaWithoutDecoding() {
        assertEquals(P1MediaTypes.VIDEO, P1MediaTypes.classify("opaque", "content://item/1", "video/mp4"))
        assertEquals(P1MediaTypes.IMAGE, P1MediaTypes.classify("opaque", "content://item/2", "image/jpeg"))
        assertNull(P1MediaTypes.classify("opaque", "content://item/3", "application/octet-stream"))
    }
    @Test fun readableUriSuffixCanResolveCopiedNames() {
        assertEquals(P1MediaTypes.VIDEO, P1MediaTypes.classify("blob", "file:///data/movie.MP4?ignored=1"))
        assertEquals(P1MediaTypes.IMAGE, P1MediaTypes.classify("blob", "file:///data/image.png"))
    }
}
