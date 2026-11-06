# Theta Core 模型与工具调用协议

草案 v0.2，对应 API 等级 1，2026-11-06。S1 通过之前，这里的接口都可能改。插件的基本结构见 [PLUGIN_API.md](PLUGIN_API.md)。

本文规定内核、模型提供商、工具三方之间的约定。写的时候核对了 OpenAI、Anthropic、Gemini 和 MCP 2026-07-28 的官方文档，来源在文末。

下面的 Kotlin 是接口草图。对象都由 SDK 的 builder 创建，比如 `toolSpec("search") { risk = ToolRisk.READ }`。这样以后加字段不破坏二进制兼容。

## 1. 术语和流程

- **运行（run）**：一次冻结配置下的执行段，通常从用户消息到最终回答；也可以在完整工具步骤之后以检查点结束，再关联一个后续运行。一次运行可能调用模型多次，检查点续接不是自动重做原任务。
- **步骤（step）**：一次模型调用，加上这次调用产生的所有工具调用。
- **适配器**：把本协议翻译成某家 API 的代码，也就是 `ModelProvider` 的实现。

```
用户消息
   │
   ▼
冻结运行配置（模型、思考档位、工具目录）
   │
   ▼
调用模型 ◀──────────────────────────┐
   │ 收到完整的助手消息              │
   ├─ 有工具调用 ─▶ 校验 ─▶ 审批 ─▶ 执行 ─▶ 每个调用都写入结果
   │
   └─ 没有工具调用 ─▶ 最终回答
```

**运行内冻结**：模型、思考档位、工具目录在一次运行内不变，切换备用模型是唯一的例外。Anthropic 不支持在工具循环中途切换思考配置：这样做不会报错，但这次请求的思考会被静默关闭。改动档位还会让提示缓存失效。

## 2. 模型侧

### 2.1 提供商接口

```kotlin
interface ModelProvider : Extension {
    val displayName: String
    val authMethods: List<AuthMethod>
    suspend fun listModels(auth: Credentials): List<ModelInfo>

    /** 发送前声明；未知或透传配置无法判断时按可能含服务端执行处理。 */
    fun executionMode(request: ChatRequest): ModelExecutionMode = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS

    /** 必须能完整消费该载荷及其引用，不能只比较协议名。 */
    fun canReplay(data: ReplayData, targetModel: String): Boolean = false

    /** 冷流：收集一次发一次请求。取消收集时必须断开连接。 */
    fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent>
}

enum class ModelExecutionMode { GENERATION_ONLY, MAY_EXECUTE_SERVER_TOOLS }
```

适配器只负责翻译，不做重试、不换 Key、不压缩上下文，这些都由内核统一处理。OpenAI 兼容的服务不用写代码，用数据插件的提供商配置就行。

### 2.2 模型信息

```kotlin
interface ModelInfo {
    val id: String
    val displayName: String
    val contextWindow: Int?
    val maxOutputTokens: Int?
    val reasoningLevels: List<String>?
    val defaultReasoningLevel: String?
    val inputKinds: Set<MediaKind>?       // 图片、音频、文档、视频
    val outputKinds: Set<MediaKind>?
    val tools: ToolSupport?
    val promptCache: Boolean?
    val structuredOutput: Boolean?
}

interface ToolSupport {
    val supported: Boolean
    val parallel: Boolean?
    val forcedChoice: Boolean?            // 能否强制调用工具
    val schemaProfile: SchemaProfile?     // 见 3.3
    val maxTools: Int?
    val maxNameLength: Int?
    val mediaInResults: Boolean?          // 工具结果里能否放图片等媒体
}
```

**`null` 表示未知，不表示不支持。** 内核遇到未知时先尝试，收到 `UNSUPPORTED_PARAMETER` 再记下来。上下文窗口未知时也能正常聊天，只是不做主动压缩，等 `CONTEXT_TOO_LONG` 再压缩。用户可以手动覆盖所有字段。

### 2.3 请求

```kotlin
interface ChatRequest {
    val requestId: String
    val model: String
    val system: String?
    val messages: List<Message>
    val tools: List<ModelTool>            // 已映射名称和 Schema，见 3.2、3.3
    val toolChoice: ToolChoice
    val parallelToolCalls: Boolean?
    val reasoningLevel: String?
    val maxOutputTokens: Int?
    val temperature: Double?
    val topP: Double?
    val stopSequences: List<String>
    val responseSchema: JsonObject?       // 要求结构化输出时才有
    val extraBody: JsonObject             // 用户配置的透传字段，适配器原样合并
}

interface ModelTool {
    val name: String
    val description: String
    val inputSchema: JsonObject
}

sealed interface ToolChoice {
    object Auto : ToolChoice                               // 模型自己决定
    object None : ToolChoice                               // 不调用工具
    object Required : ToolChoice                           // 必须调用一个工具
    interface Named : ToolChoice { val name: String }      // 必须调用这个工具
}
```

**思考档位**：内核按 `reasoningLevels` 校验。档位不存在时选最接近的较低档并提示用户，不报错。

### 2.4 消息和内容

