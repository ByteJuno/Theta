package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Extension
import io.github.bytejuno.thetacore.sdk.Tool
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun validatePluginOwner(pluginId: String) {
    require(validPluginId(pluginId)) { "Invalid plugin id" }
    require(!pluginId.equals("host", ignoreCase = true) && !pluginId.equals("mcp", ignoreCase = true)) {
        "Reserved plugin id"
    }
}

internal fun validPluginToolId(id: String): Boolean {
    if (id.isBlank() || id.length > 128) return false
    if (id.trim() != id) return false
    if (id == "." || id == "..") return false
    if (id.startsWith("host/", ignoreCase = true) || id.startsWith("mcp/", ignoreCase = true)) return false
    return id.none { ch -> ch == '/' || ch == '\\' || ch < ' ' || ch == '\u007F' }
}

internal fun validatePluginToolId(toolId: String) {
    require(validPluginToolId(toolId)) { "Invalid plugin tool id" }
}

internal fun validateVersion(version: String) {
    require(version.isNotBlank() && version.length <= 128 && version.none { it < ' ' || it == '\u007F' }) {
        "Invalid tool version"
    }
}

/**
 * Bridges enabled dex plugin [Tool] registrations from [PluginRegistrations] into [FrozenToolCatalog].
 *
 * Lock ordering is strictly one-way:
 * `PluginRegistrations.lock` -> `PluginToolBridge.lock` -> `DefinitionGate` -> `PluginExecutionBudget.gate`.
 *
 * Revocations, plugin disable/uninstall, and fault-boundary closures synchronously remove tools
 * from [FrozenToolCatalog] and `DefinitionGate` immediately. New tool metadata captures run
 * sequentially per plugin on [scope] so multi-tool plugins never contend for their own single
 * per-plugin metadata budget slot, while independent plugins capture concurrently.
 */
