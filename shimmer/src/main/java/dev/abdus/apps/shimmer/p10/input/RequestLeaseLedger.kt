package dev.abdus.apps.shimmer.p10.input

import personal.wallpaper.protocol.lock.LockProtocol
import java.security.MessageDigest

/**
 * Small, thread-safe authority table. It stores only opaque strings and deadlines, never a
 * Service, Context, callback or Engine. Revocation is synchronous on the Binder thread; Main
 * cancellation is resource cleanup rather than the point at which IO loses its permission.
 */
internal class RequestLeaseLedger(private val capacity: Int = 2) {
    companion object {
        /** Namespace isolation, not a secret/MAC: old wire IDs cannot cancel a new connection. */
        fun scopedRequestId(owner: String, wireRequestId: String): String {
            require(LockProtocol.token(owner) && LockProtocol.token(wireRequestId))
            return MessageDigest.getInstance("SHA-256").digest("$owner\n$wireRequestId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it) }
        }
    }
    private data class Lease(val owner: String, var deadlineElapsed: Long, var editing: Boolean = false)
    private val leases = LinkedHashMap<String, Lease>()

    init { require(capacity in 1..32) }

    @Synchronized fun grant(owner: String, requestId: String, deadlineElapsed: Long): Boolean {
        if (!LockProtocol.token(owner) || !LockProtocol.token(requestId) || deadlineElapsed < 0 ||
            leases.containsKey(requestId) || leases.size >= capacity) return false
        leases[requestId] = Lease(owner, deadlineElapsed)
        return true
    }

    @Synchronized fun authorized(requestId: String?, nowElapsed: Long): Boolean {
        if (requestId == null || nowElapsed < 0) return false
        val lease = leases[requestId] ?: return false
        return nowElapsed < lease.deadlineElapsed
    }

    /**
     * Caller already owns the storage monitor. Only the short logical storage publication
     * belongs here: never wait for Main, Binder, decoding or GL while holding this monitor.
     */
    @Synchronized fun <T : Any> withAuthorization(requestId: String?, nowElapsed: Long, write: () -> T): T? =
        if (authorized(requestId, nowElapsed)) write() else null

    /** Normal completion loses authority. An EDIT_READY lease remains tied to its connection. */
    @Synchronized fun complete(owner: String, requestId: String, editing: Boolean): Boolean {
        val lease = leases[requestId]?.takeIf { it.owner == owner } ?: return false
        if (!editing) {
            leases.remove(requestId)
        } else {
            // A new edit replaces only this owner's previous edit, never another connection.
            leases.entries.removeAll { it.key != requestId && it.value.owner == owner && it.value.editing }
            lease.editing = true
            lease.deadlineElapsed = Long.MAX_VALUE
        }
        return true
    }

    @Synchronized fun revokeRequest(owner: String, requestId: String): Boolean {
        if (leases[requestId]?.owner != owner) return false
        leases.remove(requestId)
        return true
    }

    @Synchronized fun revokeOwner(owner: String) {
        leases.entries.removeAll { it.value.owner == owner }
    }

    @Synchronized fun size(): Int = leases.size
}
