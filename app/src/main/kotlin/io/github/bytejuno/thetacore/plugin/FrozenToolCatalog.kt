package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.ModelToolNames
import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Scoped host registry, not the asynchronously published plugin UI list. Registration, revocation,
 * freeze and dispatch share the ledger's definition gate. Only trusted host code holds this object.
 * External plugin registrations are intentionally NOT bridged by this first host-tool slice.
 */
internal class FrozenToolCatalog(val gate: DefinitionGate, private val budget: PluginExecutionBudget,
    private val owner: CoroutineScope, private val validator: Schema202012Validator) {
    internal data class Entry(val definition: FrozenDefinition, val tool: Tool, val spec: ToolSpec)
    private val entries = mutableMapOf<String, Entry>()
    private val runs = mutableMapOf<String, Map<String, Entry>>()
    private val generations = mutableMapOf<String, Long>()

    suspend fun registerHost(key: String, version: String, tool: Tool): FrozenDefinition {
        require(key.startsWith("host/") && key.length > 5)
        val generation = gate.withGate {
            val next = (generations[key] ?: 0) + 1
            generations[key] = next
            gate.beginRefresh(key)
            next
        }
        val captured = CompletableDeferred<ToolSpec>()
        val lease = budget.submit("host", owner,
            onCancel = { captured.cancel() }, onExit = {}) {
            try {
                val raw = tool.spec
                captured.complete(raw.copy(inputSchema = Json.parseToJsonElement(raw.inputSchema.toString()).jsonObject,
                    outputSchema = raw.outputSchema?.let { Json.parseToJsonElement(it.toString()).jsonObject }))
            } catch (_: Throwable) { captured.completeExceptionally(IllegalArgumentException("Tool metadata failed")) }
        } ?: error("Metadata capacity unavailable; registration remains pending")
        val spec = try { withTimeout(5_000) { captured.await().also { lease.actualExited.await() } } }
            catch (error: Throwable) { lease.cancel(); throw error }
        require(validator.supported(spec.inputSchema.toString())) { "Unsupported input schema" }
        require(spec.outputSchema == null || validator.supported(spec.outputSchema.toString())) { "Unsupported output schema" }
        require(spec.timeoutMs == null || spec.timeoutMs in 1L..600_000L)
        // This standalone alias is provisional; freeze assigns names against the whole directory.
        val definition = FrozenDefinition(key, ModelToolNames.allocate(listOf(key)).getValue(key), spec.name, spec.description,
            spec.inputSchema.toString(), Risk.valueOf(spec.risk.name), Source.FIRST_PARTY, "host", version,
            generation, spec.parallelSafe, spec.exclusiveResource, spec.idempotent, spec.outputSchema?.toString(), spec.timeoutMs)
        return gate.withGate {
            check(generations[key] == generation) { "Registration revoked/replaced during metadata capture" }
            entries[key] = Entry(definition, tool, spec)
            gate.install(definition)
            definition
        }
    }
    fun revoke(key: String) = gate.withGate {
        generations[key] = (generations[key] ?: 0) + 1
        entries.remove(key)
        gate.uninstall(key)
    }
    fun freeze(runId: String): Map<String, FrozenDefinition> = gate.withGate {
        runs[runId]?.let { return@withGate it.mapValues { entry -> entry.value.definition } }
        val names = ModelToolNames.allocate(entries.keys)
        val snapshot = entries.values.map { entry ->
            entry.copy(definition = entry.definition.copy(modelName = names.getValue(entry.definition.toolKey)))
        }.sortedBy { it.definition.modelName }.associateBy { it.definition.modelName }
        check(snapshot.size == entries.size) { "Model tool names must be unique" }
        gate.freezeRun(runId, snapshot.values.map { it.definition })
        runs[runId] = snapshot
        snapshot.mapValues { it.value.definition }
    }
    fun definition(runId: String, name: String): FrozenDefinition? = gate.withGate { runs[runId]?.get(name)?.definition }
    /** Null means this run has no frozen catalog here, which says nothing about whether a name is unknown. */
    fun frozen(runId: String): Map<String, FrozenDefinition>? = gate.withGate { runs[runId]?.mapValues { it.value.definition } }
    fun current(definition: FrozenDefinition): Entry? = gate.withGate {
        entries[definition.toolKey]?.takeIf { it.definition.sameRegistrationAs(definition) }
    }
}
