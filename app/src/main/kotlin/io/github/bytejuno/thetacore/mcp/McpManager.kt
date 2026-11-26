package io.github.bytejuno.thetacore.mcp

import io.github.bytejuno.thetacore.agent.mcp.*
import io.github.bytejuno.thetacore.plugin.FrozenToolCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class McpConnectionView(val id: String, val name: String, val url: String,
    val enabled: Boolean, val hasToken: Boolean, val header: String, val state: String = "disabled", val tools: Int = 0)

/** One process owner. IO never holds DefinitionGate; epochs prevent stale refresh publication. */
internal class McpManager(private val store: McpConnectionStore, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val states = MutableStateFlow<List<McpConnectionView>>(emptyList())
    val connections: StateFlow<List<McpConnectionView>> = states.asStateFlow()
    private val errors = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = errors.asStateFlow()
    private val recovery = MutableStateFlow(false)
    val replacementAllowed: StateFlow<Boolean> = recovery.asStateFlow()
    private var catalog: FrozenToolCatalog? = null
    private val bindings = mutableMapOf<String, Binding>()
    private class Binding(val connection: McpConnection) {
        val version = connection.revision
        lateinit var client: McpClient
        val clientGeneration = AtomicLong()
        var initialized = false
        var refresh: Job? = null
        var listener: Job? = null
        var timer: Job? = null
        val invalidations = AtomicLong()
    }

    suspend fun attach(target: FrozenToolCatalog) {
        mutex.withLock { check(catalog == null); catalog = target }
        reload()
    }
    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val records = store.read()
                errors.value = false
                recovery.value = false
                states.value = records.map { record ->
                    states.value.firstOrNull { it.id == record.id && record.id in bindings }
                        ?: view(record, if (record.enabled) "refreshing" else "disabled")
                }
                records.filter { it.enabled && it.id !in bindings }.forEach(::startLocked)
            } catch (e: Exception) {
                errors.value = true
                recovery.value = (e as? McpSettingsException)?.replacementAllowed == true
                bindings.keys.toList().forEach(::stopLocked)
                states.value = emptyList()
            }
        }
    }
    suspend fun save(id: String?, name: String, url: String, enabled: Boolean, token: String?, header: String) =
        withContext(Dispatchers.IO) { mutex.withLock {
            val old = store.read().firstOrNull { it.id == id }
            if (token == null && old?.hasCredentials() == true && (old.url != url || old.header != header))
                throw McpSettingsException()
            val value = McpConnection(id ?: UUID.randomUUID().toString(), name.trim(), validateMcpUrl(url.trim()),
                enabled, token ?: old?.token.orEmpty(), header)
            store.save(value)
            stopLocked(value.id)
            states.value = (states.value.filterNot { it.id == value.id } + view(value, if (enabled) "refreshing" else "disabled"))
            if (enabled) startLocked(value)
        } }
    suspend fun replaceUnreadable(name: String, url: String, token: String, header: String) =
        withContext(Dispatchers.IO) { mutex.withLock {
            val value = McpConnection(UUID.randomUUID().toString(), name.trim(), validateMcpUrl(url.trim()),
                false, token, header)
            store.replaceUnreadable(value)
            bindings.keys.toList().forEach(::stopLocked)
            states.value = listOf(view(value, "disabled"))
            errors.value = false; recovery.value = false
        } }
    suspend fun enable(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val record = store.read().first { it.id == id }.enabled(enabled)
        // Revocation precedes durable IO; a failed write remains visibly blocked in this process.
        stopLocked(id)
        update(id, "disabled", 0)
        store.save(record)
        states.value = states.value.map { if (it.id == id) view(record, if (enabled) "refreshing" else "disabled") else it }
        if (enabled) startLocked(record)
    } }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        stopLocked(id); update(id, "disabled", 0)
        store.delete(id)
        states.value = states.value.filterNot { it.id == id }
    } }
    suspend fun refresh(id: String) = mutex.withLock {
        bindings[id]?.let { b ->
            // Explicit refresh starts a new session. Old calls keep their own client; no replay.
            b.listener?.cancel(); b.listener = null
            b.client = client(b); b.initialized = false
            requestRefreshLocked(b)
        }
    }

    private fun startLocked(connection: McpConnection) {
        val b = Binding(connection)
        b.client = client(b)
        bindings[connection.id] = b
        requestRefreshLocked(b)
    }
    private fun client(b: Binding): McpClient {
        val connection = b.connection
        val generation = catalog?.gate?.withGate { b.clientGeneration.incrementAndGet() }
            ?: b.clientGeneration.incrementAndGet()
        return McpClient(McpEndpoint(connection.url, connection.token, connection.header), sessionInvalid = {
            catalog?.gate?.withGate {
                if (b.clientGeneration.get() != generation) return@withGate
                b.invalidations.incrementAndGet()
                catalog?.beginMcpRefresh(connection.id)
                catalog?.gate?.clearFreshness("mcp/${connection.id}")
            }
            scope.launch { mutex.withLock {
                if (bindings[connection.id] === b && b.clientGeneration.get() == generation) {
                    b.timer?.cancel(); b.listener?.cancel()
                    update(connection.id, "failed", 0)
                }
            } }
        }) {
            // Mark pending synchronously on notification, before scheduling network work.
            catalog?.gate?.withGate {
                if (b.clientGeneration.get() != generation) return@withGate
                b.invalidations.incrementAndGet()
                catalog?.beginMcpRefresh(connection.id)
            }
            scope.launch { mutex.withLock {
                if (bindings[connection.id] === b && b.clientGeneration.get() == generation) requestRefreshLocked(b)
            } }
        }
    }
    private fun stopLocked(id: String) {
        catalog?.revokeMcp(id)
        bindings.remove(id)?.let {
            catalog?.gate?.withGate { it.clientGeneration.incrementAndGet() } ?: it.clientGeneration.incrementAndGet()
            it.refresh?.cancel(); it.listener?.cancel(); it.timer?.cancel()
        }
    }
    private fun requestRefreshLocked(b: Binding) {
        val target = catalog ?: return
        val epoch = target.gate.withGate {
            target.beginMcpRefresh(b.connection.id)
            target.gate.clearFreshness("mcp/${b.connection.id}")
            b.invalidations.incrementAndGet()
        }
        b.timer?.cancel()
        // A notification during a refresh must supersede its response, not be lost.
        b.refresh?.cancel()
        update(b.connection.id, "refreshing", 0)
        val refreshClient = b.client
        b.refresh = scope.launch {
            try {
                val directory = withTimeout(30_000) {
                    if (!b.initialized) {
                        refreshClient.initialize()
                        mutex.withLock {
                            currentCoroutineContext().ensureActive()
                            if (b.client === refreshClient) b.initialized = true
                        }
                    }
                    refreshClient.listTools()
                }
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (bindings[b.connection.id] !== b || b.client !== refreshClient) return@withLock
                    val installed = target.gate.withGate {
                        if (b.invalidations.get() != epoch) return@withGate false
                        target.replaceMcp(b.connection.id, b.version, directory.tools.map { McpTool(refreshClient, it) })
                        target.gate.setFreshness("mcp/${b.connection.id}", target.mcpKeys(b.connection.id), directory.ttlMs) {
                            scope.launch { mutex.withLock { if (bindings[b.connection.id] === b) requestRefreshLocked(b) } }
                        }
                        true
                    }
                    if (!installed) return@withLock
                    if (directory.ttlMs == 0L) {
                        target.beginMcpRefresh(b.connection.id)
                        target.gate.clearFreshness("mcp/${b.connection.id}")
                        update(b.connection.id, "failed", 0)
                        return@withLock // Immediately stale: do not spin or permit stale calls.
                    }
                    update(b.connection.id, "ready", directory.tools.size)
                    b.timer = scope.launch { delay(directory.ttlMs); mutex.withLock {
                        if (bindings[b.connection.id] === b) requestRefreshLocked(b)
                    } }
                    if (b.listener?.isActive != true) b.listener = scope.launch {
                        try { refreshClient.listen() } catch (c: CancellationException) { throw c }
                        catch (_: Exception) { /* TTL still guards admission; no reconnect/replay loop. */ }
                    }
                }
            } catch (c: CancellationException) {
                if (c !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw c
                mutex.withLock { if (bindings[b.connection.id] === b && b.client === refreshClient) update(b.connection.id, "failed", 0) }
            }
            catch (_: Exception) { mutex.withLock {
                if (bindings[b.connection.id] === b && b.client === refreshClient) update(b.connection.id, "failed", 0)
                // Existing registrations remain REFRESH_PENDING. Explicit retry can restore them.
            } }
        }
    }
    private fun update(id: String, state: String, count: Int) {
        states.value = states.value.map { if (it.id == id) it.copy(state = state, tools = count) else it }
    }
    private fun view(c: McpConnection, state: String) = McpConnectionView(c.id, c.name, c.url, c.enabled,
        c.token.isNotEmpty(), c.header, state)
    private fun McpConnection.hasCredentials() = token.isNotEmpty()
}
