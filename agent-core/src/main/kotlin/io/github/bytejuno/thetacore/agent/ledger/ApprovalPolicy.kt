package io.github.bytejuno.thetacore.agent.ledger

/** Host shared scheduling gate. Updates and durable claim use this SAME monitor.
 * A bridge must submit the returned ticket before leaving withGate, or recheck here before submission.
 * Never hand this object to a plugin. Refresh failures intentionally remain pending.
 */
class DefinitionGate {
    private val definitions = mutableMapOf<String, FrozenDefinition>()
    private val refreshing = mutableSetOf<String>()
    private val runs = mutableMapOf<String, Map<String, FrozenDefinition>>()
    fun <T> withGate(block: () -> T): T = synchronized(this) { block() }
    fun install(definition: FrozenDefinition) = withGate {
        definitions[definition.toolKey] = definition
        refreshing.remove(definition.toolKey)
    }
    fun beginRefresh(toolKey: String) = withGate { refreshing.add(toolKey); Unit }
    /** A removed tool has nothing left to refresh: its frozen calls must resolve as STALE, not pause forever. */
    fun uninstall(toolKey: String) = withGate { definitions.remove(toolKey); refreshing.remove(toolKey); Unit }
    fun freezeRun(runId: String, tools: Collection<FrozenDefinition>) = withGate {
        val frozen = tools.associateBy { it.modelName }
        require(frozen.size == tools.size)
        require(runs[runId] == null || runs[runId] == frozen) { "Run directory is immutable" }
        runs[runId] = frozen
    }
    internal fun check(input: InvocationInput): DefinitionState = withGate {
        val d = input.definition ?: return@withGate DefinitionState.STALE
        when {
            runs[input.runId]?.get(d.modelName) != d -> DefinitionState.STALE
            d.toolKey in refreshing -> DefinitionState.REFRESH_PENDING
            definitions[d.toolKey] != d -> DefinitionState.STALE
            else -> DefinitionState.CURRENT
        }
    }
}
enum class DefinitionState { CURRENT, STALE, REFRESH_PENDING }

class ApprovalPolicy(
    private val denied: (InvocationInput) -> Boolean = { false },
    private val allowRootLongTerm: () -> Boolean = { false },
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
                other.receipts.any { receipt ->
                    receipt.request.risk >= risk && receipt.request.unknownCalls.isEmpty() &&
                        (receipt.decision == Decision.ALLOW_ALWAYS ||
                            (!i.unattended && receipt.decision == Decision.ALLOW_SESSION && other.input.sessionId == i.sessionId))
                }
        }
        if (grant && permitsLongTerm(risk)) return Verdict.ALLOW
        if (risk == Risk.READ && definition.source == Source.FIRST_PARTY) return Verdict.ALLOW
        return if (i.unattended) Verdict.DENY else Verdict.ASK
    }
}

/** Validation must use original schema, preserve JSON types and return no transformed arguments.
 * This is an integration interface, NOT a production JSON Schema implementation. */
fun interface ArgumentsValidator {
    fun valid(originalSchema: String, objectJson: String): Boolean
}