internal class PluginToolBridge(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onRegistrationError: (pluginId: String, toolId: String, error: Throwable) -> Unit = { _, _, _ -> },
) {
    private class PluginBinding(
        val pluginId: String,
        val version: String,
        val token: Any,
        val failureWindow: PluginToolFailureWindow,
        var active: Boolean = false,
        var closed: Boolean = false,
        var tools: List<RegisteredExtension<Tool>> = emptyList(),
        val registered: MutableMap<String, Tool> = linkedMapOf(),
        val failed: MutableMap<String, Tool> = linkedMapOf(),
        var inFlight: RegisteredExtension<Tool>? = null,
        var syncJob: Job? = null,
    )

    private val lock = Any()
    private var catalog: FrozenToolCatalog? = null
    private val plugins = linkedMapOf<String, PluginBinding>()

    fun attachPlugin(
        pluginId: String,
        version: String,
        token: Any,
        failureWindow: PluginToolFailureWindow = PluginToolFailureWindow(),
    ) {
        validatePluginOwner(pluginId)
        validateVersion(version)
        synchronized(lock) {
            val previous = plugins.remove(pluginId)
            if (previous != null) {
                closeBindingLocked(previous)
            }
            plugins[pluginId] = PluginBinding(
                pluginId = pluginId,
                version = version,
                token = token,
                failureWindow = failureWindow,
            )
        }
    }

    /** Invoked synchronously under `PluginRegistrations.lock`. Never runs plugin code or blocking IO. */
    fun onRegistrationsChanged(
        pluginId: String,
        token: Any,
        entries: List<RegisteredExtension<Extension>>,
        closed: Boolean,
    ) {
        synchronized(lock) {
            val binding = plugins[pluginId] ?: return
            if (binding.token !== token) return
            if (closed) {
                closeBindingLocked(binding)
                plugins.remove(pluginId)
                return
            }
            if (binding.closed) return
            binding.tools = entries.mapNotNull { entry ->
                val tool = entry.value as? Tool ?: return@mapNotNull null
                RegisteredExtension(entry.id, tool)
            }
            if (binding.active) {
                reconcileLocked(binding)
            }
        }
    }

    /** Activates bridging after `onEnable` succeeds and durable start state is committed. */
    fun activatePlugin(pluginId: String, token: Any) {
        synchronized(lock) {
            val binding = plugins[pluginId] ?: return
            if (binding.token !== token || binding.closed) return
            binding.active = true
            reconcileLocked(binding)
        }
    }

    /** Immediately revokes all bridged tools for this plugin instance on disable/uninstall/fault. */
    fun deactivatePlugin(pluginId: String, token: Any) {
        synchronized(lock) {
            val binding = plugins[pluginId] ?: return
            if (binding.token !== token) return
            closeBindingLocked(binding)
            plugins.remove(pluginId)
        }
    }

    /** Attaches the process-wide [FrozenToolCatalog] and awaits initial sync of already-active plugins. */
    suspend fun attachCatalog(target: FrozenToolCatalog) {
        val activeIds = synchronized(lock) {
            catalog = target
            plugins.values
                .filter { it.active && !it.closed }
                .onEach { reconcileLocked(it) }
                .map { it.pluginId }
        }
        activeIds.forEach { awaitPending(it) }
    }

    /** Awaits currently scheduled metadata captures for [pluginId] (bounded by catalog capture timeout). */
    suspend fun awaitPending(pluginId: String) {
        while (true) {
            val job = synchronized(lock) { plugins[pluginId]?.syncJob } ?: return
            job.join()
        }
    }

    private fun closeBindingLocked(binding: PluginBinding) {
        binding.closed = true
        binding.active = false
        binding.tools = emptyList()
        binding.registered.clear()
        binding.failed.clear()
        binding.inFlight = null
        binding.syncJob?.cancel()
        binding.syncJob = null
        catalog?.revokePlugin(binding.pluginId)
    }

    private fun reconcileLocked(binding: PluginBinding) {
        val target = catalog ?: return
        val desired = linkedMapOf<String, Tool>()
        binding.tools.forEach { entry ->
            desired[entry.id] = entry.value
        }
        val revokedIds = linkedSetOf<String>()
        val removedRegistered = binding.registered.entries
            .filter { (toolId, tool) -> desired[toolId] !== tool }
            .map { it.key }
        removedRegistered.forEach { toolId ->
            binding.registered.remove(toolId)
            revokedIds += toolId
        }
        binding.inFlight?.let { activeCapture ->
            if (desired[activeCapture.id] !== activeCapture.value) {
                binding.inFlight = null
                revokedIds += activeCapture.id
            }
        }
        revokedIds.forEach { toolId ->
            if (validPluginToolId(toolId)) {
                target.revoke("${binding.pluginId}/$toolId")
            }
        }
        val removedFailed = binding.failed.entries
            .filter { (toolId, tool) -> desired[toolId] !== tool }
            .map { it.key }
        removedFailed.forEach { binding.failed.remove(it) }

        val hasPending = desired.any { (toolId, tool) ->
            binding.registered[toolId] !== tool && binding.failed[toolId] !== tool
        }
        if (hasPending && binding.syncJob == null) {
            binding.syncJob = scope.launch { drainPluginRegistrations(binding, target) }
        }
    }

    private suspend fun drainPluginRegistrations(binding: PluginBinding, target: FrozenToolCatalog) {
        try {
            while (true) {
                val next: RegisteredExtension<Tool> = synchronized(lock) {
                    if (binding.closed || !binding.active || catalog !== target) {
                        binding.inFlight = null
                        binding.syncJob = null
                        return
                    }
                    val candidate = binding.tools.firstOrNull { entry ->
                        binding.registered[entry.id] !== entry.value && binding.failed[entry.id] !== entry.value
                    }
                    if (candidate == null) {
                        binding.inFlight = null
                        binding.syncJob = null
                        return
                    }
                    binding.inFlight = candidate
                    candidate
                }
                if (!validPluginToolId(next.id)) {
                    synchronized(lock) {
                        if (binding.inFlight === next) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.id == next.id && it.value === next.value }) {
                            binding.failed[next.id] = next.value
                        }
                    }
                    onRegistrationError(binding.pluginId, next.id, IllegalArgumentException("Invalid plugin tool id"))
                    continue
                }
                try {
                    target.registerPluginAtomically(
                        pluginId = binding.pluginId,
                        version = binding.version,
                        tool = next.value,
                        toolId = next.id,
                        failureWindow = binding.failureWindow,
                    ) { install ->
                        synchronized(lock) {
                            val stillCurrent = !binding.closed &&
                                binding.active &&
                                catalog === target &&
                                binding.tools.any { it.id == next.id && it.value === next.value }
                            if (!stillCurrent) {
                                if (binding.inFlight === next) binding.inFlight = null
                                return@synchronized null
                            }
                            install().also {
                                binding.registered[next.id] = next.value
                                if (binding.inFlight === next) binding.inFlight = null
                            }
                        }
                    }
                    synchronized(lock) {
                        if (binding.inFlight === next) binding.inFlight = null
                    }
                } catch (capacity: MetadataCapacityUnavailableException) {
                    val shouldRetry = synchronized(lock) {
                        if (binding.inFlight === next) binding.inFlight = null
                        !binding.closed && binding.active && catalog === target && !target.isBudgetClosed()
                    }
                    if (!shouldRetry) return
                    capacity.nextRelease.await()
                } catch (timeout: MetadataCaptureTimeoutException) {
                    val hasMorePending = synchronized(lock) {
                        if (binding.inFlight === next) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.id == next.id && it.value === next.value }) {
                            binding.failed[next.id] = next.value
                        }
                        !binding.closed && binding.active && catalog === target && binding.tools.any { entry ->
                            binding.registered[entry.id] !== entry.value && binding.failed[entry.id] !== entry.value
                        }
                    }
                    onRegistrationError(binding.pluginId, next.id, timeout)
                    if (hasMorePending && !target.isBudgetClosed()) {
                        timeout.actualExited.await()
                    }
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) {
                        throw cancelled
                    }
                    synchronized(lock) {
                        if (binding.inFlight === next) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.id == next.id && it.value === next.value }) {
                            binding.failed[next.id] = next.value
                        }
                    }
                    onRegistrationError(binding.pluginId, next.id, cancelled)
                } catch (error: Throwable) {
                    synchronized(lock) {
                        if (binding.inFlight === next) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.id == next.id && it.value === next.value }) {
                            binding.failed[next.id] = next.value
                        }
                    }
                    onRegistrationError(binding.pluginId, next.id, error)
                }
            }
        } finally {
            synchronized(lock) {
                binding.inFlight = null
                if (binding.syncJob === coroutineContext[Job]) {
                    binding.syncJob = null
                }
            }
        }
    }
}
