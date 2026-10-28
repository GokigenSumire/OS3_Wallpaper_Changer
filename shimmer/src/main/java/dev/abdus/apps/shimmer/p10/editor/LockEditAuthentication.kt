package dev.abdus.apps.shimmer.p10.editor

/**
 * Single-use authentication lifecycle, using elapsedRealtime rather than wall/uptime clocks.
 * [ticketValid] is the Engine's long-lived original-image/connection ticket, not the transient
 * input-admission epoch: opening the expected system bouncer must not revoke its own request.
 * This class cannot decide that authentication succeeded; only the system callback may do so.
 */
internal class LockEditAuthentication(
    val createdElapsedMillis: Long,
    val deadlineElapsedMillis: Long,
) {
    enum class Phase { CREATED, AUTHENTICATING, AUTHENTICATED, CONSUMED, CANCELLED }

    var phase: Phase = if (createdElapsedMillis >= 0L &&
        deadlineElapsedMillis > createdElapsedMillis &&
        deadlineElapsedMillis - createdElapsedMillis <= MAX_AUTH_MILLIS) Phase.CREATED else Phase.CANCELLED
        private set

    fun begin(now: Long, ticketValid: Boolean): Boolean {
        if (!valid(now, ticketValid) || phase != Phase.CREATED) return false
        phase = Phase.AUTHENTICATING
        return true
    }

    fun systemSucceeded(now: Long, ticketValid: Boolean, deviceStillLocked: Boolean): Boolean {
        if (!valid(now, ticketValid)) return false
        if (phase != Phase.AUTHENTICATING) return false
        if (deviceStillLocked) {
            cancel()
            return false
        }
        phase = Phase.AUTHENTICATED
        return true
    }

    /** A duplicate success callback or second Activity cannot open another editor. */
    fun consume(now: Long, ticketValid: Boolean): Boolean {
        if (!valid(now, ticketValid) || phase != Phase.AUTHENTICATED) return false
        phase = Phase.CONSUMED
        return true
    }

    fun cancel() {
        if (phase != Phase.CONSUMED) phase = Phase.CANCELLED
    }

    fun valid(now: Long, ticketValid: Boolean): Boolean {
        if (phase == Phase.CANCELLED || phase == Phase.CONSUMED) return false
        if (!ticketValid || now < createdElapsedMillis || now >= deadlineElapsedMillis) {
            cancel()
            return false
        }
        return true
    }

    companion object { const val MAX_AUTH_MILLIS = 60_000L }
}
