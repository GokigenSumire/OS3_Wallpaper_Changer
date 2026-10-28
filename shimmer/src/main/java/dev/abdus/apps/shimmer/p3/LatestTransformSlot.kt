package dev.abdus.apps.shimmer.p3

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Latest-only hand-off of live edit placements to the render thread (REQ-PERF-01): an offer
 * overwrites any placement the renderer has not consumed yet, so a slow frame never replays a
 * backlog of drag steps.
 *
 * Coalesced offers for the same image keep the oldest input time, so the latency sample of the
 * frame that finally shows them covers every merged event instead of only the newest one.
 */
class LatestTransformSlot {
    data class Entry(val imageId: String, val transform: ImageTransform, val inputUptimeMillis: Long)

    private val slot = AtomicReference<Entry?>(null)

    fun offer(imageId: String, transform: ImageTransform, inputUptimeMillis: Long) {
        slot.getAndUpdate { previous ->
            val since = if (previous != null && previous.imageId == imageId && previous.inputUptimeMillis > 0L) {
                if (inputUptimeMillis > 0L) min(previous.inputUptimeMillis, inputUptimeMillis)
                else previous.inputUptimeMillis
            } else inputUptimeMillis
            Entry(imageId, transform, since)
        }
    }

    /** Render thread: takes the newest unconsumed placement, if any. */
    fun take(): Entry? = slot.getAndSet(null)

    /** Drops an unconsumed placement for [imageId]; a committed placement supersedes it. */
    fun discard(imageId: String) {
        slot.updateAndGet { entry -> entry?.takeUnless { it.imageId == imageId } }
    }
}
