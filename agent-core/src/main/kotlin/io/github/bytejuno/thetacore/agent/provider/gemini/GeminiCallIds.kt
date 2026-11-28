package io.github.bytejuno.thetacore.agent.provider.gemini

import io.github.bytejuno.thetacore.agent.protocol.*

/** One deterministic mapping shared by functionCall and functionResponse; history is never edited. */
internal fun geminiCallIds(messages: List<Message>, provider: String): Map<String, String?> {
    val calls = messages.flatMap { it.parts.filterIsInstance<ContentPart.ToolCall>() }
    fun original(call: ContentPart.ToolCall) = call.binding.wireId?.takeIf { call.binding.provider == provider }
    // Reserve even later originals: a generated ID must not steal another call's first wire ID.
    val reserved = calls.mapNotNull(::original).toMutableSet()
    val used = mutableSetOf<String>()
    val mapping = mutableMapOf<String, String?>()
    calls.forEach { call ->
        val id = call.binding.callId
        require(id !in mapping) { "Repeated internal call ID" }
        val wire = original(call)
        val mapped = if (wire == null || used.add(wire)) wire else {
            val base = "call_${id.replace("-", "")}"
            var candidate = base
            var suffix = 0
            while (!reserved.add(candidate)) candidate = "${base}_${++suffix}"
            used.add(candidate)
            candidate
        }
        // Rewriting a signed functionCall would invalidate its opaque replay. Let admission
        // create a continuation checkpoint before transport, rather than stripping the signature.
        require(call.replay == null || mapped == wire) { "Incompatible replay; checkpoint required" }
        mapping[id] = mapped
    }
    return mapping
}
