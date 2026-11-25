package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiChatCodec
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.agent.transport.OkHttpModelTransport
import io.github.bytejuno.thetacore.plugin.HostToolExecutionService
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID

internal const val CHAT_PROVIDER_ID = "chat"

internal data class ChatState(
    val acceptedMessages: Long = 0,
    val loading: Boolean = true,
    val sessions: List<SessionPreview> = emptyList(),
    val moreSessionsAvailable: Boolean = false,
    val sessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val records: Map<String, InvocationRecord> = emptyMap(),
    val recoveries: Map<String, RecoveryRequest> = emptyMap(),
    val older: HistoryCursor? = null,
    val showingOlder: Boolean = false,
    val active: Boolean = false,
    val pending: Boolean = false,
    val draft: String = "",
    val notice: Int? = null,
    val error: Int? = null,
    val journal: List<JournalReply> = emptyList(),
    val olderJournal: Long? = null,
    val checkpoint: CheckpointInfo? = null,
    val reconciliation: RetryReconciliation? = null,
    val result: RunResult? = null,
    val summaryWindowTokens: Long? = null,
    val executionId: String? = null,
    val contextWindowTokens: Long? = null,
    val recovery: ChatRecoveryToken? = null,
)

internal fun sharedTargetFactory(transport: ModelTransport): (OpenAiRunSnapshot) -> ModelTarget =
    { snapshot -> snapshot.target(transport) }

private fun defaultTargetForRun(): (OpenAiRunSnapshot) -> ModelTarget {
    val shared = lazy { OkHttpModelTransport() }
    return { snapshot -> snapshot.target(shared.value) }
}

/** Application-owned, single-session writer. Activity disposal never cancels an admitted run.
 * All persistence and definition gates run on IO. UI receives copies and submits only IDs. */