```kotlin
enum class Role { USER, ASSISTANT, TOOL }
enum class MediaKind { IMAGE, AUDIO, DOCUMENT, VIDEO }

/** 指向 BlobStore 里的一份内容。 */
interface BlobRef {
    val id: String
    val mimeType: String
    val sizeBytes: Long
}

interface Message {
    val role: Role
    val parts: List<ContentPart>
    val cacheBreakpoint: Boolean          // 见 2.11
}

sealed interface ContentPart {
    val replay: ReplayData?               // 见 2.10

    interface Text : ContentPart { val text: String }
    interface Media : ContentPart { val kind: MediaKind; val blob: BlobRef }
    interface Reasoning : ContentPart { val summary: String? }
    interface ToolCall : ContentPart { val callId: String; val name: String; val argsJson: String }
    interface ToolResult : ContentPart {
        val callId: String
        val name: String
        val content: List<ContentPart>    // 只含 Text 和 Media
        val isError: Boolean
    }
    interface Opaque : ContentPart        // 服务端工具调用等，内核不解析，只回放
}
```

- `Role.TOOL` 的消息只含 `ToolResult`。
- 系统提示放在 `ChatRequest.system`，不放进消息。
- 连续同角色的消息由适配器按对方要求合并。
- 用户消息里的媒体类型不在 `inputKinds` 内时，内核在发送前拒绝并告诉用户。

### 2.5 流式事件

```kotlin
sealed interface ModelEvent {
    interface Started : ModelEvent { val responseId: String?; val model: String? }

    interface TextDelta : ModelEvent { val index: Int; val text: String }
    interface ReasoningDelta : ModelEvent { val index: Int; val text: String }
    interface ToolCallStart : ModelEvent { val index: Int; val wireId: String?; val name: String }
    interface ToolCallDelta : ModelEvent { val index: Int; val argsFragment: String }
    interface MediaPart : ModelEvent { val index: Int; val kind: MediaKind; val blob: BlobRef }
    interface OpaquePart : ModelEvent { val index: Int }
    interface PartEnd : ModelEvent { val index: Int; val replay: ReplayData? }

    interface Usage : ModelEvent { val usage: TokenUsage }
    interface KeepAlive : ModelEvent

    interface Finished : ModelEvent { val reason: FinishReason; val rawReason: String? }
    interface Failed : ModelEvent { val error: ModelError }
}
```

### 2.6 事件规则

1. `index` 从 0 开始，代表这个 part 在助手消息里的位置。内核按 `index` 保存，顺序不能变。
2. 同一 `index` 的类型不能变。不同 `index` 的增量可以交错，比如并行工具调用。
3. 每个 part 以 `PartEnd` 结束。整段返回的内容（比如 Gemini 的函数调用）也要发 `ToolCallStart`、一次或多次 `ToolCallDelta` 和 `PartEnd`。按事件大小上限拆分参数字符串，拼接后必须与原始 JSON 一致，不得截断或改成 BlobRef。
4. 最后一个事件必须是 `Finished` 或 `Failed`，只能有一个。流结束时两者都没有，按 `PROTOCOL` 错误处理。
5. 流里抛出的异常按 `Failed` 处理，取消除外。
6. 单个事件不超过 64 KiB。图片、音频等大内容先写入 `BlobStore`，再发 `MediaPart`，绝不走文本增量。
7. 收到 `KeepAlive` 时内核重置空闲计时器。推理模型可能很久没有输出，适配器看到对方的心跳就要转发。

**宿主调度的工具只在 `Finished` 之后、且结束原因允许执行时运行。** 流失败时不执行其中的客户端工具，但服务端工具可能已经执行；不能由此推导整个模型请求没有副作用。发送前按适配器的 `executionMode` 冻结本次请求类型，透传 `extraBody` 可能启用的服务端工具也必须计入。未知按 `MAY_EXECUTE_SERVER_TOOLS` 处理；重试、换 Key 和备用模型统一受第 2.8 节的恢复门槛约束。

**默认超时**（可配置）：首个事件 180 秒，事件之间 120 秒。超时后内核取消收集并按 `TIMEOUT` 处理。这同样是判定失败，不保证非协作的适配器已经停止。

### 2.7 结束原因

| `FinishReason` | 含义 | 内核的处理 |
|---|---|---|
| `STOP` | 正常结束 | 没有工具调用就结束运行。内容为空见下文 |
| `TOOL_CALLS` | 要调用工具 | 进入第 3 章的流程 |
| `LENGTH` | 达到输出上限 | 按下文截断收尾规则处理，不执行这条消息中的客户端工具 |
| `CONTEXT_EXCEEDED` | 输出占满上下文窗口 | 先按截断规则收尾，再按 2.11 检查点规则恢复；不能直接重发原请求 |
| `PAUSED` | 服务端工具循环未完成 | 把助手消息原样放回，再次请求，有次数上限 |
| `REFUSAL` | 模型拒绝回答 | 不回放这条消息，有备用模型就换，否则告诉用户 |
| `CONTENT_FILTER` | 被内容过滤拦截 | 同 `REFUSAL` |
| `OTHER` | 无法归类 | 按 `STOP` 处理，记录 `rawReason` |

