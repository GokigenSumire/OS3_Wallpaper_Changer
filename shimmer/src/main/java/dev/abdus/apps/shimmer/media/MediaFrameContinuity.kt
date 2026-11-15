package dev.abdus.apps.shimmer.media

/** Retaining a same-context frame is display continuity, never a new producer acknowledgement. */
object MediaFrameContinuity {
    fun show(hasFrame: Boolean, current: Boolean, frozen: Boolean, paused: Boolean,
             retained: Boolean, state: MediaFrameState): Boolean = hasFrame &&
        (frozen || paused || current && state in setOf(MediaFrameState.VIDEO, MediaFrameState.PREPARING) ||
            retained && state in setOf(MediaFrameState.PREPARING, MediaFrameState.FALLBACK_BUSY))
}
