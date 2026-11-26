package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.SaveableStateRegistry
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

    private val saverScope = SaverScope { true }

    private val session = "shared"
    private fun image(id: String) = PendingUserImage(id, "image/png", id.toByteArray())

    @Test fun activityCanRestoreLatestDraftWhenSavedOnPluginPage() {
        val files = draftsFor(listOf(session))
        val saver = ChatComposerDrafts.saver(files)
        val drafts = ChatComposerDrafts(files)
        val activity = SaveableStateRegistry(null) { true }
        activity.registerProvider("drafts") { with(saver) { saverScope.save(drafts) } }
        drafts[session].edit("before")
        activity.performSave()
        drafts[session].edit("after")
        drafts[session].images = listOf(image("pending"))
        // ChatScreen has left composition; the Activity still owns the draft provider.
        val restoredActivity = SaveableStateRegistry(activity.performSave()) { true }
        val restored = requireNotNull(saver.restore(requireNotNull(restoredActivity.consumeRestored("drafts"))))[session]
        assertEquals("after", restored.input)
        assertEquals("pending", restored.images.single().localId)
        assertArrayEquals("pending".toByteArray(), restored.images.single().bytes)
    }

    @Test fun attachmentOnlyPagePreservesAnotherPagesTextAfterDatabaseReopen() {
        val root = temp.newFolder()
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            store.createHistory(session).close()
            val writer = ChatComposerDrafts { store.composerDrafts }
            writer[session].edit("old")
            writer.checkpoint()
            val stale = ChatComposerDrafts { store.composerDrafts }
            val old = stale[session]
            writer[session].edit("new text")
            writer.checkpoint()
            old.images = listOf(image("new-picture"))
            stale.checkpoint()
        }
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            val saved = store.composerDrafts.read(session)
            assertEquals("new text", saved.text)
            assertEquals("new-picture", saved.images.single().id)
        }
    }

    @Test fun twoPagesPersistOnlyFourOrderedImagesAcrossDatabaseReopen() {
        val root = temp.newFolder()
        val latest = listOf("z", "a", "c", "b")
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            store.createHistory(session).close()
            val first = ChatComposerDrafts { store.composerDrafts }
            val second = ChatComposerDrafts { store.composerDrafts }
            first[session].images = listOf("e", "f", "g", "h").map(::image)
            second[session].images = latest.map(::image)
            first.checkpoint()
            second.checkpoint()
        }
        BundledSQLiteDriver().open(root.resolve(AgentDatabase.FILE_NAME).path).use { db ->
            db.prepare("SELECT count(*) FROM composer_chunks").use { rows ->
                assertTrue(rows.step())
                assertEquals(4L, rows.getLong(0))
            }
        }
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            val images = store.composerDrafts.read(session).images
            assertEquals(latest, images.map { it.id })
            images.forEach { assertArrayEquals(it.id.toByteArray(), it.bytes) }
        }
    }

    @Test fun reopeningAnOlderOversizeDraftPrunesExcessRowsAndChunks() {
        val root = temp.newFolder()
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { it.createHistory(session).close() }
        val path = root.resolve(AgentDatabase.FILE_NAME).path
        BundledSQLiteDriver().open(path).use { db ->
            for (i in 0 until 8) {
                db.prepare("INSERT INTO composer_parts VALUES('shared','image','i$i',$i,'image/png')").use { it.step() }
                db.prepare("INSERT INTO composer_chunks VALUES('shared','image','i$i',0,X'01')").use { it.step() }
            }
        }
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            assertEquals(listOf("i0", "i1", "i2", "i3"), store.composerDrafts.read(session).images.map { it.id })
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("SELECT (SELECT count(*) FROM composer_parts), (SELECT count(*) FROM composer_chunks)").use {
                assertTrue(it.step())
                assertEquals(4L, it.getLong(0))
                assertEquals(4L, it.getLong(1))
            }
        }
    }

    private fun draftsFor(ids: List<String>): () -> SessionComposerStore {
        val store = AndroidAgentStore.open(temp.newFolder(), BundledSQLiteDriver()).also { stores += it }
        ids.forEach { store.createHistory(it).close() }
        return { store.composerDrafts }
    }

    @Test fun pageCheckpointStaysSmallAndRestoresEveryFullLengthSessionDraft() {
        val files = draftsFor((0 until 40).map { "session-$it" })
        val saver = ChatComposerDrafts.saver(files)
        val book = ChatComposerDrafts(files)
        val versions = (0 until 40).map { index ->
            book["session-$index"].apply { edit(index.toString().padEnd(32_000, '\u754c')) }.textVersion
        }
        val checkpoint = with(saver) { requireNotNull(saverScope.save(book)) }
        assertEquals(1, checkpoint)
        val restored = requireNotNull(saver.restore(checkpoint))
        versions.forEachIndexed { index, version ->
            assertEquals(index.toString().padEnd(32_000, '\u754c'), restored["session-$index"].input)
            assertEquals(version, restored["session-$index"].textVersion)
        }
    }

    @Test fun restoredAttachmentRetainsSendBytesAndReceiptIdentity() {
        val files = draftsFor(listOf("original", "second"))
        val saver = ChatComposerDrafts.saver(files)
        val book = ChatComposerDrafts(files)
        val bytes = ByteArray(128 * 1024) { it.toByte() }
        val pending = PendingUserImage(mimeType = "image/png", bytes = bytes)
        val original = book["original"]
        original.images = listOf(pending)
        book["second"].edit("keep")
        val checkpoint = with(saver) { requireNotNull(saverScope.save(book)) }
        val restored = requireNotNull(saver.restore(checkpoint))
        val draft = restored["original"]
        val attachment = draft.images.single()
        assertArrayEquals(bytes, attachment.bytes)
        assertEquals(pending.mimeType, attachment.mimeType)
        assertEquals(pending.localId, attachment.localId)
        assertEquals(original.textVersion, draft.textVersion)
        draft.acknowledge(ChatDraftReceipt("original", original.textVersion, setOf(pending.localId)))
        val next = with(saver) { requireNotNull(saver.restore(saverScope.save(restored)!!)) }
        assertTrue(next["original"].images.isEmpty())
        assertEquals("keep", next["second"].input)
    }

    @Test fun olderAdmissionConsumesOnlySubmittedPartsAfterAnEditAndImageRemoval() {
        val store = AndroidAgentStore.open(temp.newFolder(), BundledSQLiteDriver()).also { stores += it }
        val id = "edited"
        store.createHistory(id).use { history ->
            val book = ChatComposerDrafts { store.composerDrafts }
            val draft = book[id]
            fun image(key: String) = PendingUserImage(key, "image/png", key.toByteArray())
            val removed = image("remove")
            val sent = image("sent")
            val kept = image("z-kept")
            val added = image("a-added")
            draft.edit("submitted")
            draft.images = listOf(removed, sent, kept)
            book.checkpoint()
            val receipt = ChatDraftReceipt(id, draft.textVersion, setOf(sent.localId))
            draft.edit("new edit")
            draft.images = listOf(sent, kept, added)
            book.checkpoint()
            history.appendUserInput("submitted", emptyList(), receipt)
            val recovered = ChatComposerDrafts { store.composerDrafts }[id]
            assertEquals("new edit", recovered.input)
            assertEquals(draft.textVersion, recovered.textVersion)
            assertEquals(listOf(kept.localId, added.localId), recovered.images.map { it.localId })
            assertArrayEquals(added.bytes, recovered.images.last().bytes)
        }
    }

    @Test fun sendReceiptPreservesChangesCheckpointedAfterClick() {
        val store = AndroidAgentStore.open(temp.newFolder(), BundledSQLiteDriver()).also { stores += it }
        val id = "later-edit"
        store.createHistory(id).use { history ->
            val book = ChatComposerDrafts { store.composerDrafts }
            val draft = book[id]
            draft.edit("old")
            draft.images = listOf(PendingUserImage("removed", "image/png", byteArrayOf(1)))
            book.checkpoint()
            draft.edit("submitted")
            draft.images = emptyList()
            val receipt = book.receipt(id)
            draft.edit("keep")
            val added = PendingUserImage("added", "image/png", byteArrayOf(2))
            draft.images = listOf(added)
            book.checkpoint()
            history.appendUserInput("submitted", emptyList(), receipt)
            draft.acknowledge(receipt)
            val restored = ChatComposerDrafts { store.composerDrafts }[id]
            assertEquals("keep", draft.input)
            assertEquals("keep", restored.input)
            assertEquals(draft.textVersion, restored.textVersion)
            assertEquals(added.localId, restored.images.single().localId)
            assertArrayEquals(added.bytes, restored.images.single().bytes)
        }
    }

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
    fun draftReceiptOnlyClearsSubmittedTextVersionAndAttachmentIds() {
        val sent = PendingUserImage(mimeType = "image/png", bytes = byteArrayOf(1))
        val added = PendingUserImage(mimeType = "image/png", bytes = byteArrayOf(1))
        val receipt = ChatDraftReceipt("session", "submitted version", setOf(sent.localId))
        assertTrue(receipt.clearsText("submitted version"))
        assertFalse(receipt.clearsText("edited version"))
        assertEquals(listOf(added), receipt.remaining(listOf(sent, added)))
        assertEquals(listOf(added), receipt.remaining(listOf(added)))
    }
}