- **以 `STOP` 结束但有完整的工具调用时，按 `TOOL_CALLS` 处理。** 有些 API 没有专门的工具结束原因，第三方兼容服务也常这样返回。
- **截断收尾（`LENGTH`、`CONTEXT_EXCEEDED`）**：只要有未完成的客户端工具调用，这条助手消息就不进入可回放历史，任何调用都不执行。`LENGTH` 可以在 2.8 的恢复门槛允许时提高输出上限重试一次；不允许或再次失败则暂停并告诉用户。`CONTEXT_EXCEEDED` 转入 2.11，不能靠扩大输出上限恢复。
- 如果截断消息里的客户端调用全部完整，保留助手消息，但每个调用都补一个 `CANCELLED`、`effect=NONE` 的未执行结果，说明“回答截断，未执行”。补齐后 `LENGTH` 暂停等用户决定，`CONTEXT_EXCEEDED` 进入检查点恢复。无客户端调用时保留可见内容并采用同样的暂停或恢复策略。不得因调用参数完整就默默执行截断消息。
- 上述 `NONE` 只描述未执行的客户端调用，不代表服务端操作没有发生；服务端执行状态仍按 2.8 单独处理。
- **空响应**：`STOP` 但没有任何内容时，这条消息不写入历史，最多重试一次，再失败就告诉用户。

### 2.8 错误和重试

```kotlin
interface ModelError {
    val code: ModelErrorCode
    val message: String
    val httpStatus: Int?
    val retryAfterMs: Long?
    val param: String?                    // UNSUPPORTED_PARAMETER 时的参数名
}
```

| `ModelErrorCode` | 内核的处理 |
|---|---|
| `AUTH` | 刷新凭据重试一次，失败就提示重新登录 |
| `RATE_LIMIT` | 按 `retryAfterMs` 等待。配了多个 Key 就换一个 |
| `QUOTA` | 换 Key 或备用模型，否则告诉用户 |
| `OVERLOADED`、`SERVER`、`NETWORK`、`TIMEOUT` | 指数退避，最多 3 次，再走备用模型 |
| `CONTEXT_TOO_LONG` | 按 2.11 缩小请求或检查点续接；只在请求确实变小后重试一次，不能原样重发 |
| `UNSUPPORTED_PARAMETER` | 去掉这个参数重试一次，并记入模型信息 |
| `INVALID_REQUEST`、`MODEL_NOT_FOUND` | 不重试，告诉用户 |
| `CONTENT_BLOCKED` | 同 `REFUSAL` |
| `PROTOCOL` | 算适配器的错误，计入插件错误数，可以重试一次 |
| `UNKNOWN` | 重试一次，再失败就告诉用户 |

**恢复门槛先于上表，也适用于截断、空响应、换 Key 和备用模型：**

- `GENERATION_ONLY` 请求可以按表重试，但仍可能产生重复推理计费；“可重试”不表示免费。
- `MAY_EXECUTE_SERVER_TOOLS` 请求一旦提交，发生断流、超时或无法确认远端结果时，记录“服务端执行状态未知”并暂停。不自动重发、不换 Key 重做，也不通过备用模型重做。用户核对结果后可以显式选择再次尝试，无人值守时保持暂停。第一版不提供通用服务端请求恢复/去重接口，不能只凭 `requestId` 或 `responseId` 假定远端会去重。
- 正常收到 `PAUSED` 是同一服务端循环的显式延续，不是失败重试；保留完整历史和配置继续。若延续请求也断流，同样暂停。未知服务端执行未处理前，不做检查点自动续接。
- 流开始后才出错的（比如 Anthropic 在 HTTP 200 之后发 `error` 事件）也发 `Failed`。
- 允许重试时，这一步的部分内容不进入可回放历史；界面、请求 ID、已知用量及原始失败记录仍保留，不能抹掉执行证据。迟到的响应不能重复提交历史。
- 切换备用模型前按 2.10 检查所有需要回放的数据。不能兼容时走 2.11 的检查点新运行，不在旧工具循环里直接删掉回放数据继续。

### 2.9 用量

```kotlin
interface TokenUsage {
    val inputTokens: Long?       // 全部输入，含缓存读取和写入
    val cacheReadTokens: Long?   // inputTokens 中的缓存读取
    val cacheWriteTokens: Long?  // inputTokens 中的缓存写入
    val outputTokens: Long?      // 全部输出，含推理
    val reasoningTokens: Long?   // outputTokens 中的推理
}
```

- 各家的统计口径不同，适配器要换算成上面的口径。比如 Anthropic 的 `input_tokens` 不含缓存部分，要把三项加起来；Gemini 单独列出 `total_thought_tokens`，计费按输出加思考算，要把两项合并。
- `Usage` 事件是累计快照，后一个覆盖前一个。
- 不知道的字段填 `null`，不要填 0。

### 2.10 回放数据

有些 API 要求把上一步的推理内容原样传回。

```kotlin
interface ReplayData {
    val protocol: String     // 比如 anthropic
    val model: String
    val payload: String      // 适配器自己的格式，内核不解析
}
```

- 适配器在 `PartEnd` 里交出回放数据。**内核原样保存，不解析、不修改、不重排。** 宿主只向当前选中的提供商交付请求历史，其中包括获选的备用提供商，不向无关扩展广播；这不是针对同进程 dex 的保密隔离。
- 发送前由选中的适配器通过 `canReplay(data, targetModel)` 判断能否完整消费每份回放数据，包括格式、目标模型规则及任何间接引用。协议名相同不是充分条件；默认返回 false，使用回放数据的适配器必须实现该判断。
- 全部兼容才可在原运行继续。任何必需回放无法使用时，不部分剥离旧助手消息；按 2.11 保存原始历史，并从可见内容和工具账本生成摘要后开启新运行。界面明确说明这是摘要续接，不是原样恢复；未解决的副作用和资源隔离不能因此清空。
- 回放数据可以挂在任何 part 上。Gemini 的签名可能在函数调用或文本上，所以不能只挂在 `Reasoning` 上。
- 回放数据不是秘密，内核按普通对话内容存储。
- 不回放的情况见第 4 章第 5 条。

