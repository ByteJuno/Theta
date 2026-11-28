package io.github.bytejuno.thetacore.screen

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Backends return only after all their work has exited, including cancellation cleanup. */
internal interface ScreenControlBackend {
    val ready: Boolean
    suspend fun execute(action: ScreenAction): ScreenReply
}

internal data class ScreenAction(val name: String, val args: JsonObject) {
    val writes get() = name !in setOf("screen_capture", "screen_ui_tree", "app_list")
    fun number(key: String, default: Int = 0) = args[key]?.jsonPrimitive?.int ?: default
    fun text(key: String) = args.getValue(key).jsonPrimitive.content
}

internal data class ScreenReply(
    val result: ToolResult,
    /** True only with positive evidence that no action started; never retry UNKNOWN. */
    val unavailableBeforeStart: Boolean = false,
)

internal interface ScreenCaptureVisibilityHook {
    suspend fun beforeCapture()
    suspend fun afterCapture()
}

internal class ScreenController(
    private val root: ScreenControlBackend,
    private val accessibility: ScreenControlBackend,
    private val unavailableMessage: () -> String,
    private val visibility: ScreenCaptureVisibilityHook? = null,
) {
    suspend fun execute(action: ScreenAction): ToolResult {
        currentCoroutineContext().ensureActive()
        try {
            if (action.name == "screen_capture") visibility?.beforeCapture()
            if (root.ready) {
                val reply = root.execute(action)
                if (!reply.unavailableBeforeStart) return reply.result
            }
            currentCoroutineContext().ensureActive()
            if (accessibility.ready) return accessibility.execute(action).result
            return ToolResult(unavailableMessage(), isError = true, effect = ToolEffect.NONE)
        } finally {
            if (action.name == "screen_capture") withContext(NonCancellable) { visibility?.afterCapture() }
        }
    }
}

internal fun unavailableScreenReply(message: String) = ScreenReply(
    ToolResult(message, isError = true, effect = ToolEffect.NONE), unavailableBeforeStart = true,
)
