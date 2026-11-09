package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** In-process, trusted host API, not an exported Android component or plugin capability.
 * Wire values keep callers independent of R8's internal names. There is not yet a UI/model
 * consumer. Freeze must precede prepare; only the host catalog supplies definitions. No
 * automatic approval, execution at startup, replay, or recovery-barrier bypass is provided.
 * Prepare requires a caller-persisted canonical UUID callId; retries retain every input field.
 * Cold UNKNOWN decisions are the separate issue22 API; get exposes the retained barrier.
 */
internal class HostToolRuntimeApi(private val service: HostToolExecutionService) {
    suspend fun request(encoded: String): String {
        require(encoded.length <= 65_536) { "Host request too large" }
        val request = Json.parseToJsonElement(encoded).jsonObject
        fun field(name: String) = request.getValue(name).jsonPrimitive.content
        return when (field("operation")) {
            "freeze" -> Json.encodeToString(service.catalog.freeze(field("runId")))
            "prepare" -> {
                val run = field("runId")
                val name = field("name")
                val frozen = checkNotNull(service.catalog.frozen(run)) { "Run catalog not frozen" }
                val definition = frozen[name]
                val order = request.getValue("order").jsonPrimitive.int
                val input = InvocationInput(
                    BindingValue("host-api", field("requestId"), null, order, null, field("callId")),
                    field("taskChainId"), field("sessionId"), run, field("stepId"), order,
                    field("argsJson"), definition, request.getValue("unattended").jsonPrimitive.boolean,
                    definition?.declaredRisk ?: Risk.READ, modelName = name,
                )
                mutation(service.prepare(input))
            }
            "decide" -> mutation(service.decide(field("callId"), field("approvalId"), Decision.valueOf(field("decision"))))
            "dispatch" -> mutation(service.dispatch(field("callId")))
            "get" -> service.ledger.get(field("callId"))?.let { Json.encodeToString(it) } ?: "null"
            else -> error("Unknown host operation")
        }
    }

    private fun mutation(value: Mutation): String = buildJsonObject {
        when (value) {
            is Mutation.Applied -> { put("kind", "applied"); put("record", Json.encodeToJsonElement(value.record)) }
            is Mutation.AlreadyApplied -> { put("kind", "alreadyApplied"); put("record", Json.encodeToJsonElement(value.record)) }
            is Mutation.Blocked -> { put("kind", "blocked"); put("reason", value.reason) }
            is Mutation.Conflict -> { put("kind", "conflict"); put("reason", value.reason) }
            is Mutation.StorageFailure -> { put("kind", "storageFailure"); put("reason", value.reason); put("uncertain", value.uncertain) }
        }
    }.toString()
}