### 2.11 上下文和缓存

**缓存**：

- 请求按“工具目录、系统提示、历史、新内容”的顺序组织，保持前缀稳定。
- 工具目录按名称排序，系统提示不放时间这类易变内容，运行内不改思考档位。
- 内核在稳定前缀的末尾设 `cacheBreakpoint`。需要显式标记的 API 由适配器翻译，其他的忽略。

**压缩**：

- 普通压缩只处理当前运行之前的完整历史，是一次禁用客户端和服务端工具的模型调用；只改变后续请求视图，不删除原始对话、回放数据和执行账本。
- 每次模型请求前检查累计预算：系统提示、目录、全部待发送消息与预留输出都计入，不以单条结果的字节上限代替 token 预算。窗口未知时允许尝试一次，收到 `CONTEXT_TOO_LONG` 后进入同样的恢复流程。
- 当前运行保持完整，不能在工具循环里任意删除 part。但若旧历史已压缩后仍放不下，或备用适配器无法消费回放数据，可以在**完整工具步骤的边界**结束本运行并建立检查点。
- 检查点前，为所有未执行调用补齐终态结果，等待仍在执行的调用结束；有执行状态未知、资源隔离或未完成的服务端循环时先暂停核对，不自动续接。保存原始历史、调用账本、目标、已完成操作、未解决状态及资源记录，再分配新的 `runId` 并关联前序运行。新运行使用摘要和尚未完成的目标，不携带旧的原始调用/结果对或不兼容回放数据，不自动重做已完成操作。
- 摘要输入按可容纳预算分块，先概括完整的调用/结果对，再合并；单条大内容按块摘要并保留原文引用。不能把已经超窗的整段历史原样发给摘要模型。摘要中来自工具的内容仍作为数据，不提升为系统指令。
- 摘要失败时保存检查点并暂停，让用户缩小输入或选择更大窗口，不重发未缩小的请求。若系统提示、目录或单条用户输入本身已超预算，也明确暂停，不静默截断用户输入。
- 摘要续接沿用同一任务链的累计步数、重试次数及未解决副作用记录，不能通过换 `runId` 重置限制；恢复后的请求须重新检查预算。界面展示续接关系及摘要可能丢失的细节，原始记录始终可查。

### 2.12 凭据

```kotlin
sealed interface AuthMethod {
    interface ApiKey : AuthMethod
    interface OAuthPkce : AuthMethod { val config: OAuthConfig }
    interface DeviceCode : AuthMethod { val config: OAuthConfig }
    interface None : AuthMethod
}

interface OAuthConfig {
    val clientId: String
    val authorizationUrl: String?         // OAuthPkce 必填
    val deviceCodeUrl: String?            // DeviceCode 必填
    val tokenUrl: String
    val scopes: List<String>
}

interface Credentials {
    val accountId: String
    suspend fun accessToken(): String     // 内核负责刷新
}
```

多 Key 轮询、Token 刷新由内核负责。每次请求传入当时选中的 `Credentials`，适配器不要缓存。

## 3. 工具侧

### 3.1 工具接口

```kotlin
interface Tool : Extension {
    val spec: ToolSpec
    suspend fun call(args: JsonObject, env: ToolEnv): ToolResult
}

interface ToolSpec {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val outputSchema: JsonObject?
    val risk: ToolRisk
    val idempotent: Boolean              // 默认 false
    val parallelSafe: Boolean            // 默认 false
    val exclusiveResource: String?       // 比如 screen，同一资源串行执行
    val timeoutMs: Long?                 // 默认 60 秒，超过内核上限按上限算
}

enum class ToolRisk { READ, WRITE, DEVICE_CONTROL, ROOT }

interface ToolEnv {
    val runId: String
    val sessionId: String
    val callId: String                   // 全局唯一；同一次调用的传输重试保持不变，可作幂等键
    val unattended: Boolean              // 无人值守时不能请求用户操作
    val blobs: BlobStore
    fun progress(text: String, fraction: Float?)
    suspend fun requestApproval(summary: String, risk: ToolRisk): Boolean
    suspend fun requestInput(prompt: String, schema: JsonObject): JsonObject?
}
```

`risk` 是工具自己声明的，内核无法验证。用户可以调高单个工具的审批级别。

### 3.2 目录和命名

- **全局键**：`插件ID/扩展ID`，MCP 工具是 `mcp/连接ID/工具名`。授权和账本都用它。
- **模型看到的名称**：内核生成，只含字母、数字、下划线、连字符，默认不超过 64 个字符。插件前缀加工具名，太长或冲突时加短哈希，生成规则要确定，这样缓存才稳定。MCP 名称里的点也要替换。
- **反查**：调用回来时按运行内的映射表反查，不解析名称。
- **目录**：运行开始时生成，按名称排序，运行内不变。
- **不在目录里的调用**：不执行，返回 `REJECTED`，不中断运行。工具后来被卸载，也按这样处理。
- **超出 `maxTools`**：按用户的启用设置和优先级裁剪，并在界面上提示。

