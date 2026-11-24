package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ChatScreenStateTest {
    @get:Rule val temp = TemporaryFolder()
    private val stores = mutableListOf<AndroidAgentStore>()

    @After fun closeStores() { stores.forEach { it.close() } }

    private val imageDefinition = FrozenDefinition(
        toolKey = IMAGE_TOOL_KEY,
        modelName = "generate_image",
        name = "generate_image",
        description = "Generate image",
        inputSchema = """{"type":"object"}""",
        declaredRisk = Risk.WRITE,
        source = Source.FIRST_PARTY,
        owner = "host",
        version = "1",
        generation = 1L,
        parallelSafe = false,
        exclusiveResource = null,
        idempotent = false,
        outputSchema = null,
    )

    private fun newLedger(): InvocationLedger {
        val store = AndroidAgentStore.open(temp.newFolder(), BundledSQLiteDriver()).also { stores += it }
        val gate = DefinitionGate()
        gate.install(imageDefinition)
        gate.freezeRun("run-1", listOf(imageDefinition))
        val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
        assertTrue(ledger.recoverColdStart("epoch-1") !is Mutation.StorageFailure)
        return ledger
    }

    private fun input(
        stepId: String,
        callOrder: Int = 0,
        cancelledBeforeExecution: Boolean = false,
    ): InvocationInput {
        val callId = UUID.randomUUID().toString()
        return InvocationInput(
            binding = BindingValue("openai", "req-$stepId", null, callOrder, "wire-$stepId", callId),
            taskChainId = "chain-$stepId",
            sessionId = "session-1",
            runId = "run-1",
            stepId = stepId,
            callOrder = callOrder,
            argsJson = """{"prompt":"orange cat"}""",
            definition = imageDefinition,
            unattended = false,
            initialRisk = Risk.WRITE,
            cancelledBeforeExecution = cancelledBeforeExecution,
            nonExecutionReason = if (cancelledBeforeExecution) NonExecutionReason.MODEL_PAUSED else null,
        )
    }

    @Test
    fun rejectedImageApprovalShowsNotSentInsteadOfBilledFailure() {
        val ledger = newLedger()
        val recorded = (ledger.recordInvocation(input("step-deny")) as Mutation.Applied).record
        val awaiting = (ledger.validateAndPrepare(recorded.callId) as Mutation.Applied).record
        assertEquals(Status.WAITING_APPROVAL, awaiting.state)
        assertNull(imageResultLabel(awaiting))
        assertFalse(shouldLoadChatImage(awaiting))

        val denied = (ledger.decideApproval(awaiting.callId, awaiting.pending!!.id, Decision.DENY) as Mutation.Applied).record
        assertEquals(Status.REJECTED, denied.state)
        assertNull(denied.instance)
        assertEquals(Effect.NONE, denied.result?.effect)
        assertEquals(R.string.chat_image_not_sent, imageResultLabel(denied))
        assertFalse(shouldLoadChatImage(denied))
    }

    @Test
    fun preExecutionCancelledAndApprovalTimeoutImageCallsShowNotSent() {
        val ledger = newLedger()
        val paused = (ledger.recordInvocation(input("step-paused", cancelledBeforeExecution = true)) as Mutation.Applied).record
        assertEquals(Status.CANCELLED, paused.state)
        assertNull(paused.instance)
        assertEquals(R.string.chat_image_not_sent, imageResultLabel(paused))
        assertFalse(shouldLoadChatImage(paused))

        val awaiting = (ledger.validateAndPrepare((ledger.recordInvocation(input("step-cancel")) as Mutation.Applied).record.callId) as Mutation.Applied).record
        val cancelled = (ledger.cancel(awaiting.callId) as Mutation.Applied).record
        assertEquals(Status.CANCELLED, cancelled.state)
        assertNull(cancelled.instance)
        assertEquals(R.string.chat_image_not_sent, imageResultLabel(cancelled))
        assertFalse(shouldLoadChatImage(cancelled))

        val awaitingTimeout = (ledger.validateAndPrepare((ledger.recordInvocation(input("step-timeout")) as Mutation.Applied).record.callId) as Mutation.Applied).record
        val timedOut = (ledger.timeout(awaitingTimeout.callId) as Mutation.Applied).record
        assertEquals(Status.TIMED_OUT, timedOut.state)
        assertNull(timedOut.instance)
        assertEquals(R.string.chat_image_not_sent, imageResultLabel(timedOut))
        assertFalse(shouldLoadChatImage(timedOut))
    }

    @Test
    fun claimedImageCallsMapCompleteConfigureAttemptedAndBilledFailure() {
        fun runToTerminal(stepId: String, status: Status, effect: Effect, text: String): InvocationRecord {
            val ledger = newLedger()
            val recorded = (ledger.recordInvocation(input(stepId)) as Mutation.Applied).record
            val awaiting = (ledger.validateAndPrepare(recorded.callId) as Mutation.Applied).record
            val ready = (ledger.decideApproval(awaiting.callId, awaiting.pending!!.id, Decision.ALLOW_ONCE) as Mutation.Applied).record
            val running = (ledger.claimExecution(ready.callId, ready.revision) as Mutation.Applied).record
            val attemptId = running.instance!!.attemptId
            return if (status == Status.CANCELLED) {
                (ledger.cancel(running.callId) as Mutation.Applied).record
            } else {
                (ledger.recordOutcome(running.callId, attemptId, status, text, effect) as Mutation.Applied).record
            }
        }

        val completed = runToTerminal("s-ok", Status.SUCCEEDED, Effect.APPLIED, IMAGE_COMPLETE)
        assertEquals(R.string.chat_image_complete, imageResultLabel(completed))
        assertTrue(shouldLoadChatImage(completed))

        val configure = runToTerminal("s-cfg", Status.FAILED, Effect.NONE, IMAGE_CONFIGURE)
        assertEquals(R.string.chat_image_configure, imageResultLabel(configure))
        assertTrue(shouldLoadChatImage(configure))

        val attempted = runToTerminal("s-att", Status.FAILED, Effect.UNKNOWN, IMAGE_ATTEMPTED)
        assertEquals(R.string.chat_image_already_attempted, imageResultLabel(attempted))
        assertTrue(shouldLoadChatImage(attempted))

        val failed = runToTerminal("s-fail", Status.FAILED, Effect.UNKNOWN, IMAGE_FAILED)
        assertEquals(R.string.chat_image_failed, imageResultLabel(failed))
        assertTrue(shouldLoadChatImage(failed))

        val postClaimCancelled = runToTerminal("s-stop", Status.CANCELLED, Effect.UNKNOWN, "Stopped while running")
        assertEquals(R.string.chat_image_failed, imageResultLabel(postClaimCancelled))
        assertTrue(shouldLoadChatImage(postClaimCancelled))
    }

    @Test
    fun draftSurvivesRotationAndReentryButClearsWhenNewMessageIsAccepted() {
        assertEquals("" to 0L, reconcileDraftOnAccepted("", acceptedMessages = 0L, clearedAtAccepted = 0L))
        assertEquals("" to 1L, reconcileDraftOnAccepted("hello", acceptedMessages = 1L, clearedAtAccepted = 0L))
        assertEquals("unsent draft" to 1L, reconcileDraftOnAccepted("unsent draft", acceptedMessages = 1L, clearedAtAccepted = 1L))
        assertEquals("" to 2L, reconcileDraftOnAccepted("unsent draft", acceptedMessages = 2L, clearedAtAccepted = 1L))

        val (restoredDraft, rearmedWatermark) = reconcileDraftOnAccepted("restored draft", acceptedMessages = 0L, clearedAtAccepted = 2L)
        assertEquals("restored draft", restoredDraft)
        assertEquals(0L, rearmedWatermark)
        assertEquals("" to 1L, reconcileDraftOnAccepted(restoredDraft, acceptedMessages = 1L, clearedAtAccepted = rearmedWatermark))
    }
}
