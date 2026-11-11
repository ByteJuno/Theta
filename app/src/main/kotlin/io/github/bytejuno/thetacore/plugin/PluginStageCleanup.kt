package io.github.bytejuno.thetacore.plugin

/** Bounded ownership of exact unpublished paths, shared by both stores. All mutations under
 * the manager gate (and IO sequentially); pending flag alone may be read on Main for admission.
 * Register BEFORE creating bytes. Failed deletion retains the action, not just its exception.
 * Live confirmations are never retried. Published paths are NEVER registered.
 */
internal class PluginStageCleanup(private val capacity: Int = 8) {
    internal class Ticket internal constructor(internal val delete: () -> Unit) {
        internal var abandoned = false
    }
    private val tickets = mutableListOf<Ticket>()
    @Volatile var hasPending = false
        private set
    fun reserve(delete: () -> Unit): Ticket {
        dataRequire(!hasPending && tickets.size < capacity, "Import cleanup/capacity pending; retry cleanup before importing", PluginFailureReason.IMPORT_CLEANUP_PENDING)
        return Ticket(delete).also { tickets += it }
    }
    // Cold-start recovery may need to reserve BOTH roots even when the first deletion fails.
    // This never creates bytes or grants import admission.
    fun reserveRecovery(delete: () -> Unit): Ticket {
        dataRequire(tickets.size < capacity, "Recovery cleanup capacity exhausted", PluginFailureReason.IMPORT_CLEANUP_PENDING)
        return Ticket(delete).also { it.abandoned = true; tickets += it; hasPending = true }
    }
    fun discard(ticket: Ticket) {
        if (ticket !in tickets) return // install/cleanup may already have consumed this exact ticket
        ticket.abandoned = true
        hasPending = true
        try {
            ticket.delete()
            tickets.remove(ticket) // only AFTER successful deletion
        } finally { hasPending = tickets.any { it.abandoned } }
    }
    fun retry() {
        var failure: Throwable? = null
        tickets.filter { it.abandoned }.toList().forEach {
            try { discard(it) } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
