package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Extension
import io.github.bytejuno.thetacore.sdk.Tool
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * from [FrozenToolCatalog] and `DefinitionGate` immediately. Each registration carries a unique
 * monotonic `registrationId` so stale captures or failures from an earlier registration can never
 * publish into or poison a re-registration of the same [Tool] object instance.
 *
 * Background slot-release / actual-exit waits never block host startup (`attachCatalog`) or
 * `PluginManager` operation locks (`awaitInitialPass`), while still resuming pending registrations
 * once capacity frees.
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
        // Keyed strictly by unique registrationId so re-registering the same Tool instance gets a clean slate.
        val registered: MutableMap<Long, String> = linkedMapOf(),
        val failed: MutableSet<Long> = linkedSetOf(),
        var inFlight: RegisteredExtension<Tool>? = null,
        var syncJob: Job? = null,
        // Completes when syncJob finishes OR parks awaiting background slot release / actual exit.
        var initialPassSettled: CompletableDeferred<Unit> = CompletableDeferred(Unit),
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
                RegisteredExtension(entry.id, tool, entry.registrationId)
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

    /**
     * Attaches the process-wide [FrozenToolCatalog] and awaits only the non-blocking initial pass
     * of already-active plugins (never blocking host startup on non-cooperative worker exit or
     * background capacity waits).
     */
    suspend fun attachCatalog(target: FrozenToolCatalog) {
        val activeIds = synchronized(lock) {
            catalog = target
            plugins.values
                .filter { it.active && !it.closed }
                .onEach { reconcileLocked(it) }
                .map { it.pluginId }
        }
        coroutineScope {
            activeIds.map { id -> async { awaitInitialPass(id) } }.awaitAll()
        }
    }

    /**
     * Awaits the initial registration attempt for [pluginId] without blocking on background
     * slot-release / actual-exit waits. Used by host startup and plugin enable paths so a
     * non-cooperative metadata timeout never stalls host initialization or plugin management locks.
     */
    suspend fun awaitInitialPass(pluginId: String) {
        val settled = synchronized(lock) { plugins[pluginId]?.initialPassSettled } ?: return
        settled.await()
    }

    /** Awaits full draining of pending metadata captures for [pluginId] (including slot-release retries). */
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
        binding.initialPassSettled.complete(Unit)
        catalog?.revokePlugin(binding.pluginId)
    }

    private fun reconcileLocked(binding: PluginBinding) {
        val target = catalog ?: return
        val desiredByRegId = binding.tools.associateBy { it.registrationId }
        val revokedToolIds = linkedSetOf<String>()

        val removedRegistered = binding.registered.entries
            .filter { (regId, _) -> regId !in desiredByRegId }
            .map { it.key to it.value }
        removedRegistered.forEach { (regId, toolId) ->
            binding.registered.remove(regId)
            revokedToolIds += toolId
        }
        binding.inFlight?.let { activeCapture ->
            if (activeCapture.registrationId !in desiredByRegId) {
                binding.inFlight = null
                revokedToolIds += activeCapture.id
            }
        }
        val activeRegisteredToolIds = binding.registered.values.toSet()
        revokedToolIds.forEach { toolId ->
            if (toolId !in activeRegisteredToolIds && validPluginToolId(toolId)) {
                target.revoke("${binding.pluginId}/$toolId")
            }
        }

        val removedFailed = binding.failed.filter { it !in desiredByRegId }
        binding.failed.removeAll(removedFailed.toSet())

        val hasPending = binding.tools.any { entry ->
            entry.registrationId !in binding.registered && entry.registrationId !in binding.failed
        }
        if (hasPending && binding.syncJob == null) {
            binding.initialPassSettled = CompletableDeferred()
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
                        binding.initialPassSettled.complete(Unit)
                        return
                    }
                    val candidate = binding.tools.firstOrNull { entry ->
                        entry.registrationId !in binding.registered && entry.registrationId !in binding.failed
                    }
                    if (candidate == null) {
                        binding.inFlight = null
                        binding.syncJob = null
                        binding.initialPassSettled.complete(Unit)
                        return
                    }
                    binding.inFlight = candidate
                    candidate
                }
                if (!validPluginToolId(next.id)) {
                    synchronized(lock) {
                        if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.registrationId == next.registrationId }) {
                            binding.failed += next.registrationId
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
                                binding.tools.any { it.registrationId == next.registrationId }
                            if (!stillCurrent) {
                                if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                                return@synchronized null
                            }
                            install().also {
                                binding.registered[next.registrationId] = next.id
                                if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                            }
                        }
                    }
                    synchronized(lock) {
                        if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                    }
                } catch (capacity: MetadataCapacityUnavailableException) {
                    val shouldRetry = synchronized(lock) {
                        if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                        // Do not block host initialization or plugin operations while awaiting slot release.
                        binding.initialPassSettled.complete(Unit)
                        !binding.closed && binding.active && catalog === target && !target.isBudgetClosed()
                    }
                    if (!shouldRetry) return
                    capacity.nextRelease.await()
                } catch (timeout: MetadataCaptureTimeoutException) {
                    val hasMorePending = synchronized(lock) {
                        if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.registrationId == next.registrationId }) {
                            binding.failed += next.registrationId
                        }
                        // Unblock host startup and plugin management locks immediately after timeout;
                        // background slot-release continuation proceeds asynchronously.
                        binding.initialPassSettled.complete(Unit)
                        !binding.closed && binding.active && catalog === target && binding.tools.any { entry ->
                            entry.registrationId !in binding.registered && entry.registrationId !in binding.failed
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
                        if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.registrationId == next.registrationId }) {
                            binding.failed += next.registrationId
                        }
                    }
                    onRegistrationError(binding.pluginId, next.id, cancelled)
                } catch (error: Throwable) {
                    synchronized(lock) {
                        if (binding.inFlight?.registrationId == next.registrationId) binding.inFlight = null
                        if (!binding.closed && binding.tools.any { it.registrationId == next.registrationId }) {
                            binding.failed += next.registrationId
                        }
                    }
                    onRegistrationError(binding.pluginId, next.id, error)
                }
            }
        } finally {
            synchronized(lock) {
                binding.inFlight = null
                binding.initialPassSettled.complete(Unit)
                if (binding.syncJob === coroutineContext[Job]) {
                    binding.syncJob = null
                }
            }
        }
    }
}
