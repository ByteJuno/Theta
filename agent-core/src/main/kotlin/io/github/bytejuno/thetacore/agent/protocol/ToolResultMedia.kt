package io.github.bytejuno.thetacore.agent.protocol

/** Bounded one-level tool content; nested calls/results are never allowed. */
fun Message.mediaParts(): List<ContentPart.Media> = parts.flatMap { part -> when (part) {
    is ContentPart.Media -> listOf(part)
    is ContentPart.ToolResult -> part.content.filterIsInstance<ContentPart.Media>()
    else -> emptyList()
} }

/** Keep calls, result order and user messages intact; only unsupported tool media becomes text. */
fun toolMediaRequest(request: ChatRequest, supports: (MediaKind) -> Boolean): ChatRequest {
    var changed = false
    val messages = request.messages.map { m ->
        if (m.role != Role.TOOL) m else message(m.role, *m.parts.map { part ->
            if (part !is ContentPart.ToolResult || part.content.none { it is ContentPart.Media && !supports(it.kind) }) part
            else {
                changed = true
                toolResultPart(part.callId, part.content.map { item ->
                    if (item is ContentPart.Media && !supports(item.kind)) textPart(
                        "[Tool media unavailable for this model: ${item.kind}; ${item.blob.mimeType}; blob ${item.blob.id}]") else item
                }, part.isError)
            }
        }.toTypedArray(), cacheBreakpoint = m.cacheBreakpoint)
    }
    return if (!changed) request else object : ChatRequest by request { override val messages = messages }
}
