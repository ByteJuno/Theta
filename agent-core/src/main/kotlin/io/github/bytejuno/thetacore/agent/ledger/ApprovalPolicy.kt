package io.github.bytejuno.thetacore.agent.ledger

/** Host shared scheduling gate. Updates and durable claim use this SAME monitor.
 * A bridge must submit the returned ticket before leaving withGate, or recheck here before submission.
 * Never hand this object to a plugin. Refresh failures intentionally remain pending.
 */
class DefinitionGate {
    private val definitions = mutableMapOf<String, FrozenDefinition>()
    private val refreshing = mutableSetOf<String>()
    private val runs = mutableMapOf<String, Map<String, FrozenDefinition>>()
    private data class Freshness(val keys: Set<String>, val expires: Long, val expired: () -> Unit)
    private val freshness = mutableMapOf<String, Freshness>()
    fun setFreshness(owner: String, keys: Set<String>, ttlMs: Long, expired: () -> Unit) = withGate {
        freshness[owner] = Freshness(keys, System.nanoTime() + ttlMs * 1_000_000L, expired)
    }
    fun clearFreshness(owner: String) = withGate { freshness.remove(owner); Unit }
    /** Checked under the exact same monitor as durable admission, not just by a delayed UI timer. */
    fun checkFreshness() = withGate {
        val expired = freshness.filterValues { System.nanoTime() - it.expires >= 0 }
        expired.forEach { (owner, value) ->
            freshness.remove(owner)
            refreshing.addAll(value.keys)
            value.expired()
        }
    }
    fun <T> withGate(block: () -> T): T = synchronized(this) { block() }
    fun install(definition: FrozenDefinition) = withGate {
        definitions[definition.toolKey] = definition
        refreshing.remove(definition.toolKey)
    }
    fun beginRefresh(toolKey: String) = withGate { refreshing.add(toolKey); Unit }
    /** A removed tool has nothing left to refresh: its frozen calls must resolve as STALE, not pause forever. */
    fun uninstall(toolKey: String) = withGate { definitions.remove(toolKey); refreshing.remove(toolKey); Unit }
    fun freezeRun(runId: String, tools: Collection<FrozenDefinition>) = withGate {
        restoreRun(runId, tools)
        Unit
    }
    /** Restore a persisted run catalog without re-allocating aliases from current tools.
     * Idempotent for an identical mapping; rejects any attempt to alter an immutable run catalog. */
    fun restoreRun(runId: String, tools: Collection<FrozenDefinition>): Map<String, FrozenDefinition> = withGate {
        require(runId.isNotBlank()) { "Run id must not be blank" }
        val frozen = tools.sortedBy { it.modelName }.associateBy { it.modelName }
        require(frozen.size == tools.size) { "Duplicate model tool name in run catalog" }
        require(tools.map { it.toolKey }.distinct().size == tools.size) { "Duplicate tool key in run catalog" }
        require(runs[runId] == null || runs[runId] == frozen) { "Run directory is immutable" }
        runs[runId] = frozen
        LinkedHashMap(frozen)
    }
    fun restoreRuns(catalogs: Map<String, Collection<FrozenDefinition>>) = withGate {
        catalogs.forEach { (runId, tools) -> restoreRun(runId, tools) }
    }
    fun frozenRun(runId: String): Map<String, FrozenDefinition>? = withGate {
        runs[runId]?.let { LinkedHashMap(it) }
    }
    val frozenRunCount: Int get() = withGate { runs.size }
    fun releaseRun(runId: String) = withGate { runs.remove(runId); Unit }
    internal fun check(input: InvocationInput): DefinitionState = withGate {
        checkFreshness()
        val d = input.definition ?: return@withGate DefinitionState.STALE
        when {
            runs[input.runId]?.get(d.modelName) != d -> DefinitionState.STALE
            d.toolKey in refreshing -> DefinitionState.REFRESH_PENDING
            definitions[d.toolKey]?.sameRegistrationAs(d) != true -> DefinitionState.STALE
            else -> DefinitionState.CURRENT
        }
    }
}
enum class DefinitionState { CURRENT, STALE, REFRESH_PENDING }

enum class ApprovalMode {
    STRICT,
    BALANCED,
    FULL_AUTO,
}

class ApprovalPolicy(
    private val denied: (InvocationInput) -> Boolean = { false },
    private val allowRootLongTerm: () -> Boolean = { false },
    private val mode: () -> ApprovalMode = { ApprovalMode.STRICT },
) {
    internal enum class Verdict { ALLOW, ASK, DENY }
    internal fun permitsLongTerm(risk: Risk) = risk != Risk.ROOT || allowRootLongTerm()
    internal fun isDenied(input: InvocationInput) = denied(input)
    internal fun evaluate(record: InvocationRecord, risk: Risk, unknown: List<String>, all: List<InvocationRecord>): Verdict {
        val i = record.input
        val definition = i.definition ?: return Verdict.DENY
        if (denied(i)) return Verdict.DENY
        if (unknown.isNotEmpty()) return if (i.unattended) Verdict.DENY else Verdict.ASK
        val grant = all.any { other ->
            other.input.definition?.toolKey == definition.toolKey && other.definitionDigest == record.definitionDigest &&
                (definition.source != Source.MCP || other.input.definition?.version == definition.version) &&
                other.receipts.any { receipt ->
                    receipt.request.risk >= risk && receipt.request.unknownCalls.isEmpty() &&
                        (receipt.decision == Decision.ALLOW_ALWAYS ||
                            (!i.unattended && receipt.decision == Decision.ALLOW_SESSION && other.input.sessionId == i.sessionId))
                }
        }
        if (grant && permitsLongTerm(risk)) return Verdict.ALLOW
        if (i.unattended) {
            return if (risk == Risk.READ && definition.source == Source.FIRST_PARTY) Verdict.ALLOW else Verdict.DENY
        }
        return when (mode()) {
            ApprovalMode.STRICT ->
                if (risk == Risk.READ && definition.source == Source.FIRST_PARTY) Verdict.ALLOW else Verdict.ASK
            ApprovalMode.BALANCED ->
                if (risk == Risk.READ || risk == Risk.WRITE) Verdict.ALLOW else Verdict.ASK
            ApprovalMode.FULL_AUTO -> Verdict.ALLOW
        }
    }
}

/** Validation must use original schema, preserve JSON types and return no transformed arguments.
 * This is an integration interface, NOT a production JSON Schema implementation. */
fun interface ArgumentsValidator {
    fun valid(originalSchema: String, objectJson: String): Boolean
}
