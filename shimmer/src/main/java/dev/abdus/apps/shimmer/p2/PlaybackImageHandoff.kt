package dev.abdus.apps.shimmer.p2

/**
 * One provisional resource crossing a cancellable dispatcher boundary. The producer captures it
 * before returning. The consumer transfers it only after its real owner has accepted it; otherwise
 * the enclosing finally closes it. No coordinator, persistence or renderer state is changed here.
 */
class PlaybackImageHandoff<T : Any>(private val release: (T) -> Unit) : AutoCloseable {
    private enum class State { EMPTY, OWNED, TRANSFERRED, CLOSED }
    private var state = State.EMPTY
    private var owned: T? = null

    /** Until this succeeds the caller owns [resource]. An envelope accepts at most one resource. */
    @Synchronized
    fun capture(resource: T) {
        check(state == State.EMPTY) { "Playback handoff cannot capture another resource" }
        owned = resource
        state = State.OWNED
    }

    /** Call immediately after the actual owner accepts the identical resource, before other work. */
    @Synchronized
    fun transfer(resource: T) {
        check(state == State.OWNED && owned === resource) { "Playback handoff resource does not match" }
        owned = null
        state = State.TRANSFERRED
    }

    /** Idempotent even if disposal throws; never disposes a resource already transferred. */
    override fun close() {
        val resource = synchronized(this) {
            if (state == State.CLOSED) return
            val held = owned
            owned = null
            state = State.CLOSED
            held
        }
        resource?.let(release)
    }
}