internal class ChatSessionOwner(
    private val scope: CoroutineScope,
    private val service: suspend () -> HostToolExecutionService,
    private val configuration: () -> OpenAiRunSnapshot,
    private val diagnostic: (Throwable) -> Unit = {},
    private val targetForRun: (OpenAiRunSnapshot) -> ModelTarget = defaultTargetForRun(),
    private val imageAttachments: ImageAttachments? = null,
    private val presence: ChatTaskPresence = ChatTaskPresence.None,
) {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private val gate = Mutex()
    private var history: SqliteRunHistory? = null
    private var tools: HostToolExecutionService? = null
    private var active: Job? = null
    private var activeRunId: String? = null
    private var observedRequest: String? = null
    private var cachedPageSession: String? = null
    private var cachedPage: HistoryPage? = null
    private val previewLock = Any()
    private val preview = StringBuilder()
    private var sessionCursor: String? = null
    fun previewText(): String = synchronized(previewLock) { preview.toString() }

    private fun launchOperation(action: suspend () -> Unit) = scope.launch(Dispatchers.IO) {
        gate.withLock {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error) }
        }
    }
    private fun report(error: Exception) {
        diagnostic(error)
        val label = when (error) {
            is ChatTaskPresenceException -> R.string.chat_background_failed
            is ProviderSettingsException -> when (error.failure) {
                ProviderSettingsException.Failure.NOT_CONFIGURED -> R.string.chat_configure_first
                ProviderSettingsException.Failure.DECRYPT_FAILED -> R.string.chat_credentials_unavailable
                else -> R.string.chat_provider_failed
            }
            else -> R.string.chat_operation_failed
        }
        mutable.update { it.copy(loading = false, error = label) }
    }
    fun open() = launchOperation {
        if (history != null) return@launchOperation
        tools = service()
        val store = requireNotNull(tools!!.sessionStore)
        val sessions = store.sessionPreviews()
        selectInternal(sessions.firstOrNull()?.sessionId, sessions)
    }
    fun newSession() = launchOperation {
        check(active == null)
        selectInternal(null, requireNotNull(tools!!.sessionStore).sessionPreviews())
    }
    fun select(id: String) = launchOperation {
        check(active == null)
        selectInternal(id, requireNotNull(tools!!.sessionStore).sessionPreviews())
    }
    fun moreSessions() = launchOperation {
        val previous = mutable.value.sessions
        if (!mutable.value.moreSessionsAvailable) return@launchOperation
        val next = requireNotNull(tools!!.sessionStore).sessionPreviews(sessionCursor)
        sessionCursor = next.lastOrNull()?.sessionId ?: sessionCursor
        mutable.update { it.copy(sessions = (previous + next).distinctBy { entry -> entry.sessionId },
            moreSessionsAvailable = next.size == 50) }
    }
    suspend fun image(sessionId: String, callId: String): ByteArray? = withContext(Dispatchers.IO) {
        gate.withLock {
            check(sessionId == mutable.value.sessionId)
            val record = requireNotNull(tools).ledger.get(callId)
            check(record?.input?.sessionId == sessionId && record.input.definition?.toolKey == IMAGE_TOOL_KEY)
            imageAttachments?.read(sessionId, callId)
                ?: requireNotNull(tools!!.sessionStore).privateImages.read(sessionId, callId)
        }
    }
    fun deleteCurrentSession() = launchOperation {
        check(active == null && !mutable.value.pending)
        val id = requireNotNull(mutable.value.sessionId)
        val host = requireNotNull(tools)
        val disk = requireNotNull(history)
        if (host.ledger.sessionDeletionBlocked(id) || disk.hasPendingIntent() ||
            disk.retryReconciliation() != null || recoveryToken(disk) != null ||
            disk.checkpointInfo()?.kind?.let { it !in setOf("VIEW", "COMPLETE") } == true) {
            mutable.update { it.copy(error = R.string.chat_delete_recovery_required) }
            return@launchOperation
        }
        history?.close()
        history = null
        cachedPageSession = null
        cachedPage = null
        try { host.ledger.deleteSession(id) }
        catch (error: Exception) {
            history = requireNotNull(host.sessionStore).openHistory(id)
            throw error
        }
        val sessions = requireNotNull(host.sessionStore).sessionPreviews()
        selectInternal(sessions.firstOrNull()?.sessionId, sessions)
    }
    private suspend fun selectInternal(id: String?, sessions: List<SessionPreview>) {
        val host = requireNotNull(tools)
        val store = requireNotNull(host.sessionStore)
        history?.close()
        history = null
        cachedPageSession = null
        cachedPage = null
        val key = id ?: "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val disk = if (id == null) store.createHistory(key) else store.openHistory(key)
        history = disk
        // Preserve fully registered unsubmitted approvals. Never recoverPending over a live run.
        disk.chatTaskChainId() // Keep the saved chain before cold reconciliation consumes its step.
        val pending = disk.pendingStep(host.ledger)
        if (pending == null) disk.recoverPending(host.ledger)
        else {
            restorePending(pending)
            if (pending.records.all { it.state.terminal && it.instance?.state != InstanceState.UNCONFIRMED }) {
                disk.recoverPending(host.ledger)
                host.finishChatRun(pending.identity.runId)
            }
        }
        sessionCursor = sessions.lastOrNull()?.sessionId
        mutable.value = ChatState(loading = false,
            sessions = (listOf(store.sessionPreview(key)) + sessions).distinctBy { it.sessionId },
            moreSessionsAvailable = sessions.size == 50, sessionId = key)
        refresh()
    }
    private fun restorePending(pending: PendingHistoryStep) {
        val host = requireNotNull(tools)
        host.catalog.restorePending(
            pending.identity.runId,
            (pending.records.mapNotNull { it.input.definition } + listOfNotNull(pending.resultReader)).distinct(),
        )
    }
    private suspend fun refresh(before: HistoryCursor? = null) {
        val disk = requireNotNull(history)
        val host = requireNotNull(tools)
        val page = if (before == null && active != null && cachedPageSession == disk.sessionId &&
            cachedPage?.revision == disk.revision()) {
            cachedPage!!
        } else {
            disk.page(before, 40).also { loaded ->
                if (before == null) {
                    cachedPageSession = disk.sessionId
                    cachedPage = loaded
                }
            }
        }
        val pending = disk.pendingStep(host.ledger)
        val messages = page.messages + if (before == null && pending != null) listOf(pending.assistant) else emptyList()
        val records = messages.flatMap { it.parts.filterIsInstance<ContentPart.ToolCall>() }
            .mapNotNull { host.ledger.get(it.binding.callId) }.associateBy { it.callId }
        val journal = disk.journalPage(includeUnfinished = active == null)
        val checkpoint = if (active == null) disk.checkpointInfo() else null
        val reconciliation = if (active == null) disk.retryReconciliation() else null
        mutable.update { it.copy(messages = messages, records = records,
            recoveries = host.ledger.pendingRecoveries().filter { it.callId in records }.associateBy { it.callId }, older = page.older,
            showingOlder = before != null, pending = pending != null,
            draft = previewText(), journal = journal.replies, olderJournal = journal.older,
            checkpoint = checkpoint?.takeUnless { it.kind in setOf("VIEW", "COMPLETE") },
            reconciliation = reconciliation, result = disk.chatResult(),
            summaryWindowTokens = disk.chatSummaryWindow(), contextWindowTokens = disk.chatContextWindow(),
            recovery = if (active == null) recoveryToken(disk, checkpoint, reconciliation) else null) }
        if (active != null) mutable.value.executionId?.let { executionId ->
            presence.update(executionId, taskProgress(records.values.filter { it.input.runId == activeRunId }))
        }
    }
    private fun recoveryToken(disk: SqliteRunHistory, checkpoint: CheckpointInfo? = disk.checkpointInfo(),
        reconciliation: RetryReconciliation? = disk.retryReconciliation()): ChatRecoveryToken? {
        if (disk.hasPendingIntent() || disk.chatResult()?.state == RunState.COMPLETED) return null
        val identity = disk.chatRecoveryIdentity() ?: return null
        val context = disk.chatContext() ?: return null
        return ChatRecoveryToken(identity, disk.revision(), checkpoint, reconciliation, context)
    }
    fun olderReplies() = launchOperation {
        check(active == null)
        val before = mutable.value.olderJournal ?: return@launchOperation
        val page = requireNotNull(history).journalPage(before)
        mutable.update { it.copy(journal = page.replies + it.journal, olderJournal = page.older) }
    }
    fun older() = launchOperation { check(active == null && !mutable.value.pending); mutable.value.older?.let { refresh(it) } }
    fun latest() = launchOperation { refresh() }

    private fun observeLateExits(host: HostToolExecutionService, runId: String) {
        mutable.value.records.values.filter {
            it.input.runId == runId && it.instance?.state == InstanceState.UNCONFIRMED
        }.forEach { record ->
            scope.launch(Dispatchers.IO) {
                try {
                    host.awaitObservedExit(record.callId)
                    gate.withLock {
                        if (record.callId !in mutable.value.records) return@withLock
                        val current = requireNotNull(host.ledger.get(record.callId))
                        val recovery = host.ledger.pendingRecoveries().find { it.callId == record.callId }
                        mutable.update { state -> state.copy(
                            records = state.records + (record.callId to current),
                            recoveries = if (recovery == null) state.recoveries - record.callId
                                else state.recoveries + (record.callId to recovery)) }
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { report(error) }
            }
        }
    }

    fun continueSavedStep() = launchOperation {
        check(active == null)
        val pending = requireNotNull(history!!.pendingStep(tools!!.ledger))
        require(!pending.identity.unattended)
        start(null, pending)
    }
    fun send(text: String) = launchOperation {
        check(active == null && text.isNotBlank() && text.length <= 32_000)
        val disk = requireNotNull(history)
        check(disk.pendingStep(tools!!.ledger) == null)
        val previousIdentity = disk.chatRecoveryIdentity()
        check(previousIdentity?.unattended != true)
        if (disk.retryReconciliation() != null || disk.checkpointInfo()?.kind in setOf("PAUSED", "CONTINUATION"))
            check(previousIdentity != null)
        val snapshot = configuration() // validate/decrypt before acknowledging a new message
        val chain = disk.chatTaskChainId() // Capture checkpoint identity before the history revision advances.
        if (disk.chatContext() == null) disk.saveChatContext(ChatTaskContext(chain, text,
            Math.toIntExact(disk.page(null, 1).totalMessages), disk.chatSummaryWindow(), disk.chatContextWindow()))
        val identity = RunIdentity(chain, disk.sessionId, UUID.randomUUID().toString())
        disk.appendChatInput(disk.revision(), message(Role.USER, textPart(text)), identity)
        val entry = requireNotNull(tools!!.sessionStore).sessionPreview(disk.sessionId)
        mutable.update { it.copy(acceptedMessages = it.acceptedMessages + 1,
            sessions = it.sessions.map { saved -> if (saved.sessionId == entry.sessionId) entry else saved }) }
        start(snapshot, null, identity)
    }
    fun resume(summaryWindowTokens: Long? = null, reconcileId: String? = null) = launchOperation {
        resumeInternal(summaryWindowTokens, reconcileId)
    }
    fun recover(expected: ChatRecoveryToken, action: ChatRecoveryAction, window: Long? = null,
        confirmed: Boolean = false) = launchOperation {
        val disk = requireNotNull(history)
        check(active == null && !disk.hasPendingIntent())
        check(recoveryToken(disk) == expected && !expected.identity.unattended)
        check(action in recoveryActions(mutable.value))
        when (action) {
            ChatRecoveryAction.RETRY -> {
                check(confirmed)
                resumeInternal(reconcileId = requireNotNull(expected.reconciliation).id)
            }
            ChatRecoveryAction.READMIT, ChatRecoveryAction.SUMMARIZE -> {
                check(confirmed && validRecoveryWindow(action, window, expected.context.contextWindowTokens))
                resumeInternal(summaryWindowTokens = window, summarize = action == ChatRecoveryAction.SUMMARIZE)
            }
            ChatRecoveryAction.CONTINUE_SUMMARY -> { check(confirmed); resumeInternal() }
            ChatRecoveryAction.CONTINUE -> resumeInternal()
        }
    }
    private fun resumeInternal(summaryWindowTokens: Long? = null, reconcileId: String? = null,
        summarize: Boolean = true) {
        check(active == null)
        val disk = requireNotNull(history)
        check(!disk.hasPendingIntent())
        val snapshot = configuration()
        val context = requireNotNull(disk.chatContext())
        val previous = requireNotNull(disk.chatRecoveryIdentity())
        check(!previous.unattended && previous.taskChainId == context.taskChainId)
        val checkpoint = disk.checkpointInfo()
        val identity = checkpoint?.takeIf { it.canContinue }?.nextIdentity
            ?: previous.copy(runId = UUID.randomUUID().toString())
        check(!identity.unattended && identity.sessionId == previous.sessionId && identity.taskChainId == previous.taskChainId)
        if (reconcileId != null) check(disk.confirmRetryAfterReconciliation(reconcileId, identity, "User reviewed uncertain request and explicitly accepted retry"))
        check(disk.retryReconciliation() == null)
        if (summaryWindowTokens != null) {
            require(summaryWindowTokens in 1024..1_000_000)
            disk.saveChatContext(context.copy(contextWindowTokens = summaryWindowTokens,
                summaryWindowTokens = summaryWindowTokens.takeIf { summarize }))
        }
        val resumeId = checkpoint?.takeIf { it.canContinue }?.id
        val readmitId = checkpoint?.takeIf { it.kind == "PAUSED" && it.pauseClass == "BUDGET" }?.id
        start(snapshot, null, identity, resumeId, readmitId)
    }
    fun decide(callId: String, requestId: String, decision: Decision) = launchOperation {
        require(decision == Decision.ALLOW_ONCE || decision == Decision.DENY)
        val host = requireNotNull(tools)
        val r = requireNotNull(host.ledger.get(callId))
        require(r.input.sessionId == history!!.sessionId && !r.input.unattended)
        val coldToken = if (active == null && r.pending?.id == requestId) UUID.randomUUID().toString() else null
        try {
            if (r.pending?.id == requestId) {
                presence.update(requireNotNull(coldToken ?: mutable.value.executionId), ChatTaskProgress.Model)
            }
            when (host.decide(callId, requestId, decision)) {
                is Mutation.Applied, is Mutation.AlreadyApplied -> Unit
                else -> error("Approval changed or unavailable")
            }
            refresh()
            if (active == null) {
                val pending = history!!.pendingStep(host.ledger)
                if (pending != null) start(null, pending, executionId = coldToken ?: UUID.randomUUID().toString())
                else coldToken?.let { presence.finish(it, null) }
            }
        } catch (error: Exception) {
            if (coldToken != null) withContext(NonCancellable) { presence.finish(coldToken, ChatTaskProgress.Approval) }
            throw error
        }
    }
    fun recover(callId: String, requestId: String, resolution: RecoveryResolution) = launchOperation {
        val host = requireNotNull(tools)
        val r = requireNotNull(host.ledger.get(callId))
        require(r.input.sessionId == history!!.sessionId && !r.input.unattended)
        when (host.decideRecovery(callId, requestId, resolution)) {
            is Mutation.Applied, is Mutation.AlreadyApplied -> Unit
            else -> error("Recovery changed or unavailable")
        }
        refresh()
    }
    fun stop(expectedExecutionId: String? = null) = launchOperation {
        if (expectedExecutionId != null && expectedExecutionId != mutable.value.executionId) return@launchOperation
        active?.cancel()
        if (active == null) {
            val pending = history?.pendingStep(tools!!.ledger)
            if (pending != null) {
                stopPendingStep(pending)
                refresh()
                observeLateExits(requireNotNull(tools), pending.identity.runId)
                mutable.update { it.copy(notice = R.string.chat_stopped) }
            }
        }
    }
    private suspend fun stopPendingStep(pending: PendingHistoryStep) {
        val host = requireNotNull(tools)
        val disk = requireNotNull(history)
        pending.records.forEach { record ->
            when (host.cancel(record.callId)) {
                is Mutation.Applied, is Mutation.AlreadyApplied -> Unit
                else -> error("Could not cancel saved invocation")
            }
        }
        val ids = pending.assistant.parts.filterIsInstance<ContentPart.ToolCall>().map { it.binding.callId }
        val results = requireNotNull(host.ledger.resultsForStep(disk.sessionId,
            pending.identity.runId, pending.stepId, ids)) { "Incomplete saved step" }
        // Commit the pair from logical ledger results. Actual exit, resource leases and
        // isolation remain owned by the host observer; cold recovery is not a live stop.
        disk.append(disk.revision(), listOf(pending.assistant, results))
        disk.saveChatInterruption()
        host.finishChatRun(pending.identity.runId)
    }
    private suspend fun approval(request: ApprovalRequest): Decision? {
        val host = requireNotNull(tools)
        while (true) {
            currentCoroutineContext().ensureActive()
            check(!host.submissionsClosed) { "Host tool storage unavailable" }
            val r = requireNotNull(host.ledger.get(request.callId))
            r.receipts.firstOrNull { it.request.id == request.id }?.let { return it.decision }
            if (r.pending?.id != request.id) return null
            delay(50)
        }
    }
    private fun executor(definitions: List<FrozenDefinition>): LedgerToolExecutor {
        val host = requireNotNull(tools)
        val executionId = requireNotNull(mutable.value.executionId)
        val bridge = object : ToolExecutionBridge by host.runBridge(beforeResume = { callId ->
            gate.withLock {
                check(active?.isActive == true && mutable.value.executionId == executionId)
                val record = requireNotNull(host.ledger.get(callId))
                check(record.input.runId == activeRunId && record.input.sessionId == history!!.sessionId)
                try { presence.update(executionId, ChatTaskProgress.Tool(record.input.definition?.modelName.orEmpty())) }
                catch (error: Exception) {
                    report(error)
                    active?.cancel(CancellationException("Tool foreground admission failed", error))
                    throw error
                }
            }
        }) {
            override suspend fun beforeSubmit(callId: String) = gate.withLock {
                currentCoroutineContext().ensureActive()
                val record = requireNotNull(host.ledger.get(callId))
                check(record.input.runId == activeRunId && record.input.sessionId == history!!.sessionId)
                presence.update(requireNotNull(mutable.value.executionId), ChatTaskProgress.Model)
            }
        }
        return LedgerToolExecutor(host.ledger, host.catalog.gate, definitions, bridge, RunApprovals(::approval))
    }

    private suspend fun withStatusUpdates(action: suspend () -> Int): Int = coroutineScope {
        val ticker = launch {
            while (isActive) {
                gate.withLock { refresh() }
                delay(100)
            }
        }
        try { action() } finally { ticker.cancel() }
    }

    private fun start(snapshot: OpenAiRunSnapshot?, pending: PendingHistoryStep?, identity: RunIdentity? = null,
        resumeId: String? = null, readmitId: String? = null, executionId: String = UUID.randomUUID().toString()) {
        check(active == null)
        activeRunId = requireNotNull(identity ?: pending?.identity).runId
        mutable.update { it.copy(active = true, error = null, notice = null, draft = "", executionId = executionId) }
        active = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var notice = R.string.chat_complete
            try {
                presence.update(executionId, ChatTaskProgress.Model)
                notice = withStatusUpdates {
                    val result = executeChat(snapshot, pending, identity, resumeId, readmitId)
                    // Admission can pause after submitting a parallel sibling. Keep the
                    // execution identity, approvals and foreground owner until that sibling
                    // settles; only an explicit continuation may submit the remaining calls.
                    val host = requireNotNull(tools)
                    val saved = history!!.pendingStep(host.ledger)
                    try {
                        saved?.records?.filter { it.state == Status.RUNNING ||
                            it.state in setOf(Status.SUCCEEDED, Status.FAILED) &&
                                it.instance?.state == InstanceState.UNCONFIRMED
                        }?.forEach { host.runBridge().awaitTerminal(it.callId) }
                    } catch (error: Exception) {
                        withContext(NonCancellable) {
                            gate.withLock {
                                history!!.pendingStep(host.ledger)?.let { stopPendingStep(it) }
                            }
                        }
                        throw error
                    }
                    result
                }
            } catch (error: CancellationException) {
                notice = R.string.chat_stopped
                withContext(NonCancellable) {
                    gate.withLock {
                        try {
                            // Stop can arrive before executeChat, while foreground admission
                            // is pending. Reconcile the saved step at this boundary too.
                            val saved = history?.pendingStep(requireNotNull(tools).ledger)
                            if (saved != null) stopPendingStep(saved)
                            else history?.saveChatInterruption()
                        } catch (error: Exception) { report(error) }
                    }
                }
            }
            catch (error: Exception) {
                report(error)
                notice = R.string.chat_paused
                if (error is ChatTaskPresenceException) {
                    try { history?.saveChatInterruption() }
                    catch (storageError: Exception) { report(storageError) }
                }
            }
            finally {
                withContext(NonCancellable) {
                    gate.withLock {
                        active = null
                        synchronized(previewLock) { preview.setLength(0); observedRequest = null }
                        try {
                            val host = requireNotNull(tools)
                            val runId = requireNotNull(identity ?: pending?.identity).runId
                            if (!requireNotNull(history).hasPendingIntent()) host.finishChatRun(runId)
                            refresh()
                            observeLateExits(host, runId)
                        } catch (error: Exception) { report(error) }
                        val waiting = taskProgress(mutable.value.records.values.filter { it.input.runId == activeRunId })
                            .takeIf { it == ChatTaskProgress.Approval || it == ChatTaskProgress.Recovery }
                        try { presence.finish(executionId, waiting) }
                        catch (error: Exception) { report(error) }
                        activeRunId = null
                        mutable.update { it.copy(active = false, draft = "", notice = notice, executionId = null) }
                    }
                }
            }
        }.also { it.start() }
    }

    private suspend fun executeChat(snapshot: OpenAiRunSnapshot?, pending: PendingHistoryStep?, identity: RunIdentity?,
        resumeId: String?, readmitId: String?): Int {
        val disk = requireNotNull(history)
        if (pending != null) {
            val calls = pending.assistant.parts.filterIsInstance<ContentPart.ToolCall>().map { part ->
                object : CollectedCall {
                    override val binding = part.binding
                    override val name = part.name
                    override val argsJson = part.argsJson
                    override val complete = true
                    override val emptyArguments = argsJson.isBlank() || argsJson == "null"
                }
            }
            val executor = executor(pending.records.mapNotNull { it.input.definition }.distinctBy { it.modelName })
            var admissionPaused = false
            try { executor.execute(calls) }
            catch (pause: ToolStepPaused) {
                if (!currentCoroutineContext().isActive) executor.cancelRemaining(calls)
                currentCoroutineContext().ensureActive()
                admissionPaused = true
            }
            catch (error: Exception) { executor.cancelRemaining(calls); throw error }
            finally {
                // Same atomic pair commit as AgentRunLoop: logical cancellation is not
                // actual worker exit. Preserve the ledger's UNKNOWN/isolation truth.
                if (!admissionPaused) disk.append(disk.revision(), listOf(pending.assistant,
                    executor.results(pending.identity, pending.stepId, calls)))
            }
            return if (admissionPaused) R.string.chat_paused else R.string.chat_recovered_step
        } else {
            val config = requireNotNull(snapshot)
            val id = requireNotNull(identity)
            val context = requireNotNull(disk.chatContext())
            val window = context.contextWindowTokens
            val definitions = (tools!!.catalog.frozen(id.runId) ?: tools!!.catalog.freeze(id.runId)).values.toList()
            val configured = config.request { tools = definitions.map {
                modelTool(it.modelName, it.description, Json.parseToJsonElement(it.inputSchema).jsonObject)
            } }
            val request = if (window != null && configured.maxOutputTokens == null) object : ChatRequest by configured {
                override val maxOutputTokens: Int = minOf(2048, (window / 4).toInt())
            } else configured
            val target = targetForRun(config)
            val codec = OpenAiChatCodec(config.settings.adapterConfig())
            val requestBudget = ContextBudget({ candidate ->
                try {
                    256L + codec.encode(candidate).toByteArray(Charsets.UTF_8).size
                } catch (_: IllegalArgumentException) {
                    null
                }
            }, { window })
            val summary = context.summaryWindowTokens?.let { window ->
                SummaryModel(target, chatRequest(config.settings.model) {
                    toolChoice = ToolChoice.None
                    maxOutputTokens = minOf(2048, (window / 4).toInt())
                }, requestBudget)
            }
            val result = AgentRunLoop(id, target, request, disk, executor(definitions),
                journal = StoredRunJournal(disk),
                budget = requestBudget,
                contextPolicy = ContextPolicy(context.goal, context.priorMessageCount, summary, resumeId, readmitId),
                beforeModelSubmit = { gate.withLock {
                    currentCoroutineContext().ensureActive()
                    presence.update(requireNotNull(mutable.value.executionId), ChatTaskProgress.Model)
                } },
                onEvent = { requestId, event -> synchronized(previewLock) {
                    if (requestId != observedRequest) { observedRequest = requestId; preview.setLength(0) }
                    if (event is ModelEvent.TextDelta) {
                        preview.append(event.text)
                        if (preview.length > 64_000) preview.delete(0, preview.length - 64_000)
                    }
                    if (event is ModelEvent.Finished || event is ModelEvent.Failed) preview.setLength(0)
                } }).runChatTask()
            disk.saveChatResult(result)
            return when (result.state) {
                RunState.COMPLETED -> R.string.chat_complete
                RunState.PAUSED -> R.string.chat_paused
                RunState.FAILED -> R.string.chat_model_failed
            }
        }
    }
}