### 3.3 Schema

- 工具声明用 JSON Schema 2020-12。内核**只按原始 Schema 校验参数**。
- 发给模型前，内核按 `SchemaProfile` 降级：

| `SchemaProfile` | 含义 |
|---|---|
| `FULL` | 原样发送 |
| `NO_TOP_LEVEL_COMBINATORS` | 顶层不能有 `oneOf`、`anyOf`、`allOf` |
| `BASIC` | 只保留 `type`、`properties`、`items`、`enum`、`required`、`description` |

- `$ref` 先展开，有深度和大小限制。
- 降级丢失的约束写进字段描述。无法降级的工具不发给模型，并在界面上提示。
- 只有 Schema 符合对方严格模式的要求时，适配器才打开严格模式。

### 3.4 调用状态

```
收到 ─▶ 校验 ─▶ 等待审批 ─▶ 执行中 ─▶ 成功、失败、超时、已中止、丢失
         │          │
         └──────────┴─▶ 已拒绝
```

| 状态 | 什么时候 | `effect` |
|---|---|---|
| `SUCCEEDED` | 工具正常返回 | 工具声明，默认 `APPLIED` |
| `FAILED` | 工具返回错误或抛异常 | 工具声明，默认 `UNKNOWN` |
| `REJECTED` | 工具不存在、参数无效、审批被拒 | `NONE` |
| `CANCELLED` | 用户中止 | 执行前 `NONE`，执行中 `UNKNOWN` |
| `TIMED_OUT` | 超时 | `UNKNOWN` |
| `LOST` | 执行中进程死了 | `UNKNOWN` |

`effect` 表示这次调用是否产生了副作用：`NONE` 没有，`APPLIED` 已生效，`UNKNOWN` 不确定。按本次调用的**有效风险**判断，而不只看初始 `ToolSpec.risk`。有效风险初始为声明风险与用户上调值中的较高者；内部更高风险审批获准后，在 `requestApproval` 返回 true 之前持久化提升，直到调用结束不降低。顺序为 `READ < WRITE < DEVICE_CONTROL < ROOT`，风险升级不自动把新的动作变成可并行。

只有有效风险一直为 `READ` 且未报告矛盾副作用时，才将默认 `effect` 归为 `NONE`。工具显式报告 `APPLIED` 或 `UNKNOWN` 时保留该事实并记录声明不一致，不能覆盖为 `NONE`。有效风险升到写操作后，超时、中止和丢失采用表中的 `UNKNOWN`，同样适用未知副作用后的重试规则。宿主不能验证绕过 SDK 的真实行为。

### 3.5 ID 和校验

- **`callId`**：内核为每个新调用生成全局唯一的随机 UUID，并随账本持久化；绝不直接复用供应商 ID。不同会话、不同安装实例的新调用不能因相同 `wireId` 共用幂等键。工具调用和结果使用同一个内部 ID；同一次调用的允许传输重试仍使用它，模型重新生成的调用则是新调用，不能据此宣称业务上不会重复。
- **`wireId` 映射**：单独持久化 `(提供商、requestId、模型响应标识、part index、wireId) ↔ callId`，不能只以 wireId 为键。回放原协议时按该调用的映射还原调用和结果标识；缺失 ID 或跨协议转换时才生成符合目标格式的稳定映射，并检查同一请求内不冲突。原始回放载荷不因内部 ID 分配而改写。远端需要独立请求 ID 时，与工具幂等键分开管理。
- **参数解析**：必须能解析成 JSON 对象，否则返回 `REJECTED`，让模型修正。
- **空参数**：唯一允许的转换是，空字符串或 `null` 在 Schema 允许空对象时当作 `{}`。
- **其他情况不做类型转换**，参数原样交给工具，原始文本写入账本。

### 3.6 审批

```kotlin
enum class ApprovalDecision { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY }
```

判定顺序：

1. 用户设的拒绝规则。
2. 授权记录。授权绑定全局键和定义摘要。
3. 按风险的默认规则。

| 风险 | 第一方工具 | 第三方和 MCP 工具 |
|---|---|---|
| `READ` | 放行 | 首次询问 |
| `WRITE`、`DEVICE_CONTROL` | 询问 | 询问 |
| `ROOT` | 每次询问 | 每次询问 |

