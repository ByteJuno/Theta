package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.CallBinding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

@Serializable enum class Risk { READ, WRITE, DEVICE_CONTROL, ROOT }
@Serializable enum class Effect { NONE, APPLIED, UNKNOWN }
@Serializable enum class Status {
    RECEIVED, WAITING_APPROVAL, READY, RUNNING, SUCCEEDED, FAILED, REJECTED, CANCELLED, TIMED_OUT, LOST;
    val terminal: Boolean get() = ordinal >= SUCCEEDED.ordinal
}
@Serializable enum class InstanceState { NOT_SUBMITTED, UNCONFIRMED, EXIT_CONFIRMED }
@Serializable enum class Source { FIRST_PARTY, THIRD_PARTY, MCP }
@Serializable enum class Decision { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY }
@Serializable enum class RecoveryResolution { VERIFIED_CLEAR, ACCEPTED_UNCERTAINTY }

/** Host-owned copy: never persist an implementation supplied by a provider/plugin. */
@Serializable data class BindingValue(
    override val provider: String, override val requestId: String, override val responseId: String?,
    override val index: Int, override val wireId: String?, override val callId: String,
) : CallBinding {
    init { require(UUID.fromString(callId).toString() == callId && callId != wireId && index >= 0) }
    companion object {
        fun copyOf(b: CallBinding) = BindingValue(b.provider, b.requestId, b.responseId, b.index, b.wireId, b.callId)
    }
}

/** Strings, not mutable JSON maps, are the frozen boundary. Digest encoding version is explicit. */
@Serializable data class FrozenDefinition(
    val toolKey: String, val modelName: String, val name: String, val description: String,
    val inputSchema: String, val declaredRisk: Risk, val source: Source,
    val owner: String, val version: String, val generation: Long,
    val parallelSafe: Boolean, val exclusiveResource: String?, val idempotent: Boolean,
    val outputSchema: String?,
    val timeoutMillis: Long? = null,
) {
    init { require(toolKey.contains('/') && owner.isNotBlank() && version.isNotBlank() && generation >= 0) }
    /** Aliases belong to a run, not the registration. All other fields must still match. */
    fun sameRegistrationAs(other: FrozenDefinition): Boolean = copy(modelName = other.modelName) == other

    val digest: String get() = sha256("definition-v1:" + buildJsonObject {
        put("name", name); put("description", description)
        put("inputSchema", Json.parseToJsonElement(inputSchema)); put("risk", declaredRisk.name)
    }.let(::canonical))
}

/** Stable equality for retry matching: object order/whitespace ignored, array order and types retained. */
internal fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
        JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
    }
    is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
    else -> value.toString()
}
internal fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable data class InvocationInput(
    val binding: BindingValue, val taskChainId: String, val sessionId: String, val runId: String,
    val stepId: String, val callOrder: Int, val argsJson: String, val definition: FrozenDefinition?,
    val unattended: Boolean, val initialRisk: Risk,
    /** Set from reducer disposition; never treat a complete truncated candidate as executable. */
    val cancelledBeforeExecution: Boolean = false,
    /** Preserve unresolved names without fabricating a catalog definition. */
    val modelName: String = definition?.modelName.orEmpty(),
) {
    init {
        require(listOf(taskChainId, sessionId, runId, stepId).all { it.isNotBlank() } && callOrder >= 0)
        if (definition != null) {
            require(modelName == definition.modelName)
            require(initialRisk >= definition.declaredRisk)
            require(definition.source != Source.MCP || initialRisk >= Risk.WRITE)
        } else {
            require(modelName.isNotBlank())
            // No executable risk metadata exists; READ is only a neutral storage value.
            require(initialRisk == Risk.READ)
        }
    }
}
@Serializable data class ApprovalRequest(
    val id: String, val callId: String, val digest: String, val version: String, val generation: Long,
    val risk: Risk, val unknownCalls: List<String>, val internal: Boolean,
)
@Serializable data class ApprovalReceipt(val request: ApprovalRequest, val decision: Decision)
@Serializable data class ExecutionInstance(val attemptId: String, val processEpoch: String, val state: InstanceState)
@Serializable data class LogicalResult(val status: Status, val effect: Effect, val text: String, val inconsistentRead: Boolean)
@Serializable data class ExitEvidence(val attemptId: String, val text: String, val outcome: LogicalResult?)
@Serializable data class Isolation(val reason: String, val resource: String?, val resolution: RecoveryResolution?)

/** Aggregate v2 adds a required cold-recovery decision barrier. Pre-release v1 records are
 * rejected rather than guessing whether an earlier host loss was acknowledged. No migration
 * or deletion is automatic; the store identity marker versions the directory, not this schema. */
