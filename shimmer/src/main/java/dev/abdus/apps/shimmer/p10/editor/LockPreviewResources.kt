package dev.abdus.apps.shimmer.p10.editor

/**
 * Explicit CPU ownership for queued uploads and the current independent preview. The caller
 * must stop/join the GL thread before [clearAfterRendererStopped] or [closeAfterRendererStopped].
 * A runnable left in GLSurfaceView's queue can then only find an already removed ticket.
 */
internal class LockPreviewResources<T : Any>(private val release: (T) -> Unit) {
    private val uploads = LinkedHashMap<Any, T>()
    private var current: T? = null
    private var closed = false

    @Synchronized fun enqueue(value: T): Any? {
        if (closed) { release(value); return null }
        val ticket = Any()
        uploads[ticket] = value
        return ticket
    }

    @Synchronized fun take(ticket: Any): T? = uploads.remove(ticket)
    @Synchronized fun current(): T? = current

    /** Only called after the new pixels have been synchronously uploaded in the owning GL thread. */
    @Synchronized fun uploaded(value: T) {
        if (closed) { release(value); return }
        val old = current
        current = value
        if (old !== value) old?.let(release)
    }

    @Synchronized fun clearAfterRendererStopped() {
        uploads.values.forEach(release)
        uploads.clear()
        current?.let(release)
        current = null
    }

    @Synchronized fun closeAfterRendererStopped() {
        if (closed) return
        closed = true
        clearAfterRendererStopped()
    }
}
