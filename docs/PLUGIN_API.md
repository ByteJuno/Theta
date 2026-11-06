# Theta Core 插件 API

草案 v0.3，对应 API 等级 1，2026-11-06。S1 可行性验证通过之前，这里的接口都可能改。背景和取舍见 [DESIGN.md](DESIGN.md)，模型和工具调用的完整约定见 [PROTOCOL.md](PROTOCOL.md)。

## 1. 约定

- 回调默认在后台线程，`@Composable` 除外。
- 所有 `suspend` 函数都要响应取消，宿主停用插件时会取消。
- 初始化、清理和单次调用有时限；模型流使用协议规定的超时，`EntryAdapter.attach` 的常驻监听期不设总超时。超时判定失败，不保证终止非协作代码，所以不要在回调里阻塞线程。
- 宿主受管调用的可捕获异常应归因到插件；不承诺捕获任意线程、JNI 或 Compose 组合期异常。受管协程异常恢复与 Compose 崩溃后的冷启动恢复仍是未完成的 S1 验收门槛。
- 同一插件内扩展的 `id` 不能重复。全局 ID 是 `插件ID/扩展ID`。

## 2. 清单

dex 和 Hook 插件放在 `assets/theta-plugin.json`，数据插件放在 zip 根目录。

```json
{
  "schema": 1,
  "id": "io.github.alice.gemini",
  "name": "Gemini 原生协议",
  "version": "1.2.0",
  "versionCode": 12,
  "type": "dex",
  "apiLevel": 1,
  "entry": "io.github.alice.gemini.GeminiPlugin",
  "provides": ["provider", "settings"],
  "declares": ["network"],
  "requires": { "root": false, "lsposed": false },
  "hooks": []
}
```

| 字段 | 说明 |
|---|---|
| `id` | 全局唯一，建议用反向域名 |
| `versionCode` | 递增整数，只能升不能降 |
| `type` | `data`、`dex` 或 `hook` |
| `apiLevel` | 编译时用的 API 等级 |
| `entry` | 实现 `ThetaPlugin` 的类，数据插件不需要 |
| `provides` | 提供的扩展类型，用于“扩展”页面展示 |
| `declares` | 用到的能力。只用于告知用户，不做强制 |
| `hooks` | 见第 5 节。声明了受限目标的插件不能安装 |

## 3. 入口

```kotlin
interface ThetaPlugin {
    /** 注册扩展。不要做耗时工作。超时判定启用失败，宿主撤销已有注册并标记待重启。 */
    suspend fun onEnable(ctx: PluginContext)

    /** 释放宿主管不到的资源，关闭数据库和文件。超时判定清理失败，宿主继续撤销注册并标记待重启。 */
    suspend fun onDisable() {}
}

interface PluginContext {
    val pluginId: String
    val hostApiLevel: Int
    val appContext: Context
    val scope: CoroutineScope     // 停用时取消
    val dataDir: File             // 插件私有目录
    val assets: PluginAssets      // 读取插件包内的 assets/
    val prefs: PluginPrefs        // 普通设置
    val secrets: PluginSecrets    // 密钥，存在内核的加密存储里
    val strings: PluginStrings    // 随语言切换的文字
    val blobs: BlobStore          // 图片、音频等大内容
    val log: PluginLog

    fun register(extension: Extension): Registration
    fun onCleanup(action: () -> Unit): Registration   // 登记自己开的线程、广播接收器等
}

interface Extension { val id: String }

/** 撤销宿主注册。不代表插件的代码已经停止。 */
interface Registration { fun revoke() }

interface BlobStore {
    suspend fun put(mimeType: String, data: InputStream): BlobRef
    suspend fun open(ref: BlobRef): InputStream
}
```

开始停用时，宿主先持久化停用意愿，再原子关闭 `PluginContext` 的登记入口，撤销已有注册并取消作用域；启用失败也进入关闭与撤销流程。`register` 和 `onCleanup` 都与关闭操作同步：已成功登记的内容必须纳入清理，关闭后调用会抛 `PluginClosedException`。`onCleanup` 登记失败时，插件负责立即释放尚未交接的资源。宿主在独立清理任务中调用 `onDisable`，再逆序执行 cleanup，不能重新登记扩展。有界的是等待时间，不保证非协作回调完成，也不强停线程或 JNI。

