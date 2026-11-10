package io.github.bytejuno.thetacore.agent.ledger

/** Updated only after acknowledged commits; counts owners, not duplicate result/evidence roots. */
internal class BlobReferenceIndex(records: Collection<InvocationRecord>) {
    private val references = mutableMapOf<BlobRef, Int>()
    private val runs = mutableMapOf<String, MutableMap<String, MutableMap<BlobRef, Int>>>()
    private val files = mutableMapOf<String, Int>()
    var rootVisits = 0L
        private set
    var recordVisits = 0L
        private set
    init { records.forEach { replace(null, it) } }
    fun contains(ref: BlobRef) = ref in references
    fun matches(run: String, id: String): Set<BlobRef> = runs[run]?.get(id)?.keys.orEmpty()
    fun liveFiles(): Set<String> = files.keys
    fun liveReferences(): Set<BlobRef> = references.keys
    fun ownsFile(name: String) = name in files
    private fun roots(record: InvocationRecord?): Set<BlobRef> = record?.blobReferences().orEmpty().also {
        if (record != null) recordVisits++
        rootVisits += it.size
    }.toSet()
    private fun names(ref: BlobRef) = listOf("${ref.contentHash}.blob") +
        if (ref.chunksHash != null) listOf("${ref.contentHash}.chunks") else emptyList()
    fun candidates(refs: Collection<BlobRef>): Set<String> = refs.flatMap(::names).toSet()
    private fun <K> adjust(map: MutableMap<K, Int>, key: K, delta: Int) {
        val count = (map[key] ?: 0) + delta
        check(count >= 0)
        if (count == 0) map.remove(key) else map[key] = count
    }
    fun replace(previous: InvocationRecord?, next: InvocationRecord): Set<String> {
        val old = roots(previous)
        val new = roots(next)
        // Run changes are unusual but store replacement must still remove the old run's access.
        val sameRun = previous?.input?.runId == next.input.runId
        val removed = if (sameRun) old - new else old
        val added = if (sameRun) new - old else new
        fun change(run: String, ref: BlobRef, delta: Int) {
            adjust(references, ref, delta)
            names(ref).forEach { adjust(files, it, delta) }
            val ids = runs.getOrPut(run) { mutableMapOf() }
            val refs = ids.getOrPut(ref.id) { mutableMapOf() }
            adjust(refs, ref, delta)
            if (refs.isEmpty()) ids.remove(ref.id)
            if (ids.isEmpty()) runs.remove(run)
        }
        removed.forEach { change(requireNotNull(previous).input.runId, it, -1) }
        added.forEach { change(next.input.runId, it, 1) }
        return candidates(removed)
    }
}
