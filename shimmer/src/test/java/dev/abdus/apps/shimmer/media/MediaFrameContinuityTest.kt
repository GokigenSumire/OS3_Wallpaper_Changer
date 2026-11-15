package dev.abdus.apps.shimmer.media
import org.junit.Test
import org.junit.Assert.*
class MediaFrameContinuityTest {
    @Test fun resumeRetainsOnlyTheExistingTextureWhileWaiting() {
        assertTrue(MediaFrameContinuity.show(true,false,false,false,true,MediaFrameState.PREPARING))
        assertTrue(MediaFrameContinuity.show(true,false,false,false,true,MediaFrameState.FALLBACK_BUSY))
        assertFalse(MediaFrameContinuity.show(false,false,false,false,true,MediaFrameState.PREPARING))
        assertFalse(MediaFrameContinuity.show(true,false,false,false,false,MediaFrameState.PREPARING))
    }
    @Test fun endedStillAndErrorsCannotReviveRetainedVideo() {
        for (s in listOf(MediaFrameState.STILL,MediaFrameState.ENDED,MediaFrameState.FALLBACK_HDR,
            MediaFrameState.FALLBACK_TIMEOUT,MediaFrameState.FALLBACK_ERROR))
            assertFalse("$s",MediaFrameContinuity.show(true,false,false,false,true,s))
    }
    @Test fun realCurrentAndBoundedTransitionRetainTheirExistingBehavior() {
        assertTrue(MediaFrameContinuity.show(true,true,false,false,false,MediaFrameState.VIDEO))
        assertTrue(MediaFrameContinuity.show(true,false,true,false,false,MediaFrameState.PREPARING))
        assertTrue(MediaFrameContinuity.show(true,false,false,true,false,MediaFrameState.PREPARING))
    }
}
