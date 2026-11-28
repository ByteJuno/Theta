package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.*

/** Route-owned memory only: input may be sensitive and must not enter saved state. */
internal class ToolInputDraft {
    var values by mutableStateOf<Map<String, String>>(emptyMap())
    var submitted by mutableStateOf(false)
}

internal class ToolInputDrafts {
    private val drafts = mutableMapOf<String, ToolInputDraft>()

    operator fun get(requestId: String): ToolInputDraft = drafts.getOrPut(requestId) { ToolInputDraft() }

    fun retain(requestIds: Set<String>) {
        drafts.keys.retainAll(requestIds)
    }
}

internal val LocalToolInputDrafts = staticCompositionLocalOf<ToolInputDrafts> { error("Missing input draft owner") }
