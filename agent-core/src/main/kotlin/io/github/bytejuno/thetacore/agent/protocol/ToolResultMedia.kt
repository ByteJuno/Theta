package io.github.bytejuno.thetacore.agent.protocol

/** Bounded one-level tool content; nested calls/results are never allowed. */
fun Message.mediaParts(): List<ContentPart.Media> = parts.flatMap { part -> when (part) {
    is ContentPart.Media -> listOf(part)
    is ContentPart.ToolResult -> part.content.filterIsInstance<ContentPart.Media>()
    else -> emptyList()
} }

/** Keep calls, result order and user messages intact; only unsupported tool media becomes text. */
fun toolMediaRequest(request: ChatRequest, supports: (MediaKind) -> Boolean): ChatRequest {
    return projectToolMedia(request) { item ->
        if (supports(item.kind)) null else "unavailable for this model"
    }
}

/** Request-only degradation. Read one bounded attachment at a time, never retain image bytes
 * alongside the request or change durable history, user attachments, effects or call bindings. */
fun availableToolMediaRequest(request: ChatRequest, reader: MediaContentReader?,
    checkAvailability: Boolean = true, supports: (ContentPart.Media) -> Boolean): ChatRequest =
    projectToolMedia(request) { item ->
        when {
            !supports(item) -> "unavailable for this model or media format"
            !checkAvailability -> null
            else -> reader?.unavailableReason(item.blob) ?: if (reader == null) "reader unavailable" else null
        }
    }

private fun projectToolMedia(request: ChatRequest, unavailable: (ContentPart.Media) -> String?): ChatRequest {
    var changed = false
    val messages = request.messages.map { m ->
        if (m.role != Role.TOOL) m else message(m.role, *m.parts.map { part ->
            if (part !is ContentPart.ToolResult) part
            else {
                var resultChanged = false
                val content = part.content.map { item ->
                    val reason = (item as? ContentPart.Media)?.let(unavailable)
                    if (reason == null) item else {
                        changed = true
                        resultChanged = true
                        textPart("[Tool media $reason: ${item.kind}; ${item.blob.mimeType}; blob ${item.blob.id}]")
                    }
                }
                if (resultChanged) toolResultPart(part.callId, content, part.isError) else part
            }
        }.toTypedArray(), cacheBreakpoint = m.cacheBreakpoint)
    }
    return if (!changed) request else object : ChatRequest by request { override val messages = messages }
}