- **定义摘要**：名称、描述、输入 Schema、风险的 SHA-256。任何一项变了，授权就作废。这也覆盖插件更新和 MCP 服务器改定义。
- **当前运行的一致性**：运行快照、审批记录和待执行调用都绑定定义摘要及连接/插件版本。宿主在审批前和提交执行前分别比较快照与当前已知版本；检查与提交在同一连接/插件的调度门内串行化。收到 MCP 刷新通知或 TTL 到期时先把定义标为待刷新，暂停该连接的新提交；刷新成功且摘要未变才恢复，刷新失败保持暂停。摘要变化则将受影响的旧快照调用返回 `REJECTED`、`effect=NONE`，不按旧描述重新询问。已经提交的调用不撤销或改记为未执行，保留当时版本与真实结果。目录在下一运行重新生成；本地检查不能消除服务器未通知或提交后的远端变更。
- **MCP 工具的风险**：默认按 `WRITE` 算。MCP 规范要求把工具注解视为不可信，所以注解只用于展示。
- **`ROOT` 工具**：默认每次询问，用户要在设置里单独打开才能长期授权。
- **无人值守**：定时任务这类运行不能询问用户。按规则直接放行的工具照常执行，本来要询问的工具，只有已经 `ALLOW_ALWAYS` 才执行，否则返回 `REJECTED`。所有来源的工具都走这条规则。
- **副作用未知后的重试**：同一任务链（包括检查点续接）里，调用出现 `UNKNOWN` 后，用相同工具和参数重试时必须询问用户，不看授权记录；动态升级为写操作的调用同样适用。无人值守无法确认时返回 `REJECTED`。审批不能解除仍在运行的本地执行实例或资源隔离。

### 3.7 执行

- 同一步的调用按顺序校验和审批。
- 批准后，`parallelSafe` 的工具并发执行，其他工具串行。串行工具是屏障，前面的执行实例实际结束才开始，不能只看逻辑账本终态。
- 声明了同一个 `exclusiveResource` 的工具在整个宿主内串行执行，跨会话也共用资源租约。逻辑超时或中止不释放未停止实例的租约；排队调用不得开始，暂停受影响运行并提示资源不可用。
- 未确认结束的实例保留其并发槽位和屏障占用，不再为其启动传输重试。无 exclusiveResource 的串行工具也不能绕过这一规则。实例实际退出后可释放本地占用，但未知外部副作用仍须核对；工具自行脱离调用生命周期继续工作的线程不受此保证覆盖。
- 无法确认本地实例退出时，标记待重启，停止继续调度受影响插件。宿主重启只终止宿主进程内代码；目标进程、su 子进程或远程操作必须另行停止或核对。对仍未知的外部资源占用持久化隔离记录，不能在冷启动时自动清空，恢复入口明确展示哪些状态已确认、哪些只是用户接受不确定性。
- 结果按调用顺序写回，不按完成顺序。
- 一个调用被拒绝，不影响同一步的其他调用。用户也可以选择全部拒绝。
- 工具内部的高风险步骤用 `env.requestApproval`，拒绝返回 false，不得执行该步骤；获准时按 3.4 先持久化有效风险再返回 true。无人值守仍执行 3.6 的规则，需要交互而无法满足时返回 false。需要用户填写内容时用 `env.requestInput`，无人值守时返回 `null`。工具的 parallelSafe 和资源声明须覆盖可能发生的内部步骤，不能只覆盖最初的读操作。

### 3.8 结果和超时

```kotlin
interface ToolResult {
    val content: List<ContentPart>   // 只含 Text 和 Media
    val structured: JsonElement?
    val isError: Boolean
    val effect: ToolEffect?
}

class ToolException(message: String, val effect: ToolEffect?) : Exception(message)
```

**发给模型的内容：**

- 先归一化结果，再构造消息级 `ContentPart.ToolResult`：`structured` 存在且 `outputSchema` 存在时按它校验；没有输出 Schema 时接受该 JSON 值。校验成功或无需校验时，将 JSON 序列化为一个带“结构化结果”标签的 `Text`，放在原 `content` 之前，原有文本和媒体仍保留。因此 `content=[]` 的结构化结果也能进入模型历史，不要求各适配器自行猜测转换。
- `structured` 校验不通过时只保留原 `content` 并记录警告。若原 content 也为空，内核生成“工具返回的结构化结果未通过校验”文本并将模型结果标为错误；不能据此把已经发生的副作用改为 NONE。
- 归一化后的全部文本合计默认不超过 32 KiB，包括序列化后的 JSON 与原文本。超出部分存进 `BlobStore`，模型收到受限文本、截断说明和引用；截断的 JSON 仅作为文本展示，不冒充完整结构化对象。原始工具结果保留在账本中。
- 模型不支持工具结果带媒体时，换成文字占位。
- 工具结果是不可信数据，内核不让它获得系统提示的地位。

**超时、中止、丢失**由内核生成结果，让模型知道实际情况：

```json
{
  "status": "timed_out",
  "effect": "unknown",
  "message": "工具超时。操作可能已经生效，也可能还在运行。重试前先确认结果。"
}
```

- **超时判定失败，不保证终止非协作代码。** 内核取消协程后，工具可能还在运行。逻辑结果只提交一次；迟到返回不能覆盖终态或追加第二份模型结果，但需要记录为后续核对证据，并更新“执行实例是否实际退出”的独立状态。资源释放按 3.7，不由 TIMED_OUT/CANCELLED 自动触发。
- 进度上报可以重置超时，但总时长有上限，默认 10 分钟。
- 内核不自动重试工具。唯一的例外是明确声明 `idempotent` 的工具遇到传输层中断，且上一执行实例已退出、无资源隔离和待确认的未知副作用时；传输重试保留 callId，仍检查定义版本。模型重新发起调用走正常审批及 3.6 的未知副作用规则。

**账本**：调用进入执行中之前，先把调用记录、有效风险和资源租约落盘；逻辑结束后写入结果，执行实例状态与之分开维护。冷启动时发现还在执行中的逻辑记录，就标记为 `LOST`，生成唯一结果并暂停等用户决定，绝不自动重新执行。已经 TIMED_OUT/CANCELLED 但实际结束未确认的记录也进入恢复检查，保留原终态；不能因为不再是 RUNNING 就跳过外部资源隔离。

