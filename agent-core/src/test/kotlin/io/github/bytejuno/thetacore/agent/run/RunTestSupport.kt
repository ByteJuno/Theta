package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class TestStore : InvocationStore {
    private val data = linkedMapOf<String, InvocationRecord>()
    private var bound = false
    override fun bindCoordinator() { check(!bound); bound = true }
    override fun records() = data.values.toList()
    override fun save(record: InvocationRecord, expectedRevision: Long?) {
        check(data[record.callId]?.revision == expectedRevision)
        record.validate()
        data[record.callId] = record
    }
    override fun close() = Unit
}
internal fun testDefinition(name: String = "tool", parallel: Boolean = false, risk: Risk = Risk.READ) =
    FrozenDefinition("test/$name", name, name, "description", "{\"type\":\"object\"}", risk,
        Source.FIRST_PARTY, "test", "1", 1, parallel, null, false, null)

internal class ScriptProvider(
    private val scripts: List<List<ModelEvent>>,
    var mode: ModelExecutionMode = ModelExecutionMode.GENERATION_ONLY,
    var replay: Boolean = true,
) : ModelProvider {
    val requests = mutableListOf<ChatRequest>()
    override fun executionMode(request: ChatRequest) = mode
    override fun canReplay(data: ReplayData, targetModel: String) = replay
    override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> {
        validateHistory(request.messages)
        requests += request
        return scripts[minOf(requests.lastIndex, scripts.lastIndex)].asFlow()
    }
}
internal fun answer(text: String = "done", reason: FinishReason = FinishReason.STOP) = listOf(
    Events.started("r", "m"), Events.text(0, text), Events.end(0), Events.finished(reason))
internal fun calls(vararg names: String, reason: FinishReason = FinishReason.TOOL_CALLS, args: String = "{}") = buildList {
    add(Events.started("r", "m"))
    names.forEachIndexed { i, name ->
        add(Events.toolStart(i, "wire-$i", name)); add(Events.toolDelta(i, args)); add(Events.end(i))
    }
    add(Events.finished(reason))
}
internal fun failure(code: ModelErrorCode, param: String? = null, retryAfter: Long? = null) =
    listOf(Events.failed(modelError(code, "test", retryAfterMs = retryAfter, param = param)))

internal class RunFixture(val definitions: List<FrozenDefinition> = emptyList(), val unattended: Boolean = false) {
    val store = TestStore()
    val gate = DefinitionGate()
    val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, args -> Json.parseToJsonElement(args) is JsonObject })
    val history = MemoryRunHistory(listOf(message(Role.USER, textPart("goal"))))
    val journal = MemoryRunJournal()
    val submitted = mutableListOf<ExecutionTicket>()
    val cancelled = mutableListOf<String>()
    val order = mutableListOf<String>()
    var onSubmit: (ExecutionTicket) -> Unit = { ticket ->
        ledger.complete(ticket.callId, ticket.attemptId, Status.SUCCEEDED, ticket.definition.name)
    }
    var onAwait: suspend (String) -> Unit = {}
    var approve: suspend (ApprovalRequest) -> Decision = { Decision.ALLOW_ONCE }
    init {
        ledger.recoverColdStart("epoch")
        definitions.forEach(gate::install)
    }
    val bridge = object : ToolExecutionBridge {
        override fun submit(ticket: ExecutionTicket) {
            submitted += ticket; order += "submit:${ticket.definition.name}"; onSubmit(ticket)
        }
        override suspend fun awaitTerminal(callId: String) { order += "await:$callId"; onAwait(callId) }
        override fun requestCancel(callId: String) { cancelled += callId }
    }
    fun request() = chatRequest("m") {
        maxOutputTokens = 100
        temperature = 0.4
        tools = definitions.map { modelTool(it.modelName, it.description, Json.parseToJsonElement(it.inputSchema) as JsonObject) }
    }
    fun loop(provider: ModelProvider, recovery: RecoveryStrategy = RecoveryStrategy { _, _, _, _ -> null },
             limits: RunLimits = RunLimits(), history: RunHistory = this.history,
             request: ChatRequest = request(), budget: RequestBudget = RequestBudget { null }) = AgentRunLoop(
        RunIdentity("chain", "session", "run", unattended), ModelTarget("provider", provider, Credentials { "fake" }),
        request, history, LedgerToolExecutor(ledger, gate, definitions, bridge, RunApprovals { approve(it) }),
        journal, recovery, budget, limits, EventTimeouts(100, 100))
}