已批准的原生 dex 生命周期维持全信任同进程执行，不改变以上接口：同一 ID 一旦在当前进程尝试加载，无论成功、失败或超时，都不在重启前创建第二个实例；重新启用只保存下次冷启动意愿。更新包写入新路径，保留旧运行实例，冷启动才启用新版本。已加载插件卸载先保存删除标记（tombstone），撤销登记和取消作用域，冷启动再删除包；数据默认保留，只有明确选择才删除。安全模式跳过加载也必须冷启动，向已有进程发恢复请求不卸载代码。不承诺热卸载或类加载器 GC，回收观察不能解除进程内门控。

`dataDir`、`prefs`、`secrets` 是设计中更新回滚能恢复的全部数据，边界见 DESIGN.md 第 6 节。快照与自动回滚仍待实现和验收；原验收历史、生命周期要求变更及未完成门槛见 DESIGN.md 第 11 节和 S1_VALIDATION.md。

示例：

```kotlin
class HelloPlugin : ThetaPlugin {
    override suspend fun onEnable(ctx: PluginContext) {
        ctx.register(EchoTool())
    }
}
```

## 4. 扩展点

### 模型提供商和工具

`ModelProvider` 和 `Tool` 是最核心的两个扩展点，接口和完整的调用约定见 [PROTOCOL.md](PROTOCOL.md)：

- 模型侧：模型信息、请求、消息、流式事件、结束原因、错误和重试、用量、回放数据。
- 工具侧：命名、Schema、调用状态、审批、执行、结果、超时和崩溃恢复。
- MCP 和四种 API 协议的映射。

### 入口适配器

把一个触发方式接到 Theta 上，比如语音助手、快捷方式、耳机按键。

```kotlin
interface EntryAdapter : Extension {
    val displayName: String
    fun isAvailable(device: DeviceInfo): Boolean
    suspend fun attach(host: EntryHost)    // 一直挂着，取消时退出
}

interface EntryHost {
    fun ready()    // 初始化完成；每次 attach 仅调用一次
    suspend fun open(request: EntryRequest): EntrySession
}

interface EntrySession {
    val events: Flow<SessionEvent>
    suspend fun send(text: String)
    fun close()
}

sealed interface SessionEvent {
    interface Text : SessionEvent { val delta: String }
    interface ToolActivity : SessionEvent { val toolName: String; val state: String }
    interface NeedsApproval : SessionEvent { val summary: String }
    interface Finished : SessionEvent
    interface Failed : SessionEvent { val message: String }
}
```

入口适配器看到的是整理过的会话事件，不是模型的原始事件。

`attach` 在插件作用域的独立子任务中运行。完成监听器注册后调用 `host.ready()`，之后持续挂起到停用，并在 `finally` 中释放监听器。初始化默认限时 30 秒；逾期未 ready 则取消任务并判定挂接失败，迟到的 ready 不改变失败状态。ready 之后不设监听期总超时，也不因长时间没有输入判失败。正常停用引发的取消不计作插件错误；意外返回或抛异常则将入口标为不可用，不自动反复重启。

### 输出位置

内核发布任务进度，由选中的输出位置展示。默认是普通通知。

```kotlin
interface OutputSurface : Extension {
    val displayName: String
    val priority: Int
    fun isAvailable(device: DeviceInfo): Boolean
    fun show(task: TaskProgress)
    fun clear(taskId: String)
}
```

### 设置页

每个插件可以注册设置页，由“扩展”页面进入。

```kotlin
interface SettingsPage : Extension {
    val title: String
    @Composable fun Content()
}
```

### 第二批预留

记忆、语音识别和朗读、外部 Agent 和运行环境、定时和事件触发、消息渲染器。这些接口等第一批稳定后再定，等级 1 不包含。

## 5. Hook 插件

第三方 Hook 第一版只开放普通 App 进程，受限目标见 DESIGN.md 第 9 节。清单里声明要 Hook 的目标：

```json
{
  "type": "hook",
  "requires": { "root": false, "lsposed": true },
  "hooks": [
    {
      "package": "com.example.assistant",
      "entry": "io.github.alice.xiaoai.AssistantHook",
      "rules": "rules/assistant.json"
    }
  ]
}
```