## 4. 历史规则

每次调用模型之前，内核都要保证这些规则成立。适配器可以依赖它们。

1. **每个工具调用有且只有一个结果。** 被拒绝、中止、超时、丢失的调用，都由内核生成结果。
2. **工具结果紧跟在产生它的助手消息之后**，全部放在一条 `TOOL` 消息里，顺序和调用一致。Gemini 要求所有调用在前、所有结果在后，交错会报 400。
3. **工具循环中途不插入用户文字。** Anthropic 的文档说，在 `tool_result` 后面加文字可能导致 400 或空响应。排队的消息等这次运行结束再发。要插话，就先中止运行，补齐结果，再发新消息。
4. **助手消息的 part 顺序和回放数据原样保存。** Anthropic 要求思考块不能调整顺序、修改或部分删除。
5. **下面这些助手消息不进入下一次请求：** 流中断或失败的、`REFUSAL` 和 `CONTENT_FILTER` 的、`LENGTH` 或 `CONTEXT_EXCEEDED` 时带未完成客户端调用的、空响应。它们只保留在界面和原始记录中。截断但调用全部完整的消息，只能在 2.7 补齐未执行结果后回放；不能提交带孤立调用的历史。检查点续接使用摘要而非伪造原始工具结果。
6. **`PAUSED` 的助手消息原样放回。**
7. **历史里的工具调用和结果成对保留或成对删除。** 压缩不能把它们拆开。

运行有上限，默认 50 步。相同工具和相同参数连续调用 3 次，内核暂停并询问用户。

## 5. MCP 映射

目标版本是 2026-07-28，同时兼容 2025-11-25。内核把 MCP 工具包装成 `Tool`，走完全相同的校验、审批和账本。

| MCP | Theta Core |
|---|---|
| `server/discover` | 协商版本。旧服务器走 `initialize` 流程 |
| `tools/list`、`nextCursor` | 翻到底，不设上限 |
| `ttlMs`、`notifications/tools/list_changed` | 先暂停该连接的新调用提交，再刷新。定义变化撤销授权并拒绝受影响的旧快照调用，下一运行重建目录；见 3.6 |
| `subscriptions/listen` | 订阅 `toolsListChanged` |
| `inputSchema`、`outputSchema` | 对应 `ToolSpec` |
| `annotations` | 不可信，只用于展示 |
| `x-mcp-header` | 不合规的工具按规范排除 |
| `content` 的 `text`、`image`、`audio` | 对应 `Text`、`Media` |
| `content` 的 `resource_link`、`resource` | 转成文字摘要，全文放 `BlobStore` |
| `structuredContent` | 对应 `structured`，再按 3.8 统一归一化到模型可见文本；有效 JSON 不得在历史转换时丢失 |
| `isError: true` | `FAILED`，内容交给模型自行修正 |
| JSON-RPC 错误 | 未知工具算 `REJECTED`，其他算 `FAILED`（`effect` 为 `UNKNOWN`） |
| `resultType: input_required` | 走 `requestInput`，用 `inputResponses` 和 `requestState` 重发，请求 ID 要换新的 |
| 缺少 `resultType` | 旧服务器，按 `complete` 处理 |
| `notifications/progress` | 对应 `env.progress` |
| 响应流中断 | 不把断流当成远端未执行。仅在 3.8 的幂等重试条件满足时用新的 JSON-RPC 请求 ID 重发，保留内部 callId；否则标记 UNKNOWN 并暂停核对 |
| 取消 | HTTP 关闭响应流，stdio 发 `notifications/cancelled` |

## 6. 四种协议的映射

这张表用来检查协议能否覆盖各家 API，也是内置适配器的实现依据。`gemini` 的官方文档现在分 generateContent 和 Interactions 两套，第一版只实现其中一套，到 S3 时按官方推荐来定。

| 概念 | `openai-chat` | `openai-responses` | `anthropic` | `gemini` |
|---|---|---|---|---|
| 工具定义 | `function.parameters` | `parameters` | `input_schema` | `parameters` |
| 强制调用 | `required` | `required` | `any` | `any` |
| 调用 ID | `tool_calls[].id` | `call_id` | `tool_use.id` | `id`，可能没有，由内核生成 |
| 调用结果 | `tool` 消息，`tool_call_id` | `function_call_output`，`call_id` | `tool_result`，`tool_use_id`，`is_error` | `functionResponse`；Interactions 用 `function_result`，`call_id` |
| 参数增量 | 按 `index` 拼 `arguments` | `response.function_call_arguments.delta` | `input_json_delta` | 按整段处理 |
| 推理回放 | 无统一字段，用 `compat` 配置 | `reasoning` 项随工具结果传回 | `thinking`、`signature`、`redacted_thinking` 原样传回 | `thoughtSignature` 放回原 part；Interactions 传回 `thought` |
| 思考档位 | `reasoning_effort` | `reasoning.effort` | `thinking: adaptive` 加 `output_config.effort` | `thinking_level` |
| 工具结束 | `tool_calls` | 输出里有 `function_call` | `tool_use` | 输出里有函数调用 |
| 长度截断 | `length` | `incomplete` | `max_tokens` | `MAX_TOKENS` |
| 拒绝和过滤 | `content_filter` | `refusal`、`content_filter` | `refusal` | `SAFETY` 等 |
| 暂停 | 无 | 无 | `pause_turn` | 无 |
| 流内错误 | 按错误体解析 | `response.failed` | `error` 事件 | 按错误体解析 |
| 缓存读取 | `cached_tokens` | `cached_tokens` | `cache_read_input_tokens` | 缓存字段 |
| 推理用量 | `reasoning_tokens` | `reasoning_tokens` | `thinking_tokens` | `total_thought_tokens` |

