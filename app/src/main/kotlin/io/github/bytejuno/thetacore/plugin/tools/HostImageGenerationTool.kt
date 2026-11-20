package io.github.bytejuno.thetacore.plugin.tools

import io.github.bytejuno.thetacore.chat.ImageAttachments
import io.github.bytejuno.thetacore.chat.normalizeImage
import io.github.bytejuno.thetacore.chat.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal class HostImageGenerationTool(
    private val configuration: () -> OpenAiRunSnapshot,
    private val attachments: ImageAttachments,
    private val generate: suspend (OpenAiRunSnapshot, String) -> ByteArray = OpenAiImageClient()::generate,
    private val normalize: (ByteArray) -> ByteArray = ::normalizeImage,
) : Tool {
    override val id = "generate_image"
    override val spec = ToolSpec(id,
        "Generate one image ONLY when the user asks to create or draw an image. Do not call for writing prompts, explaining image APIs, or discussing images. Supply the user's visual description as prompt. The image is displayed privately in this chat; never retrieve image bytes or URLs into the conversation. This may incur a charge and requires approval. Configuration errors need user action, not another call.",
        Json.parseToJsonElement("""{"type":"object","properties":{"prompt":{"type":"string","minLength":1,"maxLength":8000}},"required":["prompt"],"additionalProperties":false}""").jsonObject,
        risk = ToolRisk.WRITE, idempotent = false, parallelSafe = false,
        exclusiveResource = "host-image-generation", timeoutMs = 240_000)
    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        var submitted = false
        try {
            val prompt = args.getValue("prompt").jsonPrimitive.content
            require(prompt.isNotBlank() && prompt.length <= 8000)
            val snapshot = configuration()
            if (!attachments.claim(env.sessionId, env.callId))
                return ToolResult(IMAGE_ATTEMPTED, isError = true, effect = ToolEffect.UNKNOWN)
            currentCoroutineContext().ensureActive()
            submitted = true
            val bytes = normalize(generate(snapshot, prompt))
            currentCoroutineContext().ensureActive()
            attachments.save(env.sessionId, env.callId, bytes)
            return ToolResult(IMAGE_COMPLETE, effect = ToolEffect.APPLIED)
        } catch (error: CancellationException) { throw error }
        catch (_: ProviderSettingsException) {
            return ToolResult(IMAGE_CONFIGURE, isError = true, effect = ToolEffect.NONE)
        } catch (_: Exception) {
            return ToolResult(IMAGE_FAILED, isError = true,
                effect = if (submitted) ToolEffect.UNKNOWN else ToolEffect.NONE)
        }
    }
}