```kotlin
interface HookEntry {
    fun onPackageReady(env: HookEnv)
}

interface HookEnv {
    val packageName: String
    val versionCode: Long
    val classLoader: ClassLoader
    val rules: RuleSet        // 按目标版本选出的匹配规则
    val host: HostChannel     // 回到 Theta 的调用通道，宿主校验调用方

    /** SDK 包装回调：异常会被捕获并计数，停用后放行原方法。 */
    fun hook(target: Executable, callback: HookCallback): Registration
}
```

规则文件按目标 App 的版本范围组织：

```json
{
  "schema": 1,
  "ranges": [
    { "minVersionCode": 507000000, "maxVersionCode": 507999999, "targets": { "speechEntry": { "dexkit": "..." } } }
  ]
}
```

规则可以用数据插件单独更新，这个数据插件在清单里写 `"rulesFor": "<Hook 插件 ID>"`。匹配不到当前版本时，这个 Hook 不生效，并在“扩展”页面显示“不支持这个版本”，不会盲目 Hook。

Hook 插件必须通过 `env.hook` 注册回调。绕过它的 Hook 和插件自己开的线程，不受异常保护和全局开关管理，只能靠重启目标进程退出。恢复路径见 DESIGN.md 第 7 节。

## 6. 数据插件

```
theta-plugin.json
skills/<名称>/SKILL.md
personas/<名称>.json
lang/<语言>.json
providers/<名称>.json
mcp/<名称>.json
```

提供商配置：

```json
{
  "id": "example",
  "name": "Example",
  "protocol": "openai-chat",
  "baseUrl": "https://api.example.com/v1",
  "models": [
    { "id": "example-large", "contextWindow": 128000, "reasoningLevels": ["low", "high"] }
  ],
  "compat": {
    "maxTokensField": "max_completion_tokens",
    "reasoningField": "reasoning_content",
    "replayReasoning": true,
    "streamUsage": true,
    "schemaProfile": "BASIC"
  }
}
```

`protocol` 取 `openai-chat`、`openai-responses`、`anthropic` 或 `gemini`。`models` 里的字段都可以省略，含义见 PROTOCOL.md 第 2.2 节。

`compat` 用来处理各家 OpenAI 兼容服务的差异，只对 `openai-chat` 生效：

| 字段 | 说明 |
|---|---|
| `maxTokensField` | 输出上限用哪个字段名 |
| `reasoningField` | 推理内容在响应里的字段名 |
| `replayReasoning` | 工具循环里是否要把推理内容传回去 |
| `streamUsage` | 是否在流式请求里要用量 |
| `schemaProfile` | 工具 Schema 的降级档位，见 PROTOCOL.md 第 3.3 节 |

MCP 配置：

```json
{ "id": "home", "name": "家庭服务", "url": "https://mcp.example.com/mcp", "auth": "oauth" }
```

安装时，确认页会显示 `baseUrl` 和 `url` 的域名，因为它们决定 Key 和工具参数发给谁。

## 7. 构建

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.bytejuno.theta-plugin")
}

theta {
    id = "io.github.alice.gemini"
    apiLevel = 1
    entry = "io.github.alice.gemini.GeminiPlugin"
}
```

Gradle 插件负责：

- 引入 SDK 和对应 API 等级的 BOM。
- 生成清单。
- 不把共享库打进插件包。
- 检查用了 `R` 类或 `@Experimental*` API 的情况，有就让构建失败。

## 8. 兼容规则

插件作者要遵守：

- 不依赖内核内部类，也不用反射访问。
- 不打包共享库。
- 对 SDK 里的 `sealed` 类型写 `when` 时要加 `else`，以后可能增加新的子类型。
- 自己开的线程和注册的系统回调要用 `onCleanup` 登记，否则卸载后可能残留。
- 不要把插件的对象放进静态字段或全局单例，以减少残留引用；遵守此约定也不保证类加载器被 GC，不能据此要求热卸载或同进程重新启用。
- 不要在回调里阻塞线程。超时后宿主不再等待，但你的代码可能还在运行。
- 数据放在 `dataDir`、`prefs`、`secrets` 里，放在别处的数据回滚时恢复不了。
- `onDisable` 里关闭数据库和文件，否则更新时的快照可能不一致。
