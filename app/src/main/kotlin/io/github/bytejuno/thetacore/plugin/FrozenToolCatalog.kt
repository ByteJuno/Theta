package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.ModelToolNames
import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Scoped host registry, not the asynchronously published plugin UI list. Registration, revocation,
 * freeze and dispatch share the ledger's definition gate. Only trusted host code holds this object.
 */
internal class FrozenToolCatalog(
    val gate: DefinitionGate,
    private val budget: PluginExecutionBudget,
    private val owner: CoroutineScope,
    private val validator: Schema202012Validator,
    private val store: RunCatalogStore? = null,
) {
    internal data class Entry(val definition: FrozenDefinition, val tool: Tool, val spec: ToolSpec)
    private val entries = mutableMapOf<String, Entry>()
    private val runs = mutableMapOf<String, Map<String, FrozenDefinition>>()
    private val generations = mutableMapOf<String, Long>()
    private val pluginEpochs = mutableMapOf<String, Long>()

    suspend fun registerHost(key: String, version: String, tool: Tool): FrozenDefinition {
        require(key.startsWith("host/") && key.length > 5)
        return registerKnownKey(
            key = key,
            budgetDomain = key,
            source = Source.FIRST_PARTY,
            ownerId = "host",
            version = version,
            tool = tool,
            pluginEpoch = null,
        )
    }

    suspend fun registerPlugin(
        pluginId: String,
        version: String,
        tool: Tool,
        toolId: String? = null,
    ): FrozenDefinition {
        validatePluginOwner(pluginId)
        validateVersion(version)
        if (toolId != null) {
            validatePluginToolId(toolId)
            val key = "$pluginId/$toolId"
            require(!key.startsWith("host/") && !key.startsWith("mcp/")) { "Reserved tool prefix" }
            val epoch = gate.withGate { pluginEpochs[pluginId] ?: 0L }
            return registerKnownKey(
                key = key,
                budgetDomain = pluginId,
                source = Source.THIRD_PARTY,
                ownerId = pluginId,
                version = version,
                tool = tool,
                pluginEpoch = epoch,
            )
        }
        val epoch = gate.withGate { pluginEpochs[pluginId] ?: 0L }
        val captured = CompletableDeferred<Pair<String, ToolSpec>>()
        val lease = budget.submit(
            pluginId,
            owner,
            onCancel = { captured.cancel() },
            onExit = {},
        ) {
            try {
                val id = tool.id.toString()
                val detached = detachSpec(tool.spec)
                captured.complete(id to detached)
            } catch (_: Throwable) {
                captured.completeExceptionally(IllegalArgumentException("Tool metadata failed"))
            }
        } ?: error("Metadata capacity unavailable; registration remains pending")
        val (resolvedId, spec) = try {
            withTimeout(5_000) {
                captured.await().also { lease.actualExited.await() }
            }
        } catch (error: Throwable) {
            lease.cancel()
            throw error
        }
        validatePluginToolId(resolvedId)
        validateSpec(spec)
        val key = "$pluginId/$resolvedId"
        require(!key.startsWith("host/") && !key.startsWith("mcp/")) { "Reserved tool prefix" }
        return gate.withGate {
            check((pluginEpochs[pluginId] ?: 0L) == epoch) { "Plugin revoked during metadata capture" }
            val generation = (generations[key] ?: 0) + 1
            generations[key] = generation
            val definition = buildDefinition(
                key = key,
                spec = spec,
                source = Source.THIRD_PARTY,
                ownerId = pluginId,
                version = version,
                generation = generation,
            )
            entries[key] = Entry(definition, tool, spec)
            gate.install(definition)
            definition
        }
    }

    private suspend fun registerKnownKey(
        key: String,
        budgetDomain: String,
        source: Source,
        ownerId: String,
        version: String,
        tool: Tool,
        pluginEpoch: Long?,
    ): FrozenDefinition {
        val generation = gate.withGate {
            if (pluginEpoch != null) {
                check((pluginEpochs[ownerId] ?: 0L) == pluginEpoch) { "Plugin revoked before metadata capture" }
            }
            val next = (generations[key] ?: 0) + 1
            generations[key] = next
            gate.beginRefresh(key)
            next
        }
        try {
            val captured = CompletableDeferred<ToolSpec>()
            val lease = budget.submit(
                budgetDomain,
                owner,
                onCancel = { captured.cancel() },
                onExit = {},
            ) {
                try {
                    captured.complete(detachSpec(tool.spec))
                } catch (_: Throwable) {
                    captured.completeExceptionally(IllegalArgumentException("Tool metadata failed"))
                }
            } ?: error("Metadata capacity unavailable; registration remains pending")
            val spec = try {
                withTimeout(5_000) { captured.await().also { lease.actualExited.await() } }
            } catch (error: Throwable) {
                lease.cancel()
                throw error
            }
            validateSpec(spec)
            val definition = buildDefinition(
                key = key,
                spec = spec,
                source = source,
                ownerId = ownerId,
                version = version,
                generation = generation,
            )
            return gate.withGate {
                check(generations[key] == generation) { "Registration revoked/replaced during metadata capture" }
                if (pluginEpoch != null) {
                    check((pluginEpochs[ownerId] ?: 0L) == pluginEpoch) { "Plugin revoked during metadata capture" }
                }
                entries[key] = Entry(definition, tool, spec)
                gate.install(definition)
                definition
            }
        } catch (error: Throwable) {
            gate.withGate {
                if (generations[key] == generation) {
                    entries.remove(key)
                    gate.uninstall(key)
                }
            }
            throw error
        }
    }

    private fun detachSpec(raw: ToolSpec): ToolSpec = raw.copy(
        name = raw.name.toString(),
        description = raw.description.toString(),
        inputSchema = Json.parseToJsonElement(raw.inputSchema.toString()).jsonObject,
        outputSchema = raw.outputSchema?.let { Json.parseToJsonElement(it.toString()).jsonObject },
        exclusiveResource = raw.exclusiveResource?.toString(),
    )

    private fun validateSpec(spec: ToolSpec) {
        require(spec.name.isNotBlank()) { "Tool name must not be blank" }
        require(validator.supported(spec.inputSchema.toString())) { "Unsupported input schema" }
        require(spec.outputSchema == null || validator.supported(spec.outputSchema.toString())) { "Unsupported output schema" }
        require(spec.timeoutMs == null || spec.timeoutMs in 1L..600_000L) { "Tool timeout out of bounds" }
        require(spec.exclusiveResource == null || (spec.exclusiveResource!!.isNotBlank() && spec.exclusiveResource!!.length <= 256)) {
            "Invalid exclusiveResource"
        }
    }

    private fun buildDefinition(
        key: String,
        spec: ToolSpec,
        source: Source,
        ownerId: String,
        version: String,
        generation: Long,
    ): FrozenDefinition =
        // This standalone alias is provisional; freeze assigns names against the whole directory.
        FrozenDefinition(
            key,
            ModelToolNames.allocate(listOf(key)).getValue(key),
            spec.name,
            spec.description,
            spec.inputSchema.toString(),
            Risk.valueOf(spec.risk.name),
            source,
            ownerId,
            version,
            generation,
            spec.parallelSafe,
            spec.exclusiveResource,
            spec.idempotent,
            spec.outputSchema?.toString(),
            spec.timeoutMs,
        )

    fun revoke(key: String) = gate.withGate {
        generations[key] = (generations[key] ?: 0) + 1
        entries.remove(key)
        gate.uninstall(key)
    }

    fun revokePluginTool(pluginId: String, toolId: String) {
        validatePluginOwner(pluginId)
        validatePluginToolId(toolId)
        revoke("$pluginId/$toolId")
    }

    fun revokePlugin(pluginId: String) = gate.withGate {
        if (!validPluginId(pluginId)) return@withGate
        pluginEpochs[pluginId] = (pluginEpochs[pluginId] ?: 0L) + 1
        val prefix = "$pluginId/"
        val matchingKeys = (entries.keys + generations.keys)
            .filter { key ->
                key.startsWith(prefix) ||
                    entries[key]?.definition?.let { it.source == Source.THIRD_PARTY && it.owner == pluginId } == true
            }
            .distinct()
        matchingKeys.forEach { key ->
            generations[key] = (generations[key] ?: 0) + 1
            entries.remove(key)
            gate.uninstall(key)
        }
    }

    fun pluginToolKeys(pluginId: String): Set<String> = gate.withGate {
        entries.values
            .filter { it.definition.source == Source.THIRD_PARTY && it.definition.owner == pluginId }
            .mapTo(linkedSetOf()) { it.definition.toolKey }
    }
    fun freeze(runId: String): Map<String, FrozenDefinition> = gate.withGate {
        runs[runId]?.let { return@withGate LinkedHashMap(it) }
        // Existing persisted runs MUST reuse their original frozen mapping across restarts,
        // never re-allocate aliases against the current tool directory.
        store?.loadRunCatalog(runId)?.let { persisted ->
            return@withGate restoreLocked(runId, persisted)
        }
        val names = ModelToolNames.allocate(entries.keys)
        val snapshot = entries.values.map { entry ->
            entry.definition.copy(modelName = names.getValue(entry.definition.toolKey))
        }.sortedBy { it.modelName }.associateBy { it.modelName }
        check(snapshot.size == entries.size) { "Model tool names must be unique" }
        store?.saveRunCatalog(runId, snapshot.values.toList())
        gate.freezeRun(runId, snapshot.values)
        runs[runId] = snapshot
        LinkedHashMap(snapshot)
    }

    /** Restore a persisted run catalog from durable storage without re-allocating model names. */
    fun restore(runId: String): Map<String, FrozenDefinition>? = gate.withGate {
        runs[runId]?.let { return@withGate LinkedHashMap(it) }
        val persisted = store?.loadRunCatalog(runId) ?: return@withGate null
        restoreLocked(runId, persisted)
    }

    /** Restore an explicitly provided persisted run catalog into both this catalog and [gate]. */
    fun restore(runId: String, definitions: Collection<FrozenDefinition>): Map<String, FrozenDefinition> = gate.withGate {
        val canonical = definitions.sortedBy { it.modelName }.associateBy { it.modelName }
        require(canonical.size == definitions.size) { "Model tool names must be unique" }
        require(definitions.map { it.toolKey }.distinct().size == definitions.size) { "Tool keys must be unique" }
        store?.loadRunCatalog(runId)?.let { persisted ->
            require(persisted.associateBy { it.modelName } == canonical) {
                "Restored catalog conflicts with persisted run catalog for $runId"
            }
        }
        restoreLocked(runId, canonical.values)
    }

    /** Restore every persisted run catalog into both this catalog and [gate]. */
    fun restoreAll(): Map<String, Map<String, FrozenDefinition>> = gate.withGate {
        val persisted = store?.loadAllRunCatalogs().orEmpty()
        linkedMapOf<String, Map<String, FrozenDefinition>>().apply {
            persisted.forEach { (runId, definitions) ->
                put(runId, restoreLocked(runId, definitions))
            }
        }
    }

    private fun restoreLocked(runId: String, definitions: Collection<FrozenDefinition>): Map<String, FrozenDefinition> {
        val restored = gate.restoreRun(runId, definitions)
        val existing = runs[runId]
        require(existing == null || existing == restored) { "Run directory is immutable" }
        runs[runId] = restored
        return LinkedHashMap(restored)
    }

    /** Recover a pending step: prefer the full persisted run catalog when available, and fall back
     * to persisted call definitions only for pre-v6 runs that have no saved run catalog. */
    fun restorePending(runId: String, definitions: List<FrozenDefinition>) = gate.withGate {
        val full = runs[runId] ?: store?.loadRunCatalog(runId)?.let { restoreLocked(runId, it) }
        if (full != null) {
            for (definition in definitions) {
                require(full[definition.modelName] == definition) {
                    "Pending definition conflicts with persisted run catalog for $runId"
                }
            }
            return@withGate
        }
        restoreLocked(runId, definitions)
        Unit
    }
    fun definition(runId: String, name: String): FrozenDefinition? = gate.withGate { runs[runId]?.get(name) }
    /** Null means this run has no frozen catalog here, which says nothing about whether a name is unknown. */
    fun frozen(runId: String): Map<String, FrozenDefinition>? = gate.withGate { runs[runId]?.let { LinkedHashMap(it) } }
    val frozenRunCount: Int get() = gate.withGate { runs.size }
    fun release(runId: String) = gate.withGate {
        runs.remove(runId)
        gate.releaseRun(runId)
    }
    fun current(definition: FrozenDefinition): Entry? = gate.withGate {
        entries[definition.toolKey]?.takeIf { it.definition.sameRegistrationAs(definition) }
    }
}
