package io.github.bytejuno.thetacore.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** First S1 invocation subset, not the full PROTOCOL agent, approval ledger or MCP API. */
interface Tool : Extension {
    val spec: ToolSpec
    suspend fun call(args: JsonObject, env: ToolEnv): ToolResult
}

enum class ToolRisk { READ, WRITE, DEVICE_CONTROL, ROOT }
enum class ToolEffect { NONE, APPLIED, UNKNOWN }

/** Final envelope: reading these fields never invokes plugin getters. Schema validation is S2. */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val risk: ToolRisk = ToolRisk.READ,
    val idempotent: Boolean = false,
    val parallelSafe: Boolean = false,
    val exclusiveResource: String? = null,
    val timeoutMs: Long? = null,
)

enum class ToolMediaKind { IMAGE, AUDIO, DOCUMENT, VIDEO }
sealed interface ToolContent {
    data class Text(val text: String) : ToolContent
    /** Immutable byte value: neither the source array nor a getter/copy can mutate this part. */
    class Media(val kind: ToolMediaKind, val mimeType: String, bytes: ByteArray) : ToolContent {
        private val content = bytes.copyOf()
        val bytes: ByteArray get() = content.copyOf()
        fun copy(kind: ToolMediaKind = this.kind, mimeType: String = this.mimeType,
            bytes: ByteArray = content): Media = Media(kind, mimeType, bytes)
        operator fun component1() = kind
        operator fun component2() = mimeType
        operator fun component3() = bytes
        override fun equals(other: Any?): Boolean = other is Media && kind == other.kind &&
            mimeType == other.mimeType && content.contentEquals(other.content)
        override fun hashCode(): Int = 31 * (31 * kind.hashCode() + mimeType.hashCode()) + content.contentHashCode()
        override fun toString(): String = "Media(kind=$kind, mimeType=$mimeType, bytes=${content.contentToString()})"
    }
}

/** API-1 value contract, including the original constructor and copy default bridges.
 * Content is added through withContent, not a fifth data-class constructor parameter:
 * already compiled plugins still link, and copying never silently drops attached content.
 */
class ToolResult private constructor(
    val text: String,
    val structured: JsonElement?,
    val isError: Boolean,
    val effect: ToolEffect?,
    content: List<ToolContent>,
) {
    val content: List<ToolContent> = content.toList()
    constructor(text: String = "", structured: JsonElement? = null, isError: Boolean = false,
        effect: ToolEffect? = null) : this(text, structured, isError, effect, emptyList())
    // Kotlin's former all-default primary constructor also exposed this Java ABI.
    constructor() : this("", null, false, null)
    fun withContent(content: List<ToolContent>): ToolResult = ToolResult(text, structured, isError, effect, content)
    fun copy(text: String = this.text, structured: JsonElement? = this.structured,
        isError: Boolean = this.isError, effect: ToolEffect? = this.effect): ToolResult =
        ToolResult(text, structured, isError, effect, content)
    operator fun component1() = text
    operator fun component2() = structured
    operator fun component3() = isError
    operator fun component4() = effect
    override fun equals(other: Any?): Boolean = other is ToolResult && text == other.text &&
        structured == other.structured && isError == other.isError && effect == other.effect && content == other.content
    override fun hashCode(): Int {
        val legacy = 31 * (31 * (31 * text.hashCode() + (structured?.hashCode() ?: 0)) + isError.hashCode()) + (effect?.hashCode() ?: 0)
        return if (content.isEmpty()) legacy else 31 * legacy + content.hashCode()
    }
    override fun toString(): String = "ToolResult(text=$text, structured=$structured, isError=$isError, effect=$effect" +
        (if (content.isEmpty()) ")" else ", content=$content)")
}

/** Immutable host identifiers and explicit host hooks. Unattended calls cannot prompt. */
class ToolEnv(
    val runId: String,
    val sessionId: String,
    val callId: String,
    val unattended: Boolean,
    private val onProgress: (String, Float?) -> Unit,
    private val onApproval: suspend (String, ToolRisk) -> Boolean = { _, _ -> false },
    private val onInput: suspend (String, JsonObject) -> JsonObject? = { _, _ -> null },
) {
    fun progress(text: String, fraction: Float? = null) = onProgress(text, fraction)
    // The trusted host hook enforces unattended policy without prompting; it may use an existing
    // ALLOW_ALWAYS grant. Default remains deny. Keep the constructor/method ABI unchanged.
    suspend fun requestApproval(summary: String, risk: ToolRisk): Boolean = onApproval(summary, risk)
    suspend fun requestInput(prompt: String, schema: JsonObject): JsonObject? =
        if (unattended) null else onInput(prompt, schema)
}
