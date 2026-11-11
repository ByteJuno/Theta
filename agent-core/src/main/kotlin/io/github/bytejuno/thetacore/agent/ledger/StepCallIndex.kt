package io.github.bytejuno.thetacore.agent.ledger

/** Membership only, never cached results or trust decisions. Inputs are immutable in the ledger. */
internal class StepCallIndex(records: Collection<InvocationRecord>) {
    private data class Key(val session: String, val run: String, val step: String)
    private fun key(input: InvocationInput) = Key(input.sessionId, input.runId, input.stepId)
    private val steps = mutableMapOf<Key, MutableList<String>>()
    var callVisits = 0L
        private set

    init {
        records.groupBy { key(it.input) }.forEach { (key, members) ->
            steps[key] = members.sortedBy { it.input.callOrder }.mapTo(mutableListOf()) { it.callId }
        }
    }

    /** Publish only after acknowledgement. Preserve stable callOrder even for sparse loaded steps. */
    fun add(record: InvocationRecord, recordsById: Map<String, InvocationRecord>) {
        val members = steps.getOrPut(key(record.input)) { mutableListOf() }
        val before = members.indexOfFirst { recordsById.getValue(it).input.callOrder > record.input.callOrder }
        members.add(if (before < 0) members.size else before, record.callId)
    }

    fun calls(session: String, run: String, step: String): List<String> =
        steps[Key(session, run, step)].orEmpty().also { callVisits += it.size }
}
