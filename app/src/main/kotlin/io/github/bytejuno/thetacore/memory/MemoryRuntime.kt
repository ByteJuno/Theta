package io.github.bytejuno.thetacore.memory

import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.InvocationStore
import io.github.bytejuno.thetacore.agent.memory.MemoryPrompt
import io.github.bytejuno.thetacore.chat.ChatPromptSnapshot
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun memoryTools(context: android.content.Context, store: InvocationStore): List<io.github.bytejuno.thetacore.sdk.Tool> {
    val memories = (store as? AndroidAgentStore)?.memories ?: return emptyList()
    return listOf("memory_search", "memory_save", "memory_delete").map { id ->
        MemoryTool(id, memories) { context.getString(memoryErrorResource(it)) }
    }
}

internal fun memoryErrorResource(error: MemoryError): Int = when (error) {
    MemoryError.INPUT -> R.string.memory_input_error
    MemoryError.CAPACITY -> R.string.memory_capacity_error
    MemoryError.DISABLED -> R.string.memory_disabled_error
    MemoryError.STORAGE -> R.string.memory_storage_error
}

internal suspend fun memoryPromptSnapshot(host: ThetaApplication): ChatPromptSnapshot = withContext(Dispatchers.IO) {
    host.personaSettings.resolve(host.plugins.activePromptData()).copy(memorySection =
        MemoryPrompt.section(requireNotNull(host.hostTools().sessionStore).memories.promptEntries()))
}

internal fun systemWithMemory(prompts: ChatPromptSnapshot, base: String?, reader: String?): String? =
    MemoryPrompt.append(prompts.data.system(base, prompts.persona, reader), prompts.memorySection)

internal object MemoryUiKeys {
    const val GENERAL = "general"
    const val CONTENT = "memory-content"
    const val CATEGORY = "memory-category"
    const val ENABLED = "memory-enabled"
    const val SEARCH = "memory-search"
    const val FEEDBACK = "memory-feedback"
    const val ENTRY = "memory-entry"
}
