package dev.abdus.apps.shimmer.p2

/** Sticky catalog demand: a cancelled/lightweight notification cannot acknowledge a later edit. */
class CatalogRefreshDemand {
    class Ticket internal constructor()
    private var requested = Ticket()
    private var confirmed: Ticket? = null

    @Synchronized fun requestFull() { requested = Ticket() }
    @Synchronized fun capture(): Ticket = requested
    @Synchronized fun requiresRead(): Boolean = requested !== confirmed
    @Synchronized fun confirm(ticket: Ticket) {
        if (ticket === requested) confirmed = ticket
    }
}
