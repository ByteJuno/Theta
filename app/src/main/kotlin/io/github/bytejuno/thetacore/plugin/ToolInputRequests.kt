package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal data class ToolInputRequest(val id: String, val callId: String, val prompt: String, val schema: JsonObject)

/** Process-only input waiters. Answers are never persisted, and cancellation removes the waiter. */
internal class ToolInputRequests(private val validator: Schema202012Validator) {
    private data class Pending(val request: ToolInputRequest, val answer: CompletableDeferred<JsonObject?>)
    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    suspend fun request(callId: String, prompt: String, schema: JsonObject): JsonObject? {
        if (prompt.length > 4096 || !validator.supported(schema.toString())) return null
        currentCoroutineContext().ensureActive()
        val p = Pending(ToolInputRequest(UUID.randomUUID().toString(), callId, prompt, schema), CompletableDeferred())
        synchronized(lock) { check(callId !in pending); pending[callId] = p }
        try { return p.answer.await() }
        finally { synchronized(lock) { if (pending[callId] === p) pending.remove(callId) }; p.answer.cancel() }
    }

    fun snapshot(callIds: Collection<String>): Map<String, ToolInputRequest> = synchronized(lock) {
        callIds.mapNotNull { id -> pending[id]?.request?.let { id to it } }.toMap()
    }

    fun respond(callId: String, requestId: String, answer: JsonObject?): Boolean = synchronized(lock) {
        val p = pending[callId]?.takeIf { it.request.id == requestId } ?: return false
        if (answer != null && !validator.valid(p.request.schema.toString(), answer.toString())) return false
        if (!p.answer.complete(answer)) return false
        pending.remove(callId)
        true
    }
}
