package dev.abdus.apps.shimmer.p10.input

/** Main-owned exact LOCK registrations; Binder readers see only this detached immutable state. */
object LockEngineHostRegistry : LockHostEndpoint {
    interface Target {
        fun state(): LockHostState
        fun command(action: Int, ticket: String, requestId: String, complete: (LockHostResult) -> Unit)
        fun cancel(requestId: String)
        fun retire() {}
    }
    private val targets = LinkedHashMap<String, Target>()
    @Volatile private var cached = LockHostState(false, "", "NO_ENGINE")
    private var selected: String? = null

    fun register(id: String, target: Target) {
        if (targets[id] !== target) {
            val previous = targets.values.toList()
            targets.clear()
            previous.forEach { it.retire() }
        }
        targets[id] = target
        refresh()
    }
    fun unregister(id: String, expected: Target? = null) {
        if (expected != null && targets[id] !== expected) return
        targets.remove(id)
        if (selected == id) selected = null
        refresh()
    }
    fun refresh() {
        val entry = targets.entries.lastOrNull { it.value.state().ready }
            ?: targets.entries.lastOrNull()
        selected = entry?.key
        cached = entry?.value?.state() ?: LockHostState(false, "", "NO_ENGINE")
    }
    override fun snapshot(): LockHostState = cached
    override fun dispatch(action: Int, ticket: String, requestId: String, complete: (LockHostResult) -> Unit) {
        refresh()
        val target = selected?.let(targets::get)
        if (target == null) complete(LockHostResult("NO_ENGINE"))
        else if (!cached.ready) complete(LockHostResult(cached.reason))
        else if (ticket != cached.ticket) complete(LockHostResult("STALE_TICKET"))
        else target.command(action, ticket, requestId, complete)
    }
    override fun revoke(session: String) {
        // The argument is an exact request ID, not an epoch: stale disconnects cannot cancel
        // a newer connection or an independently opened foreground edit.
        targets.values.toList().forEach { it.cancel(session) }
        refresh()
    }
}
