package dev.abdus.apps.shimmer.media

/** A visibility refresh is a sample, not a new lock/unlock event. Main-looper owned. */
class MediaRestartPolicy {
    private var locked: Boolean? = null
    fun observe(next: Boolean, onLock: Boolean, onUnlock: Boolean): Boolean {
        val previous = locked
        locked = next
        return previous != null && previous != next && if (next) onLock else onUnlock
    }
}