**几个必须注意的差异：**

- **Gemini 并行调用**：签名只在第一个 `functionCall` 上，必须放回原位。
- **Gemini 换模型**：Interactions 的文档要求继续传回上一个模型的 `thought`。
- **Anthropic 手动思考**：`thinking: enabled` 只支持 `auto` 和 `none` 两种 `tool_choice`，而且 4.7 及之后的模型不接受这种模式。
- **Anthropic 工具结果**：延续请求只放 `tool_result`，不加文字。用了服务端工具时，还要保持相同的 `tools`。
- **OpenAI 严格模式**：要求每层 `additionalProperties: false`，而且所有字段都在 `required` 里。

## 7. 一致性测试

SDK 附一套测试，用录制好的响应驱动适配器。下面这些来自 Eta 社区的真实问题，都要作为用例：

| 社区问题 | 对应规则 |
|---|---|
| 工具调用 ID 跨回合重复或为空，反代丢弃工具结果 | 3.5 内核分配 `callId` |
| Anthropic 拒绝回答后，下一轮思考块校验报 400 | 第 4 章第 5 条 |
| 工具 Schema 顶层 `oneOf` 导致 400 | 3.3 Schema 降级 |
| Gemini 无法调用工具 | 3.3 Schema 降级，2.10 回放数据 |
| 生图模型的 base64 被当成文本写进数据库 | 2.6 第 6 条 |
| MCP 工具超过 128 条被截断 | 第 5 章，翻到底 |
| MCP 工具的参数丢失 | 3.5 参数原样传递 |
| 工具未在本次运行的能力目录中声明 | 3.2 目录规则 |
| 指定推理档位就报错 | 2.3 档位校验，2.8 `UNSUPPORTED_PARAMETER` |
| 没填上下文窗口就不能聊天 | 2.2 `null` 表示未知 |
| 正常结束但响应为空 | 2.7 空响应 |
| 任务运行中无法追加消息 | 第 4 章第 3 条 |
| 缓存命中率偏低 | 2.11 缓存 |
| 上下文压缩失败，无法继续对话 | 2.11 压缩 |

**本轮规范回归用例**（待实现，不表示已经运行通过）：

| 场景 | 必须断言 |
|---|---|
| 两个会话都收到 wireId=call_1 | 内部 callId 不同；各自传输重试不变；供应商调用和结果映射正确 |
| content 为空、structured 为有效对象 | 模型历史中有序列化结果，且文本总限额包含该 JSON |
| structured 不合规且 content 为空 | 有明确错误文本，不改写真实副作用 |
| 整段返回 70 KiB 工具参数 | 多个增量均在事件上限内，拼接内容完全一致 |
| LENGTH/CONTEXT_EXCEEDED 含完整调用 | 不执行调用，补齐 NONE 的未执行结果后才保存可回放历史 |
| 截断消息混合完整及未完成调用 | 整条消息不回放、不执行其中任何客户端工具 |
| 服务端已执行工具后断流 | 不自动重试、换 Key 或切备用模型；记录未知状态并暂停 |
| 提供商 A 切换到 B | 检查所有 replay；兼容才原样交付，不兼容则检查点续接而非剥离 part |
| 当前运行自身超窗，无旧历史 | 有预算内的分块摘要路径；失败暂停，绝不原样重试超窗请求 |
| READ 工具获准 WRITE 后超时 | 升级先落盘，effect 为 UNKNOWN，重复调用必须重新确认 |
| screen 工具超时但仍运行 | 不启动同资源调用，不以逻辑终态释放租约；冷启动检查外部隔离 |
| MCP 定义刷新发生在审批后、提交前 | 阻止提交旧版本；刷新失败不放行，变化则拒绝旧快照调用 |
| 检查点续接多次 | 不重置累计步数、未知副作用和资源隔离，不重做账本中的已完成操作 |

## 8. 来源

2026-11-06 核对：

- [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [Anthropic thinking](https://platform.claude.com/docs/en/build-with-claude/thinking)
- [Anthropic extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
- [Anthropic stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)
- [Gemini function calling](https://aistudio.google.com/docs/function-calling)
- [Gemini thinking](https://aistudio.google.com/docs/thinking)
- [Gemini thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures)
- [MCP tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- [MCP cancellation](https://modelcontextprotocol.io/specification/2026-07-28/basic/utilities/cancellation)
- [MCP 2026-07-28 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)

OpenAI Chat Completions 的字段、`openai-responses` 的 `reasoning.effort` 和 `incomplete`、Gemini 的 `MAX_TOKENS` 和 `SAFETY`，这次没有逐项核对，实现适配器时要对照当时的官方文档。