@Serializable data class InvocationRecord(
    val formatVersion: Int, val revision: Long, val input: InvocationInput,
    val definitionDigest: String?, val state: Status, val effectiveRisk: Risk,
    val executionArgs: String?, val pending: ApprovalRequest?, val receipts: List<ApprovalReceipt>,
    val instance: ExecutionInstance?, val result: LogicalResult?, val evidence: List<ExitEvidence>,
    val isolation: Isolation?, val restartRequired: Boolean, val recoveryPending: Boolean,
    /** Only a timed-out call's grace-expiry restriction is released by actual exit. Cancelled calls and legacy/other causes stay sticky. */
    val restartRequiredUntilExit: Boolean = false,
) {
    val callId: String get() = input.binding.callId
    fun validate() {
        require(formatVersion == 2 && revision > 0) { "Unsupported format or revision" }
        require(!recoveryPending || (state.terminal && instance?.state == InstanceState.EXIT_CONFIRMED && isolation != null))
        require(!restartRequiredUntilExit || (restartRequired && instance?.state == InstanceState.UNCONFIRMED &&
            state == Status.TIMED_OUT))
        require(definitionDigest == input.definition?.digest && effectiveRisk >= input.initialRisk)
        if (input.definition == null) {
            require(state == if (input.cancelledBeforeExecution) Status.CANCELLED else Status.REJECTED)
            require(effectiveRisk == Risk.READ && result?.effect == Effect.NONE)
            require(executionArgs == null && pending == null && receipts.isEmpty() && instance == null)
            require(evidence.isEmpty() && isolation == null && !restartRequired && !recoveryPending)
        }
        require(state.terminal == (result != null) && (result == null || state == result.status))
        require(state != Status.RUNNING || instance != null)
        require(state != Status.READY || (executionArgs != null && instance == null && pending == null))
        require(state != Status.WAITING_APPROVAL || (pending != null && !pending.internal && executionArgs != null && instance == null))
        require(!state.terminal || pending == null)
        require(instance == null || executionArgs != null)
        require(instance == null || instance.state != InstanceState.NOT_SUBMITTED)
        require(instance == null || (UUID.fromString(instance.attemptId).toString() == instance.attemptId && instance.processEpoch.isNotBlank()))
        require(state !in setOf(Status.SUCCEEDED, Status.FAILED, Status.LOST) || instance != null)
        require(state != Status.REJECTED || (instance == null && result?.effect == Effect.NONE))
        require(result == null || instance != null || result.effect == Effect.NONE)
        require(result == null || result.inconsistentRead == (effectiveRisk == Risk.READ && result.effect != Effect.NONE))
        require(pending == null || (pending.internal == (state == Status.RUNNING)))
        require(pending == null || receipts.none { it.request.id == pending.id })
        require(evidence.all { it.attemptId == instance?.attemptId })
        require(isolation == null || isolation.resource == input.definition?.exclusiveResource)
        require(state != Status.RECEIVED || (instance == null && pending == null))
        if (executionArgs != null) {
            require(Json.parseToJsonElement(executionArgs) is JsonObject)
            val original = if (input.argsJson.isBlank()) JsonNull else Json.parseToJsonElement(input.argsJson)
            require(executionArgs == if (original == JsonNull) "{}" else input.argsJson) { "Frozen arguments differ from original" }
        }
        require(receipts.map { it.request.id }.distinct().size == receipts.size)
        for (request in receipts.map { it.request } + listOfNotNull(pending)) {
            require(request.callId == callId && request.digest == definitionDigest &&
                request.version == input.definition?.version && request.generation == input.definition.generation)
            require(UUID.fromString(request.id).toString() == request.id)
            require(request.unknownCalls == request.unknownCalls.distinct().sorted() && callId !in request.unknownCalls)
            require(request.risk >= input.initialRisk)
            require(request.internal || request.risk == input.initialRisk)
            require(!request.internal || instance != null)
        }
        require(pending == null || !pending.internal || pending.risk > effectiveRisk)
        require(receipts.filter { it.decision != Decision.DENY }.all { it.request.risk <= effectiveRisk })
        require(isolation != null || (result?.effect != Effect.UNKNOWN && evidence.none { it.outcome?.effect == Effect.UNKNOWN })) {
            "Unknown effect without isolation"
        }
    }
}

sealed interface Mutation {
    data class Applied(val record: InvocationRecord, val ticket: ExecutionTicket? = null) : Mutation
    data class AlreadyApplied(val record: InvocationRecord) : Mutation
    data class Conflict(val reason: String) : Mutation
    data class Blocked(val reason: String) : Mutation
    data class StorageFailure(val reason: String, val uncertain: Boolean) : Mutation
}
/** Only claim emits a ticket, after storage acknowledgement. Approval never emits one. */
data class ExecutionTicket(val callId: String, val attemptId: String, val processEpoch: String,
    val definition: FrozenDefinition, val argsJson: String)
