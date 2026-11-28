package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ChatSessionOwnerTest {
    @Test fun documentsSurviveRestoreAndOnlySuccessfulAdmissionConsumesThem() = runBlocking {
        val root = Files.createTempDirectory("chat-documents").toFile()
        var configured = false
        val f = fixture(root, AtomicInteger(), provider(false), snapshot = {
            check(configured)
            OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v1", "model"), ApiKeyCredentials("test-key"))
        })
        try {
            f.owner.open().join()
            val session = requireNotNull(f.owner.state.value.sessionId)
            var drafts = ChatComposerDrafts { f.store.composerDrafts }
            val text = "long document tail\n".repeat(9000)
            val document = ComposerDocument("document-id", "report.md", "text/markdown", text.length.toLong(), text)
            drafts[session].documents = listOf(document, ComposerDocument("remove", "discard.txt", "text/plain", 4, "drop"))
            drafts[session].removeDocument("remove")
            drafts.checkpoint()
            drafts = ChatComposerDrafts { f.store.composerDrafts }
            val restored = drafts[session]
            assertEquals(listOf("document-id"), restored.documents.map { it.id })
            assertEquals(text, restored.documents.single().text)
            assertTrue(chatComposerAvailability(f.owner.state.value, false, restored).send)
            f.owner.send("", draft = drafts.receipt(session), documents = restored.documents).join()
            assertEquals(0L, f.owner.state.value.acceptedMessages)
            assertEquals(1, f.store.composerDrafts.read(session).documents.size)
            configured = true
            f.owner.send("", draft = drafts.receipt(session), documents = restored.documents).join()
            await { !f.owner.state.value.active }
            drafts.reconcile(session, f.owner.state.value.acceptedDraft)
            assertTrue(restored.documents.isEmpty())
            assertTrue(f.store.composerDrafts.read(session).documents.isEmpty())
            val input = f.owner.state.value.messages.first { it.role == Role.USER }.parts.filterIsInstance<ContentPart.Text>().joinToString { it.text }
            assertTrue(input.length < 6000)
            assertTrue(input.contains("report.md")); assertTrue(input.contains("blob_read"))
            val presentation = documentMessagePresentation(input)
            assertEquals("", presentation.text)
            assertEquals("report.md", presentation.documents.single().name)
            assertEquals(text.length, presentation.documents.single().characters)
            val id = Regex("\"blobId\":\"([a-f0-9]{64})\"").find(input)!!.groupValues[1]
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < text.toByteArray().size) bytes.write(f.host.ledger.readSessionBlob(session, id, bytes.size().toLong()).second)
            assertEquals(text, bytes.toString(Charsets.UTF_8.name()))
            assertEquals(1L, f.owner.state.value.acceptedMessages)
            // A stale page cannot submit or resurrect the already-consumed attachment.
            f.owner.send("", draft = ChatDraftReceipt(session, "stale", emptySet(), documentIds = setOf(document.id)), documents = listOf(document)).join()
            assertEquals(1L, f.owner.state.value.acceptedMessages)
            drafts.checkpoint()
            assertTrue(ChatComposerDrafts { f.store.composerDrafts }[session].documents.isEmpty())
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun loweredEffortNoticeSurvivesToolLoopAndSavedEditsOnlyAffectNextRun() = runBlocking {
        val root = Files.createTempDirectory("chat-reasoning-selection").toFile()
        val delegate = provider()
        val efforts = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val model = object : ModelProvider by delegate {
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> {
                efforts += request.reasoningLevel
                return delegate.chat(request, auth)
            }
        }
        var selected = "medium"
        var snapshots = 0
        val f = fixture(root, AtomicInteger(), model, snapshot = {
            snapshots++
            OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v1", "model",
                OpenAiParameters(reasoningLevel = selected), reasoningLevels = listOf("low", "high")), ApiKeyCredentials("test-key"))
        })
        try {
            f.owner.open().join(); f.owner.send("use a tool").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            assertEquals(ReasoningLevelAdjustment("medium", "low"), f.owner.state.value.reasoningAdjustment)
            selected = "high"
            val record = f.owner.state.value.records.values.single()
            f.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !f.owner.state.value.active }
            assertEquals(listOf("low", "low"), efforts.toList())
            assertEquals(1, snapshots)
            assertEquals(ReasoningLevelAdjustment("medium", "low"), f.owner.state.value.reasoningAdjustment)
            f.owner.send("new run").join(); await { !f.owner.state.value.active }
            assertEquals(listOf("low", "low", "high"), efforts.toList())
            assertNull(f.owner.state.value.reasoningAdjustment)
            assertEquals(2, snapshots)
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun staleDrawerDeleteCannotDeleteAnotherSession() = runBlocking {
        val root = Files.createTempDirectory("chat-drawer-delete-identity").toFile()
        val f = fixture(root, AtomicInteger(), provider(tool = false))
        try {
            f.owner.open().join()
            val first = requireNotNull(f.owner.state.value.sessionId)
            f.owner.newSession().join()
            val second = requireNotNull(f.owner.state.value.sessionId)
            f.owner.deleteCurrentSession(first).join()
            assertEquals(second, f.owner.state.value.sessionId)
            assertTrue(first in f.store.sessionIds())
            assertTrue(second in f.store.sessionIds())
            assertEquals(R.string.chat_operation_failed, f.owner.state.value.error)
            f.owner.deleteCurrentSession(second).join()
            assertFalse(second in f.store.sessionIds())
            assertTrue(first in f.store.sessionIds())
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun deletingSessionReleasesCachedDraftAndAttachmentsWhilePreservingOtherSessions() = runBlocking {
        val root = Files.createTempDirectory("chat-draft-delete-lifecycle").toFile()
        val f = fixture(root, AtomicInteger(), provider(tool = false))
        try {
            f.owner.open().join()
            val keepId = requireNotNull(f.owner.state.value.sessionId)
            f.owner.newSession().join()
            val deleteId = requireNotNull(f.owner.state.value.sessionId)
            assertNotEquals(keepId, deleteId)

            val activePage = ChatComposerDrafts { f.store.composerDrafts }
            val keepImg = PendingUserImage("keep-img", "image/png", byteArrayOf(7, 8, 9))
            val deleteImg = PendingUserImage("del-img", "image/png", byteArrayOf(1, 2, 3, 4))
            activePage[keepId].edit("unsent on other session")
            activePage[keepId].images = listOf(keepImg)
            val activeDeletedDraft = activePage[deleteId]
            activeDeletedDraft.edit("delete me")
            activeDeletedDraft.images = listOf(deleteImg)
            activePage.checkpoint()

            val oldPage = ChatComposerDrafts { f.store.composerDrafts }
            val oldDeletedDraft = oldPage[deleteId]
            val oldKeepDraft = oldPage[keepId]
            assertEquals("delete me", oldDeletedDraft.input)
            assertEquals(listOf("del-img"), oldDeletedDraft.images.map { it.localId })
            assertEquals("unsent on other session", oldKeepDraft.input)

            // Delete the current session; deletion succeeds and switches back to keepId.
            f.owner.deleteCurrentSession().join()
            assertFalse(deleteId in f.store.sessionIds())
            assertEquals(keepId, f.owner.state.value.sessionId)

            // Reconciling the active page after deletion releases the deleted draft and attachments
            // while preserving unsent edits and attachments on the other session.
            activePage.reconcile(f.owner.state.value.sessionId, f.owner.state.value.acceptedDraft)
            assertFalse(activePage.hasCachedDraft(deleteId))
            assertTrue(activePage.hasCachedDraft(keepId))
            assertEquals("", activeDeletedDraft.input)
            assertTrue(activeDeletedDraft.images.isEmpty())
            assertEquals("", activePage[deleteId].input)
            assertTrue(activePage[deleteId].images.isEmpty())
            assertFalse(activePage.hasCachedDraft(deleteId))
            assertEquals("unsent on other session", activePage[keepId].input)
            assertEquals(listOf("keep-img"), activePage[keepId].images.map { it.localId })
            assertArrayEquals(byteArrayOf(7, 8, 9), activePage[keepId].images.single().bytes)

            // An old page attempting late edits and checkpoint cannot write back to the deleted session,
            // releases its cached deleted draft/attachments, and still preserves other sessions' edits.
            oldDeletedDraft.edit("late stale write")
            oldDeletedDraft.images = listOf(PendingUserImage("late-img", "image/png", byteArrayOf(5, 6)))
            oldKeepDraft.edit("updated on other session")
            oldPage.checkpoint()

            assertFalse(oldPage.hasCachedDraft(deleteId))
            assertTrue(oldPage.hasCachedDraft(keepId))
            assertEquals("", oldDeletedDraft.input)
            assertTrue(oldDeletedDraft.images.isEmpty())
            val storedDeleted = f.store.composerDrafts.read(deleteId)
            assertNull(storedDeleted.version)
            assertEquals("", storedDeleted.text)
            assertTrue(storedDeleted.images.isEmpty())
            val storedKept = f.store.composerDrafts.read(keepId)
            assertEquals("updated on other session", storedKept.text)
            assertEquals(listOf("keep-img"), storedKept.images.map { it.id })
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun blockedSessionDeletionRetainsCachedDraftAndAttachments() = runBlocking {
        val root = Files.createTempDirectory("chat-draft-delete-blocked").toFile()
        val executions = AtomicInteger()
        val f = fixture(root, executions, provider(), tool(executions, ToolResult("unknown", effect = ToolEffect.UNKNOWN)))
        try {
            f.owner.open().join()
            f.owner.send("trigger unknown").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val call = f.owner.state.value.records.values.single()
            f.owner.decide(call.callId, call.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !f.owner.state.value.active }
            val id = requireNotNull(f.owner.state.value.sessionId)

            val drafts = ChatComposerDrafts { f.store.composerDrafts }
            val img = PendingUserImage("blocked-img", "image/png", byteArrayOf(3, 4))
            drafts[id].edit("keep when blocked")
            drafts[id].images = listOf(img)
            drafts.checkpoint()

            f.owner.deleteCurrentSession().join()
            assertEquals(R.string.chat_delete_recovery_required, f.owner.state.value.error)
            assertTrue(id in f.store.sessionIds())

            drafts.reconcile(f.owner.state.value.sessionId, f.owner.state.value.acceptedDraft)
            assertTrue(drafts.hasCachedDraft(id))
            assertEquals("keep when blocked", drafts[id].input)
            assertEquals(listOf("blocked-img"), drafts[id].images.map { it.localId })
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun twoPagesConsecutiveSendsClearReturnedDraftAndBlockDuplicateHistory() = runBlocking {
        val root = Files.createTempDirectory("chat-draft-two-pages-double-send").toFile()
        val model = visionProvider()
        val f = fixture(root, AtomicInteger(), model, userImageValidator = ::acceptTestImage)
        val id: String
        try {
            f.owner.open().join()
            id = requireNotNull(f.owner.state.value.sessionId)
            val pageA = ChatComposerDrafts { f.store.composerDrafts }
            val img1 = PendingUserImage("img-1", "image/png", byteArrayOf(1, 2, 3))
            pageA[id].edit("first")
            pageA[id].images = listOf(img1)
            pageA.checkpoint()

            val pageB = ChatComposerDrafts { f.store.composerDrafts }
            val draftB = pageB[id]
            assertEquals("first", draftB.input)
            assertEquals(listOf("img-1"), draftB.images.map { it.localId })

            f.owner.send(draftB.input, draftB.images, pageB.receipt(id)).join()
            await { !f.owner.state.value.active }
            draftB.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))
            assertEquals("", draftB.input)
            assertTrue(draftB.images.isEmpty())

            val img2 = PendingUserImage("img-2", "image/png", byteArrayOf(4, 5, 6))
            draftB.edit("second")
            draftB.images = listOf(img2)
            f.owner.send(draftB.input, draftB.images, pageB.receipt(id)).join()
            await { !f.owner.state.value.active }
            draftB.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))
            assertEquals("", draftB.input)
            assertTrue(draftB.images.isEmpty())

            // Stale Page A trying to re-send its already-consumed draft must not duplicate history.
            val staleA = pageA[id]
            f.owner.send(staleA.input, staleA.images, pageA.receipt(id)).join()
            await { !f.owner.state.value.active }
            assertEquals(2L, f.owner.state.value.acceptedMessages)
            assertEquals(2, model.requests)

            // When Page A re-displays with only the latest receipt, earlier consumed text & image must clear.
            staleA.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))
            assertEquals("", staleA.input)
            assertTrue(staleA.images.isEmpty())

            val userMessages = f.owner.state.value.messages.filter { it.role == Role.USER }
            assertEquals(listOf("first", "second"), userMessages.map {
                it.parts.filterIsInstance<ContentPart.Text>().single().text
            })
            assertEquals(listOf(1, 1), userMessages.map { it.parts.filterIsInstance<ContentPart.Media>().size })
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally { f.close() }
        val cold = fixture(root, AtomicInteger(), visionProvider(), userImageValidator = ::acceptTestImage)
        try {
            cold.owner.open().join()
            assertEquals(id, cold.owner.state.value.sessionId)
            val userMessages = cold.owner.state.value.messages.filter { it.role == Role.USER }
            assertEquals(listOf("first", "second"), userMessages.map {
                it.parts.filterIsInstance<ContentPart.Text>().single().text
            })
            val restored = ChatComposerDrafts { cold.store.composerDrafts }[id]
            assertEquals("", restored.input)
            assertTrue(restored.images.isEmpty())
        } finally { cold.close(); root.deleteRecursively() }
    }

    @Test fun returningPageAfterConsecutiveSendsPreservesUnsentEditsAndAllowsFreshSend() = runBlocking {
        val root = Files.createTempDirectory("chat-draft-two-pages-unsent").toFile()
        val model = visionProvider()
        val f = fixture(root, AtomicInteger(), model, userImageValidator = ::acceptTestImage)
        try {
            f.owner.open().join()
            val id = requireNotNull(f.owner.state.value.sessionId)
            val pageA = ChatComposerDrafts { f.store.composerDrafts }
            val img1 = PendingUserImage("sent-1", "image/png", byteArrayOf(1, 1))
            pageA[id].edit("first")
            pageA[id].images = listOf(img1)
            pageA.checkpoint()

            val pageB = ChatComposerDrafts { f.store.composerDrafts }
            val draftB = pageB[id]
            f.owner.send(draftB.input, draftB.images, pageB.receipt(id)).join()
            await { !f.owner.state.value.active }
            draftB.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))

            draftB.edit("second")
            f.owner.send(draftB.input, draftB.images, pageB.receipt(id)).join()
            await { !f.owner.state.value.active }
            draftB.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))

            // Page A edited text and added a fresh attachment while still holding consumed img1.
            val imgNew = PendingUserImage("fresh-a", "image/png", byteArrayOf(9, 8, 7))
            val draftA = pageA[id]
            draftA.edit("unsent follow-up")
            draftA.images = listOf(img1, imgNew)

            // Attempting to send while consumed img1 is still attached must not duplicate img1 into history.
            f.owner.send(draftA.input, draftA.images, pageA.receipt(id)).join()
            await { !f.owner.state.value.active }
            assertEquals(2L, f.owner.state.value.acceptedMessages)
            assertEquals(2, model.requests)

            // Re-displaying Page A clears consumed img1 while retaining unsent text and fresh attachment.
            draftA.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))
            assertEquals("unsent follow-up", draftA.input)
            assertEquals(listOf("fresh-a"), draftA.images.map { it.localId })
            assertArrayEquals(byteArrayOf(9, 8, 7), draftA.images.single().bytes)

            // Sending the preserved unsent edits now succeeds cleanly.
            val freshReceipt = pageA.receipt(id)
            f.owner.send(draftA.input, draftA.images, freshReceipt).join()
            await { !f.owner.state.value.active }
            draftA.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))
            assertEquals("", draftA.input)
            assertTrue(draftA.images.isEmpty())

            // Re-sending the just-consumed receipt again is also blocked.
            f.owner.send("unsent follow-up", listOf(imgNew), freshReceipt).join()
            await { !f.owner.state.value.active }
            assertEquals(3L, f.owner.state.value.acceptedMessages)
            assertEquals(3, model.requests)

            val userMessages = f.owner.state.value.messages.filter { it.role == Role.USER }
            assertEquals(listOf("first", "second", "unsent follow-up"), userMessages.map {
                it.parts.filterIsInstance<ContentPart.Text>().single().text
            })
            assertEquals(listOf(1, 0, 1), userMessages.map { it.parts.filterIsInstance<ContentPart.Media>().size })
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun coldStartAfterSendingReplacementDoesNotRestoreOldText() = runBlocking {
        checkColdDraftAfterSend { book, id ->
            book[id].edit("old text")
            book.checkpoint()
            book[id].edit("final text")
        }
    }

    @Test fun coldStartAfterSendingTextDoesNotRestoreRemovedImage() = runBlocking {
        checkColdDraftAfterSend { book, id ->
            book[id].images = listOf(PendingUserImage("removed", "image/png", byteArrayOf(1, 2, 3)))
            book.checkpoint()
            book[id].images = emptyList()
            book[id].edit("final text")
        }
    }

    private suspend fun checkColdDraftAfterSend(prepare: (ChatComposerDrafts, String) -> Unit) {
        val root = Files.createTempDirectory("chat-draft-replace").toFile()
        val first = fixture(root, AtomicInteger(), provider(tool = false))
        val id: String
        try {
            first.owner.open().join()
            id = requireNotNull(first.owner.state.value.sessionId)
            val drafts = ChatComposerDrafts { first.store.composerDrafts }
            prepare(drafts, id)
            val draft = drafts[id]
            first.owner.send(draft.input, draft.images, drafts.receipt(id)).join()
            await { !first.owner.state.value.active }
        } finally { first.close() }
        val cold = fixture(root, AtomicInteger(), provider(tool = false))
        try {
            cold.owner.open().join()
            assertEquals(id, cold.owner.state.value.sessionId)
            val parts = cold.owner.state.value.messages.single { it.role == Role.USER }.parts
            assertEquals("final text", (parts.single() as ContentPart.Text).text)
            val recovered = ChatComposerDrafts { cold.store.composerDrafts }[id]
            assertTrue(recovered.input.isEmpty())
            assertTrue(recovered.images.isEmpty())
        } finally { cold.close(); root.deleteRecursively() }
    }

    @Test fun admissionConsumesSavedDraftAcrossColdStartWithoutAnotherCheckpoint() = runBlocking {
        val root = Files.createTempDirectory("chat-draft-admission").toFile()
        val f = fixture(root, AtomicInteger(), provider(tool = false))
        val id: String
        try {
            f.owner.open().join()
            id = requireNotNull(f.owner.state.value.sessionId)
            val drafts = ChatComposerDrafts { f.store.composerDrafts }
            val draft = drafts[id]
            draft.edit("send once")
            drafts.checkpoint()
            val receipt = ChatDraftReceipt(id, draft.textVersion, emptySet())
            f.owner.send(draft.input, draft = receipt).join()
            await { !f.owner.state.value.active }
            draft.acknowledge(requireNotNull(f.owner.state.value.acceptedDraft))
            assertEquals("", draft.input)
        } finally { f.close() }
        val cold = fixture(root, AtomicInteger(), provider(tool = false))
        try {
            cold.owner.open().join()
            assertEquals(id, cold.owner.state.value.sessionId)
            assertEquals(listOf("send once"), cold.owner.state.value.messages.filter { it.role == Role.USER }
                .flatMap { it.parts.filterIsInstance<ContentPart.Text>() }.map { it.text })
            assertEquals("", ChatComposerDrafts { cold.store.composerDrafts }[id].input)
        } finally { cold.close(); root.deleteRecursively() }
    }

    @Test fun sessionDeletionRemovesDraftAndRejectsLatePageSave() = runBlocking {
        val root = Files.createTempDirectory("chat-draft-delete").toFile()
        val f = fixture(root, AtomicInteger(), provider(tool = false))
        try {
            f.owner.open().join()
            val id = requireNotNull(f.owner.state.value.sessionId)
            val book = ChatComposerDrafts { f.store.composerDrafts }
            val draft = book[id]
            draft.edit("unsubmitted")
            draft.images = listOf(PendingUserImage(mimeType = "image/png", bytes = byteArrayOf(1, 2)))
            book.checkpoint()
            assertEquals(1, f.store.composerDrafts.read(id).images.size)
            f.owner.deleteCurrentSession().join()
            assertFalse(id in f.store.sessionIds())
            draft.edit("late edit")
            draft.images = draft.images + PendingUserImage(mimeType = "image/png", bytes = byteArrayOf(3))
            book.checkpoint()
            val stored = f.store.composerDrafts.read(id)
            assertNull(stored.version)
            assertEquals("", stored.text)
            assertTrue(stored.images.isEmpty())
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun stoppingSavedStepCommitsBeforeTimedOutWorkerExit() = runBlocking {
        stopSavedStepBeforeExit(cancel = false)
    }

    @Test fun stoppingSavedStepCommitsBeforeCancelledWorkerExit() = runBlocking {
        stopSavedStepBeforeExit(cancel = true)
    }

    private suspend fun stopSavedStepBeforeExit(cancel: Boolean) {
        val root = Files.createTempDirectory("chat-stop-saved").toFile()
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val controlled = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "controlled", schema, risk = ToolRisk.READ,
                timeoutMs = if (cancel) 30_000 else 150, exclusiveResource = "saved-resource")
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                count.incrementAndGet()
                withContext(NonCancellable) { entered.complete(env.callId); release.await() }
                return ToolResult("late result", effect = ToolEffect.UNKNOWN)
            }
        }
        val model = provider(calls = 2)
        val f = fixture(root, count, model, controlled)
        var firstId: String? = null
        try {
            f.owner.open().join(); f.owner.send("two reads").join()
            val id = withTimeout(10_000) { entered.await() }; firstId = id
            if (cancel) f.host.cancel(id)
            await { !f.owner.state.value.active }
            assertTrue(f.owner.state.value.pending)
            val before = f.owner.state.value.records.values.sortedBy { it.input.callOrder }
            val first = f.host.ledger.get(id)!!
            assertEquals(if (cancel) Status.CANCELLED else Status.TIMED_OUT, first.state)
            assertEquals(InstanceState.UNCONFIRMED, first.instance!!.state)
            val session = f.owner.state.value.sessionId!!
            f.owner.stop().join()
            assertFalse("Stop must commit without waiting for actual exit", f.owner.state.value.pending)
            assertEquals(R.string.chat_stopped, f.owner.state.value.notice)
            val stopped = f.host.ledger.get(id)!!
            assertEquals(first.result, stopped.result)
            assertEquals(first.instance, stopped.instance)
            assertEquals(first.isolation, stopped.isolation)
            assertEquals(Status.CANCELLED, f.host.ledger.get(before[1].callId)!!.state)
            assertNull(f.host.ledger.get(before[1].callId)!!.instance)
            assertEquals(1, f.budget.occupiedSlots()); assertEquals(1, count.get())
            assertEquals(1, model.requests)
            val committed = f.owner.state.value.messages
            assertEquals(before.map { it.callId }, committed.last().parts.filterIsInstance<ContentPart.ToolResult>().map { it.callId })
            validateHistory(committed)
            f.owner.stop().join(); f.owner.select(session).join()
            assertFalse(f.owner.state.value.pending)
            val expected = f.host.ledger.resultsForStep(session, first.input.runId,
                first.input.stepId, before.map { it.callId })!!.parts.filterIsInstance<ContentPart.ToolResult>()
            val actual = f.owner.state.value.messages.last().parts.filterIsInstance<ContentPart.ToolResult>()
            assertEquals(expected.map { Triple(it.callId, it.text, it.isError) },
                actual.map { Triple(it.callId, it.text, it.isError) })
            assertEquals(committed, f.owner.state.value.messages)
            assertEquals(1, f.budget.occupiedSlots())
            release.complete(Unit); f.host.awaitObservedExit(id)
            await { f.owner.state.value.records[id]?.instance?.state == InstanceState.EXIT_CONFIRMED }
            assertEquals(first.instance!!.attemptId, f.host.ledger.get(id)!!.instance!!.attemptId)
            assertEquals(first.result, f.host.ledger.get(id)!!.result)
            assertEquals(0, f.budget.occupiedSlots())
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally {
            release.complete(Unit); firstId?.let { f.host.awaitObservedExit(it) }
            f.close(); root.deleteRecursively()
        }
    }

    @Test fun stoppingRetainedSiblingCommitsWithoutReleasingItsLease() = runBlocking {
        val root = Files.createTempDirectory("chat-stop-retained").toFile()
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val controlled = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "controlled", schema, risk = ToolRisk.READ,
                timeoutMs = 30_000, parallelSafe = true)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                count.incrementAndGet()
                withContext(NonCancellable) { entered.complete(env.callId); release.await() }
                return ToolResult("late", effect = ToolEffect.NONE)
            }
        }
        val model = provider(calls = 2)
        val f = fixture(root, count, model, controlled, budget = PluginExecutionBudget(1, 1))
        var firstId: String? = null
        try {
            f.owner.open().join(); f.owner.send("two parallel reads").join()
            val id = withTimeout(10_000) { entered.await() }; firstId = id
            await { f.owner.state.value.pending && f.owner.state.value.records.values.any { it.state == Status.READY } }
            val before = f.host.ledger.get(id)!!
            f.owner.stop().join(); await { !f.owner.state.value.active }
            assertFalse(f.owner.state.value.pending)
            assertEquals(R.string.chat_stopped, f.owner.state.value.notice)
            val stopped = f.host.ledger.get(id)!!
            assertEquals(Status.CANCELLED, stopped.state)
            assertEquals(before.input, stopped.input); assertEquals(before.instance, stopped.instance)
            assertEquals(1, f.budget.occupiedSlots()); assertEquals(1, count.get()); assertEquals(1, model.requests)
            val records = f.owner.state.value.records.values.sortedBy { it.input.callOrder }
            assertEquals(listOf(Status.CANCELLED, Status.CANCELLED), records.map { it.state })
            assertNull(records[1].instance)
            validateHistory(f.owner.state.value.messages)
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally {
            release.complete(Unit); firstId?.let { f.host.awaitObservedExit(it) }
            f.close(); root.deleteRecursively()
        }
    }

    @Test fun stoppingSavedContinuationDuringForegroundAdmissionCommitsBeforeWorkerExit() = runBlocking {
        val root = Files.createTempDirectory("chat-stop-saved-admission").toFile()
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val admission = CompletableDeferred<Unit>()
        val presence = RecordingPresence()
        val count = AtomicInteger()
        val controlled = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "controlled", schema, risk = ToolRisk.READ,
                timeoutMs = 30_000, exclusiveResource = "saved-admission-resource")
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                count.incrementAndGet()
                withContext(NonCancellable) { entered.complete(env.callId); release.await() }
                return ToolResult("late", effect = ToolEffect.NONE)
            }
        }
        val model = provider(calls = 2)
        val f = fixture(root, count, model, controlled, presence)
        var firstId: String? = null
        try {
            f.owner.open().join(); f.owner.send("two reads").join()
            val id = withTimeout(10_000) { entered.await() }; firstId = id
            f.host.cancel(id)
            await { !f.owner.state.value.active }
            assertTrue(f.owner.state.value.pending)
            val original = f.host.ledger.get(id)!!
            val records = f.owner.state.value.records.values.sortedBy { it.input.callOrder }
            assertEquals(Status.READY, records[1].state)
            val session = f.owner.state.value.sessionId!!
            val eventsBefore = presence.events.size
            presence.admitted = admission
            f.owner.continueSavedStep().join()
            await { presence.events.size > eventsBefore }
            f.owner.stop().join(); await { !f.owner.state.value.active }
            assertFalse("Stop must settle a saved step even before foreground admission", f.owner.state.value.pending)
            assertEquals(R.string.chat_stopped, f.owner.state.value.notice)
            assertEquals(original.result, f.host.ledger.get(id)!!.result)
            assertEquals(original.instance, f.host.ledger.get(id)!!.instance)
            assertEquals(original.isolation, f.host.ledger.get(id)!!.isolation)
            assertEquals(Status.CANCELLED, f.host.ledger.get(records[1].callId)!!.state)
            assertNull(f.host.ledger.get(records[1].callId)!!.instance)
            assertEquals(1, f.budget.occupiedSlots())
            assertEquals(1, count.get()); assertEquals(1, model.requests)
            val committed = f.owner.state.value.messages
            assertEquals(records.map { it.callId }, committed.last().parts.filterIsInstance<ContentPart.ToolResult>().map { it.callId })
            validateHistory(committed)
            f.owner.select(session).join()
            assertEquals(committed, f.owner.state.value.messages)
            assertFalse(f.owner.state.value.pending)
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally {
            admission.complete(Unit); release.complete(Unit)
            firstId?.let { f.host.awaitObservedExit(it) }
            f.close(); root.deleteRecursively()
        }
    }

    @Test fun retainedSiblingApprovalKeepsExecutionIdentityAndForegroundAdmission() = runBlocking {
        val root = Files.createTempDirectory("chat-retained-approval").toFile()
        val requestApproval = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<String>()
        val returned = CompletableDeferred<Boolean>()
        val admission = CompletableDeferred<Unit>()
        val presence = RecordingPresence()
        val count = AtomicInteger()
        val controlled = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "controlled", schema, risk = ToolRisk.READ,
                timeoutMs = 30_000, parallelSafe = true)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                if (count.incrementAndGet() == 1) {
                    entered.complete(env.callId); requestApproval.await()
                    returned.complete(env.requestApproval("write after read", ToolRisk.WRITE))
                }
                return ToolResult("original result", effect = ToolEffect.NONE)
            }
        }
        val model = provider(calls = 2)
        val f = fixture(root, count, model, controlled, presence, budget = PluginExecutionBudget(1, 1))
        var firstId: String? = null
        try {
            f.owner.open().join(); f.owner.send("two parallel reads").join()
            val id = withTimeout(10_000) { entered.await() }; firstId = id
            await { f.owner.state.value.pending && f.owner.state.value.records.values.any { it.state == Status.READY } }
            val token = f.owner.state.value.executionId
            val original = f.host.ledger.get(id)!!
            requestApproval.complete(Unit)
            await { f.host.ledger.get(id)!!.pending != null }
            assertTrue("The live sibling still belongs to the original execution", f.owner.state.value.active)
            assertNotNull(token)
            presence.admitted = admission
            val request = f.host.ledger.get(id)!!.pending!!
            val decision = f.owner.decide(id, request.id, Decision.ALLOW_ONCE)
            await { presence.events.last().second == ChatTaskProgress.Model }
            assertFalse(returned.isCompleted)
            assertEquals(request, f.host.ledger.get(id)!!.pending)
            admission.complete(Unit); decision.join()
            assertTrue(withTimeout(10_000) { returned.await() })
            f.host.awaitObservedExit(id); await { !f.owner.state.value.active }
            assertTrue(f.owner.state.value.pending)
            val completed = f.host.ledger.get(id)!!
            assertEquals(Status.SUCCEEDED, completed.state)
            assertEquals(original.input, completed.input)
            assertEquals(original.instance!!.attemptId, completed.instance!!.attemptId)
            assertEquals(listOf(Decision.ALLOW_ONCE), completed.receipts.map { it.decision })
            assertEquals(listOf(token), presence.events.map { it.first }.distinct())
            assertEquals(1, count.get()); assertEquals(1, model.requests)
            f.owner.continueSavedStep().join(); await { !f.owner.state.value.active }
            assertFalse(f.owner.state.value.pending)
            assertEquals(2, count.get()); assertEquals(1, model.requests)
            assertEquals(completed, f.host.ledger.get(id))
            validateHistory(f.owner.state.value.messages)
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally {
            admission.complete(Unit); requestApproval.complete(Unit)
            firstId?.let { f.host.cancel(it); f.host.awaitObservedExit(it) }
            f.close(); root.deleteRecursively()
        }
    }

    @Test fun savedStepResumesAfterTimeoutExitWithoutReplayingSubmittedCall() = runBlocking {
        blockedStepResumes(cancel = false, sticky = false)
    }

    @Test fun savedStepResumesAfterCancelledExitWithoutReplayingSubmittedCall() = runBlocking {
        blockedStepResumes(cancel = true, sticky = false)
    }

    @Test fun savedStepRetainsIndependentRestartRestrictionAfterExit() = runBlocking {
        blockedStepResumes(cancel = false, sticky = true)
    }

    private suspend fun blockedStepResumes(cancel: Boolean, sticky: Boolean) {
        val root = Files.createTempDirectory("chat-step-resume").toFile()
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger()
        val controlled = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "controlled read", schema, risk = ToolRisk.READ,
                timeoutMs = if (cancel) 30_000 else 150)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                if (executions.incrementAndGet() == 1) withContext(NonCancellable) {
                    entered.complete(env.callId)
                    release.await()
                }
                return ToolResult("read", effect = ToolEffect.NONE)
            }
        }
        val model = provider(calls = 2)
        val f = fixture(root, executions, model, controlled)
        var firstCall: String? = null
        try {
            f.owner.open().join(); f.owner.send("two reads").join()
            val firstId = withTimeout(10_000) { entered.await() }
            firstCall = firstId
            if (cancel) f.host.cancel(firstId) // Cancel this invocation, not the user's whole run.
            await { !f.owner.state.value.active }
            assertTrue("Admission pause must retain the original step", f.owner.state.value.pending)
            val before = f.owner.state.value.records.values.sortedBy { it.input.callOrder }
            assertEquals(2, before.size)
            val first = f.host.ledger.get(firstId)!!
            val nextId = before[1].callId
            assertEquals(if (cancel) Status.CANCELLED else Status.TIMED_OUT, first.state)
            assertEquals(Status.READY, f.host.ledger.get(nextId)!!.state)
            assertNull(f.host.ledger.get(nextId)!!.instance)
            assertEquals(1, executions.get()); assertEquals(1, model.requests)
            await { f.host.ledger.get(firstId)!!.restartRequiredUntilExit }
            if (sticky) assertTrue(f.host.ledger.markRestartRequired(firstId, first.instance!!.attemptId) is Mutation.Applied)
            // An early explicit retry rechecks admission and preserves both bindings.
            f.owner.continueSavedStep().join(); await { !f.owner.state.value.active }
            assertTrue(f.owner.state.value.pending)
            assertEquals(Status.READY, f.host.ledger.get(nextId)!!.state)
            assertEquals(1, executions.get()); assertEquals(1, f.budget.occupiedSlots())
            release.complete(Unit); f.host.awaitObservedExit(firstId)
            val exited = f.host.ledger.get(firstId)!!
            assertEquals(first.result, exited.result)
            assertEquals(first.instance!!.attemptId, exited.instance!!.attemptId)
            assertEquals(InstanceState.EXIT_CONFIRMED, exited.instance!!.state)
            assertEquals(sticky, exited.restartRequired)
            f.owner.continueSavedStep().join(); await { !f.owner.state.value.active }
            assertEquals(sticky, f.owner.state.value.pending)
            assertEquals(if (sticky) 1 else 2, executions.get())
            assertEquals(1, model.requests) // Resuming a saved step never regenerates the response.
            if (sticky) {
                assertEquals(Status.READY, f.host.ledger.get(nextId)!!.state)
                assertNull(f.host.ledger.get(nextId)!!.instance)
                f.owner.stop().join()
                assertEquals(Status.CANCELLED, f.host.ledger.get(nextId)!!.state)
            } else {
                assertEquals(Status.SUCCEEDED, f.host.ledger.get(nextId)!!.state)
                val results = f.owner.state.value.messages.last().parts.filterIsInstance<ContentPart.ToolResult>()
                assertEquals(before.map { it.callId }, results.map { it.callId })
                assertEquals(first.result, f.host.ledger.get(firstId)!!.result)
                validateHistory(f.owner.state.value.messages)
            }
            assertEquals(0, f.budget.occupiedSlots())
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally {
            release.complete(Unit)
            firstCall?.let { f.host.awaitObservedExit(it) }
            f.close(); root.deleteRecursively()
        }
    }

    @Test fun diagnosticsFollowFailureColdOpenSessionSwitchAndLaterSuccess() = runBlocking {
        val root = Files.createTempDirectory("chat-diagnostics").toFile()
        val requests = AtomicInteger()
        val responseStarted = CompletableDeferred<Unit>()
        val responseAllowed = CompletableDeferred<Unit>()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                if (requests.incrementAndGet() == 1) {
                    emit(Events.failed(modelError(ModelErrorCode.AUTH, "Authorization: Bearer private-value")))
                } else {
                    emit(Events.started("success", "model"))
                    responseStarted.complete(Unit); responseAllowed.await()
                    emit(Events.text(0, "done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
        }
        var live: Fixture? = null
        try {
            val first = fixture(root, AtomicInteger(), model).also { live = it }
            first.owner.open().join(); first.owner.send("first").join()
            await { !first.owner.state.value.active }
            assertEquals(RunState.FAILED, first.owner.state.value.result?.state)
            val failedSession = first.owner.state.value.sessionId!!
            val failedDiagnostic = requireNotNull(chatDiagnostic(first.owner.state.value))
            assertFalse(failedDiagnostic.toString().contains("private-value"))
            first.close(); live = null
            val cold = fixture(root, AtomicInteger(), model).also { live = it }
            cold.owner.open().join()
            assertEquals(failedSession, cold.owner.state.value.sessionId)
            assertEquals(failedDiagnostic, chatDiagnostic(cold.owner.state.value))
            assertEquals(1, requests.get())
            cold.owner.newSession().join()
            val emptySession = cold.owner.state.value.sessionId!!
            assertNull(chatDiagnostic(cold.owner.state.value))
            cold.owner.select(failedSession).join()
            assertEquals(failedDiagnostic, chatDiagnostic(cold.owner.state.value))
            cold.owner.send("try again").join()
            withTimeout(10_000) { responseStarted.await() }
            assertTrue(cold.owner.state.value.active)
            assertNull(chatDiagnostic(cold.owner.state.value))
            responseAllowed.complete(Unit)
            await { !cold.owner.state.value.active }
            assertEquals(RunState.COMPLETED, cold.owner.state.value.result?.state)
            assertNull(chatDiagnostic(cold.owner.state.value))
            cold.owner.select(emptySession).join(); cold.owner.select(failedSession).join()
            assertNull(chatDiagnostic(cold.owner.state.value))
            assertEquals(2, requests.get())
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
        } finally {
            responseAllowed.complete(Unit)
            live?.close(); root.deleteRecursively()
        }
    }

    @Test fun sessionPreviewsSurviveSendColdOpenPagingSelectionAndDeletion() = runBlocking {
        val root = Files.createTempDirectory("chat-session-previews").toFile()
        try {
            val first = fixture(root, AtomicInteger(), provider(false))
            repeat(52) { index ->
                first.store.createHistory("old-${index.toString().padStart(3, '0')}").use {
                    it.append(0, listOf(message(Role.USER, textPart("Previous $index"))))
                }
            }
            first.owner.open().join()
            first.owner.newSession().join()
            val id = first.owner.state.value.sessionId!!
            assertNull(first.owner.state.value.sessions.first().text)
            first.owner.send("\n我的   长问题 " + "中".repeat(80)).join()
            await { !first.owner.state.value.active }
            val title = "我的 长问题 " + "中".repeat(52) + "…"
            assertEquals(title, first.owner.state.value.sessions.first().text)
            first.owner.send("Later question").join(); await { !first.owner.state.value.active }
            assertEquals(title, first.owner.state.value.sessions.first().text)
            first.close()
            val cold = fixture(root, AtomicInteger(), provider(false))
            cold.owner.open().join(); cold.owner.select(id).join()
            assertEquals(title, cold.owner.state.value.sessions.first().text)
            assertTrue(cold.owner.state.value.moreSessionsAvailable)
            cold.owner.moreSessions().join()
            assertEquals(53, cold.owner.state.value.sessions.size)
            assertEquals(53, cold.owner.state.value.sessions.map { it.sessionId }.distinct().size)
            assertFalse(cold.owner.state.value.moreSessionsAvailable)
            assertEquals("Previous 0", cold.owner.state.value.sessions.single { it.sessionId == "old-000" }.text)
            cold.owner.select("old-000").join()
            assertEquals("Previous 0", cold.owner.state.value.sessions.first().text)
            cold.owner.select(id).join(); cold.owner.deleteCurrentSession().join()
            assertNull(cold.store.sessionPreview(id).text)
            assertFalse(cold.owner.state.value.sessions.any { it.sessionId == id })
            cold.owner.newSession().join()
            assertNull(cold.owner.state.value.sessions.first().text)
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun deletionRetainsUnknownEffectRecoveryAcrossPagesAndColdOpen() = runBlocking {
        val root = Files.createTempDirectory("chat-delete-recovery").toFile()
        val executions = AtomicInteger()
        try {
            val first = fixture(root, executions, provider(), tool(executions, ToolResult("unknown", effect = ToolEffect.UNKNOWN)))
            first.owner.open().join(); first.owner.send("execute").join()
            await { first.owner.state.value.records.values.any { it.pending != null } }
            val call = first.owner.state.value.records.values.single()
            first.owner.decide(call.callId, call.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !first.owner.state.value.active }
            val sessionId = first.owner.state.value.sessionId!!
            assertEquals(1, first.owner.state.value.recoveries.size)
            first.owner.deleteCurrentSession().join()
            assertTrue(first.store.sessionIds().contains(sessionId))
            assertEquals(sessionId, first.owner.state.value.sessionId)
            assertEquals(1, first.host.ledger.pendingRecoveries().size)
            first.close()
            val cold = fixture(root, executions, provider(false))
            cold.owner.open().join()
            repeat(22) {
                cold.owner.send("text only").join(); await { !cold.owner.state.value.active }
            }
            assertFalse(cold.owner.state.value.records.containsKey(call.callId))
            cold.owner.deleteCurrentSession().join()
            assertTrue(cold.store.sessionIds().contains(sessionId))
            assertEquals(1, cold.host.ledger.pendingRecoveries().size)
            while (call.callId !in cold.owner.state.value.records && cold.owner.state.value.older != null)
                cold.owner.older().join()
            assertTrue(cold.owner.state.value.recoveries.containsKey(call.callId))
            assertEquals(Effect.UNKNOWN, cold.host.ledger.get(call.callId)!!.result!!.effect)
            assertEquals(1, executions.get())
            cold.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun imageToolUsesNormalApprovalStoresPrivatelyAndColdOpenDoesNotGenerate() = runBlocking {
        val root = Files.createTempDirectory("chat-images").toFile()
        val executions = AtomicInteger()
        val payload = byteArrayOf(1, 2, 3)
        val toolAdmissionStarted = CompletableDeferred<Unit>()
        val toolAdmitted = CompletableDeferred<Unit>()
        val presence = object : ChatTaskPresence {
            @Volatile var ready: () -> Boolean = { false }
            override suspend fun update(executionId: String, progress: ChatTaskProgress) {
                if (progress == ChatTaskProgress.Model && ready()) {
                    toolAdmissionStarted.complete(Unit)
                    toolAdmitted.await()
                }
            }
            override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) = Unit
        }
        fun imageTool(attachments: ImageAttachments) = io.github.bytejuno.thetacore.plugin.tools.HostImageGenerationTool(attachments, { _, prompt ->
            assertEquals("画一只在窗边睡觉的猫", prompt)
            executions.incrementAndGet(); payload
        }, { it })
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val index = requests.incrementAndGet()
                emit(Events.started("image-response-$index", "model"))
                if (index == 1) {
                    emit(Events.toolStart(0, "image-wire", "host_generate_image"))
                    emit(Events.toolDelta(0, """{"prompt":"画一只在窗边睡觉的猫"}"""))
                    emit(Events.end(0)); emit(Events.finished(FinishReason.TOOL_CALLS))
                } else {
                    emit(Events.text(0, "done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
        }
        try {
            val first = fixture(root, executions, model, presence = presence)
            val attachments = ImageAttachments(first.store.privateImages)
            val images = imageTool(attachments)
            first.host.catalog.registerHost(IMAGE_TOOL_KEY, "1", images)
            first.owner.open().join(); first.owner.send("画一只在窗边睡觉的猫").join()
            await { first.owner.state.value.records.values.any { it.pending != null } }
            val call = first.owner.state.value.records.values.single()
            val imageSnapshot = OpenAiRunSnapshot(OpenAiProviderSettings("images", "https://example.invalid/v1", "images"), ApiKeyCredentials("image-key"))
            images.configurations.bind(call.input.runId, imageSnapshot.withImageSnapshot(imageSnapshot))
            assertEquals(0, executions.get())
            presence.ready = { first.host.ledger.get(call.callId)?.state == Status.READY }
            val approval = first.owner.decide(call.callId, call.pending!!.id, Decision.ALLOW_ONCE)
            try {
                withTimeout(10_000) { toolAdmissionStarted.await() }
                assertEquals(0, executions.get())
                assertEquals(1, requests.get())
                assertNull(attachments.read(first.owner.state.value.sessionId!!, call.callId))
            } finally { toolAdmitted.complete(Unit) }
            approval.join()
            await { !first.owner.state.value.active }
            assertEquals(1, executions.get())
            assertEquals(IMAGE_COMPLETE, first.owner.state.value.records.getValue(call.callId).result!!.text)
            val sessionId = first.owner.state.value.sessionId!!
            assertArrayEquals(payload, attachments.read(sessionId, call.callId))
            assertTrue(first.failures.toString(), first.failures.isEmpty())
            first.close()
            val cold = fixture(root, executions, model)
            cold.owner.open().join()
            assertEquals(sessionId, cold.owner.state.value.sessionId)
            assertEquals(2, requests.get()); assertEquals(1, executions.get())
            assertEquals(IMAGE_COMPLETE, cold.owner.state.value.records.getValue(call.callId).result!!.text)
            assertArrayEquals(payload, ImageAttachments(cold.store.privateImages).read(sessionId, call.callId))
            cold.owner.deleteCurrentSession().join()
            assertFalse(cold.store.sessionIds().contains(sessionId))
            assertNull(ImageAttachments(cold.store.privateImages).read(sessionId, call.callId))
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun ordinaryPromptWritingAndApiQuestionsDoNotGenerateWithoutModelToolCalls() = runBlocking {
        val root = Files.createTempDirectory("chat-image-text").toFile()
        val executions = AtomicInteger()
        try {
            val fixture = fixture(root, executions, provider(false))
            fixture.host.catalog.registerHost(IMAGE_TOOL_KEY, "1", tool(executions))
            fixture.owner.open().join()
            for (text in listOf("帮我写一个画猫的提示词", "解释生图 API")) {
                fixture.owner.send(text).join(); await { !fixture.owner.state.value.active }
                assertEquals(R.string.chat_complete, fixture.owner.state.value.notice)
            }
            assertEquals(0, executions.get())
            assertTrue(fixture.owner.state.value.records.isEmpty())
            fixture.close()
        } finally { root.deleteRecursively() }
    }
    private val schema = Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject
    private fun tool(count: AtomicInteger, output: ToolResult = ToolResult("done", effect = ToolEffect.APPLIED)) = object : Tool {
        override val id = "write"
        override val spec = ToolSpec("write", "test write", schema, risk = ToolRisk.WRITE, timeoutMs = 5_000)
        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
            count.incrementAndGet(); return output
        }
    }
    private suspend fun await(describe: String = "condition", predicate: () -> Boolean) {
        try { withTimeout(10_000) { while (!predicate()) delay(10) } }
        catch (error: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $describe", error)
        }
    }
    @Test fun coldImageApprovalCannotAdoptNewProviderButNewRunUsesNewBinding() = runBlocking {
        val root = Files.createTempDirectory("chat-cold-image-binding").toFile()
        val count = AtomicInteger()
        fun snapshot(id: String): OpenAiRunSnapshot {
            val image = OpenAiRunSnapshot(OpenAiProviderSettings(id, "https://$id.invalid/v1", "image-$id"), ApiKeyCredentials("key-$id"))
            return OpenAiRunSnapshot(OpenAiProviderSettings("chat", "https://chat.invalid/v1", "chat"), ApiKeyCredentials("chat-key"))
                .withImageSnapshot(image)
        }
        fun model() = object : ModelProvider {
            var requests = 0
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                emit(Events.started("response-${requests++}", "chat"))
                if (requests == 1) {
                    emit(Events.toolStart(0, "image-wire", "host_generate_image"))
                    emit(Events.toolDelta(0, """{"prompt":"cat"}""")); emit(Events.end(0))
                    emit(Events.finished(FinishReason.TOOL_CALLS))
                } else {
                    emit(Events.text(0, "done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
        }
        fun imageTool(store: InvocationStore): Tool = io.github.bytejuno.thetacore.plugin.tools.HostImageGenerationTool(
            ImageAttachments((store as AndroidAgentStore).privateImages), { config, _ ->
                assertEquals("image-b", config.settings.model)
                assertEquals("https://b.invalid/v1", config.settings.baseUrl)
                assertEquals("key-b", config.target().credentials.accessToken())
                count.incrementAndGet(); byteArrayOf(1, 2, 3)
            }, { it })
        try {
            val live = File(root, "live").also { it.mkdir() }
            val saved = File(root, "saved").also { it.mkdir() }
            val seed = fixture(live, count, model(), imageTool = ::imageTool, snapshot = { snapshot("a") })
            seed.owner.open().join(); seed.owner.send("draw cat").join()
            await { seed.owner.state.value.records.values.any { it.pending != null } }
            val original = seed.owner.state.value.records.values.single()
            BundledSQLiteDriver().open(File(live, AgentDatabase.FILE_NAME).path).use { db ->
                db.prepare("VACUUM INTO ?").use {
                    it.bindText(1, File(saved, AgentDatabase.FILE_NAME).path); it.step()
                }
            }
            seed.close()
            val cold = fixture(saved, count, model(), imageTool = ::imageTool, snapshot = { snapshot("b") })
            try {
                cold.owner.open().join()
                val pending = cold.host.ledger.get(original.callId)!!.pending!!
                cold.owner.decide(original.callId, pending.id, Decision.ALLOW_ONCE).join()
                await { !cold.owner.state.value.active }
                val result = cold.host.ledger.get(original.callId)!!.result!!
                assertEquals(IMAGE_CONFIGURE, result.text)
                assertEquals(Effect.NONE, result.effect)
                assertEquals(0, count.get())
                assertFalse(cold.store.privateImages.hasAttempt(original.input.sessionId, original.callId))
                cold.owner.send("draw a new cat").join()
                await { cold.owner.state.value.records.values.any { it.pending != null } }
                val next = cold.owner.state.value.records.values.single { it.pending != null }
                assertNotEquals(original.input.runId, next.input.runId)
                cold.owner.decide(next.callId, next.pending!!.id, Decision.ALLOW_ONCE).join()
                await { !cold.owner.state.value.active }
                assertEquals(1, count.get())
                assertEquals(IMAGE_COMPLETE, cold.host.ledger.get(next.callId)!!.result!!.text)
                assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            } finally { cold.close() }
        } finally { root.deleteRecursively() }
    }

    private fun acceptTestImage(bytes: ByteArray) = ValidatedUserImage("image/png", 2, 2, bytes)
    private fun visionProvider() = object : ModelProvider {
        var requests = 0
        override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
        override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
            emit(Events.started("vision-${requests++}", "model"))
            emit(Events.text(0, "ok")); emit(Events.end(0))
            emit(Events.finished(FinishReason.STOP))
        }
    }
    private suspend fun fixture(root: File, count: AtomicInteger, provider: ModelProvider, hostTool: Tool = tool(count),
        presence: ChatTaskPresence = ChatTaskPresence.None, budget: PluginExecutionBudget = PluginExecutionBudget(),
        imageTool: ((InvocationStore) -> Tool)? = null,
        snapshot: () -> OpenAiRunSnapshot = { OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v1", "model"), ApiKeyCredentials("test-key")) },
        userImageValidator: (ByteArray) -> ValidatedUserImage = ::validateAndDecodeAndroidUserImage,
        fault: (SqliteWriteStage) -> Unit = {}): Fixture {
        val store = AndroidAgentStore.open(root, BundledSQLiteDriver(), fault)
        val host = HostToolBridgeRoot(budget, hostTool).initialize(imageTool = imageTool) { store }
        return Fixture(store, budget, host, provider, ImageAttachments(store.privateImages), presence, snapshot, userImageValidator)
    }
    private inner class Fixture(val store: AndroidAgentStore, val budget: PluginExecutionBudget,
        val host: HostToolExecutionService, provider: ModelProvider, images: ImageAttachments,
        presence: ChatTaskPresence = ChatTaskPresence.None,
        snapshot: () -> OpenAiRunSnapshot,
        userImageValidator: (ByteArray) -> ValidatedUserImage = ::validateAndDecodeAndroidUserImage) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failures = mutableListOf<Throwable>()
        val owner = ChatSessionOwner(scope, { host }, snapshot,
            { synchronized(failures) { failures += it } }, { ModelTarget("test", provider, Credentials { "test-key" }) },
            imageAttachments = images, presence = presence, userImageValidator = userImageValidator)
        suspend fun close() {
            owner.stop().join()
            await { !owner.state.value.active }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            budget.close(); store.close()
        }
    }
    private fun provider(tool: Boolean = true, streaming: CompletableDeferred<Unit>? = null, calls: Int = 1) = object : ModelProvider {
        var requests = 0
        override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
        override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
            emit(Events.started("response-${requests++}", "model"))
            if (tool && requests == 1) {
                repeat(calls) { index ->
                    emit(Events.toolStart(index, "wire-$index", "host_clock")); emit(Events.toolDelta(index, "{}")); emit(Events.end(index))
                }
                emit(Events.finished(FinishReason.TOOL_CALLS))
            } else {
                emit(Events.text(0, "hello")); streaming?.await(); emit(Events.text(0, " world")); emit(Events.end(0))
                emit(Events.finished(FinishReason.STOP))
            }
        }
    }
    private class RecordingPresence : ChatTaskPresence {
        val events = java.util.concurrent.CopyOnWriteArrayList<Pair<String, ChatTaskProgress?>>()
        var failure = false
        var admitted: CompletableDeferred<Unit>? = null
        override suspend fun update(executionId: String, progress: ChatTaskProgress) {
            events += executionId to progress
            if (failure) throw ChatTaskPresenceException(IllegalStateException("service rejected"))
            admitted?.await()
        }
        override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) {
            events += executionId to waiting
        }
    }

    @Test fun foregroundAdmissionPrecedesModelAndCompletionStopsPresence() = runBlocking {
        val root = Files.createTempDirectory("chat83-").toFile()
        val presence = RecordingPresence().also { it.admitted = CompletableDeferred() }
        val model = provider(false)
        val fixture = fixture(root, AtomicInteger(), model, presence = presence)
        try {
            fixture.owner.open().join(); fixture.owner.send("goal").join()
            await { presence.events.isNotEmpty() }
            assertEquals(0, model.requests)
            presence.admitted!!.complete(Unit)
            await { !fixture.owner.state.value.active }
            assertEquals(1, model.requests)
            assertEquals(ChatTaskProgress.Model, presence.events.first().second)
            assertNull(presence.events.last().second)
            assertEquals(1, presence.events.map { it.first }.distinct().size)
        } finally { fixture.close(); root.deleteRecursively() }
    }

    @Test fun foregroundAdmissionFailureNeverSubmitsModelAndStopsPresence() = runBlocking {
        val root = Files.createTempDirectory("chat83-").toFile()
        val presence = RecordingPresence().also { it.failure = true }
        val model = provider(false)
        val fixture = fixture(root, AtomicInteger(), model, presence = presence)
        try {
            fixture.owner.open().join(); fixture.owner.send("goal").join()
            await { !fixture.owner.state.value.active }
            assertEquals(0, model.requests)
            assertEquals(R.string.chat_background_failed, fixture.owner.state.value.error)
            assertEquals(RunState.PAUSED, fixture.owner.state.value.result?.state)
            assertNull(presence.events.last().second)
            assertNotNull(fixture.owner.state.value.recovery)
            assertTrue(ChatRecoveryAction.CONTINUE in recoveryActions(fixture.owner.state.value))
        } finally { fixture.close(); root.deleteRecursively() }
    }

    @Test fun firstAdmissionFailureRetainsTrustedIdentityAcrossColdOpen() = runBlocking {
        val root = Files.createTempDirectory("chat-first-admission").toFile()
        val model = provider(false)
        val first = fixture(root, AtomicInteger(), model, presence = RecordingPresence().also { it.failure = true })
        val token: ChatRecoveryToken
        try {
            first.owner.open().join(); first.owner.send("original goal").join()
            await { !first.owner.state.value.active }
            token = requireNotNull(first.owner.state.value.recovery)
            assertFalse(token.identity.unattended)
            assertEquals(0, model.requests)
        } finally { first.close() }
        val admission = CompletableDeferred<Unit>()
        val presence = RecordingPresence().also { it.admitted = admission }
        val cold = fixture(root, AtomicInteger(), model, presence = presence)
        try {
            cold.owner.open().join()
            assertEquals(token, cold.owner.state.value.recovery)
            assertEquals(0, model.requests)
            cold.owner.recover(token.copy(identity = token.identity.copy(runId = "forged")), ChatRecoveryAction.CONTINUE).join()
            assertEquals(0, model.requests)
            assertEquals(token, cold.owner.state.value.recovery)
            cold.owner.recover(token, ChatRecoveryAction.CONTINUE).join()
            await { presence.events.isNotEmpty() }
            assertEquals(0, model.requests)
            admission.complete(Unit)
            await { !cold.owner.state.value.active }
            assertEquals(1, model.requests)
            assertEquals(RunState.COMPLETED, cold.owner.state.value.result?.state)
            assertEquals(1, cold.owner.state.value.messages.count { it.role == Role.USER })
            assertEquals("original goal", (cold.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
        } finally { admission.complete(Unit); cold.close(); root.deleteRecursively() }
    }

    @Test fun parallelInternalApprovalAndApprovedToolWaitForDelayedAdmission() = runBlocking {
        parallelApprovalAdmission(false)
    }

    @Test fun acceptedInputOnRetainedChainOffersColdContinuationWithoutToolReplay() = runBlocking {
        val root = Files.createTempDirectory("chat-retained-chain-input").toFile()
        val calls = AtomicInteger()
        val model = provider()
        val uncertainRead = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "uncertain read", schema, risk = ToolRisk.READ)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                calls.incrementAndGet()
                return ToolResult("uncertain result retained", effect = ToolEffect.UNKNOWN)
            }
        }
        val first = fixture(root, calls, model, hostTool = uncertainRead)
        val session: String
        val original: InvocationRecord
        try {
            first.owner.open().join(); first.owner.send("first goal").join()
            await { !first.owner.state.value.active }
            val state = first.owner.state.value
            session = requireNotNull(state.sessionId)
            original = state.records.values.single()
            assertEquals(RunState.COMPLETED, state.result?.state)
            assertEquals(Effect.UNKNOWN, original.result!!.effect)
            assertEquals(InstanceState.EXIT_CONFIRMED, original.instance!!.state)
        } finally { first.close() }
        try {
            // Crash boundary: send's accepted input is durable, but foreground admission
            // and creation of the next run have not happened yet.
            AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
                store.openHistory(session).use { disk ->
                    assertNotNull(disk.chatRecoveryIdentity())
                    disk.appendChatInput(disk.revision(), message(Role.USER, textPart("second goal")),
                        RunIdentity(disk.chatTaskChainId(), session, "accepted-second-run"))
                }
            }
            val cold = fixture(root, calls, model, hostTool = uncertainRead)
            try {
                cold.owner.open().join()
                val state = cold.owner.state.value
                assertEquals(RunState.PAUSED, state.result?.state)
                assertEquals(2, model.requests)
                assertEquals(1, calls.get())
                assertEquals(original, cold.host.ledger.get(original.callId))
                assertEquals(2, state.messages.count { it.role == Role.USER })
                assertTrue(ChatRecoveryAction.CONTINUE in recoveryActions(state))
                cold.owner.recover(requireNotNull(state.recovery), ChatRecoveryAction.CONTINUE).join()
                await { !cold.owner.state.value.active }
                assertEquals(3, model.requests)
                assertEquals(1, calls.get())
                assertEquals(original, cold.host.ledger.get(original.callId))
                assertEquals(2, cold.owner.state.value.messages.count { it.role == Role.USER })
                assertEquals(RunState.COMPLETED, cold.owner.state.value.result?.state)
            } finally { cold.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun parallelInternalApprovalAndApprovedToolRejectFailedAdmission() = runBlocking {
        parallelApprovalAdmission(true)
    }

    private suspend fun parallelApprovalAdmission(reject: Boolean) {
        val root = Files.createTempDirectory("chat-parallel-admission").toFile()
        val restart = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Boolean>()
        val release = CompletableDeferred<Unit>()
        val read = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "internal approval", schema, risk = ToolRisk.READ, parallelSafe = true)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                env.requestApproval("internal write", ToolRisk.WRITE)
                return ToolResult("done", effect = ToolEffect.NONE)
            }
        }
        val presence = object : ChatTaskPresence {
            @Volatile var protected = false
            @Volatile var waiting = false
            @Volatile var restarting = false
            @Volatile var rejectRestart = reject
            @Volatile var approved = false
            override suspend fun update(executionId: String, progress: ChatTaskProgress) {
                if (progress == ChatTaskProgress.Approval) {
                    protected = false
                    waiting = true
                    if (approved) withTimeout(5_000) { written.await() }
                } else {
                    if (!protected && waiting) {
                        restarting = true
                        restart.await()
                        if (rejectRestart) throw ChatTaskPresenceException(IllegalStateException("restart rejected"))
                    }
                    protected = true
                }
            }
            override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) { protected = false }
        }
        val write = object : Tool {
            override val id = "write"
            override val spec = ToolSpec(id, "approved write", schema, risk = ToolRisk.WRITE, parallelSafe = true)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                written.complete(presence.protected)
                release.await()
                return ToolResult("done", effect = ToolEffect.APPLIED)
            }
        }
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                emit(Events.started("response", "model"))
                for ((index, name) in listOf("host_clock", "host_write").withIndex()) {
                    emit(Events.toolStart(index, "wire-$index", name)); emit(Events.toolDelta(index, "{}")); emit(Events.end(index))
                }
                emit(Events.finished(FinishReason.TOOL_CALLS))
            }
        }
        val fixture = fixture(root, AtomicInteger(), model, hostTool = read, presence = presence)
        try {
            fixture.host.catalog.registerHost("host/write", "1", write)
            val owner = fixture.owner
            owner.open().join(); owner.send("goal").join()
            await { owner.state.value.records.values.count { it.pending != null } == 2 && presence.waiting }
            val external = owner.state.value.records.values.single { it.pending?.internal == false }
            val decision = owner.decide(external.callId, external.pending!!.id, Decision.ALLOW_ONCE)
            await { presence.restarting }
            assertFalse(written.isCompleted)
            assertEquals(external.pending, fixture.host.ledger.get(external.callId)!!.pending)
            presence.approved = !reject
            restart.complete(Unit)
            decision.join()
            if (reject) {
                assertFalse(written.isCompleted)
                assertEquals(external.pending, fixture.host.ledger.get(external.callId)!!.pending)
                presence.rejectRestart = false
                presence.approved = true
                owner.decide(external.callId, external.pending!!.id, Decision.ALLOW_ONCE).join()
            }
            assertTrue("approved tool ran without foreground acknowledgement", withTimeout(5_000) { written.await() })
            assertTrue(presence.protected)
            assertTrue(fixture.host.ledger.get(external.callId)!!.receipts.any { it.decision == Decision.ALLOW_ONCE })
        } finally {
            presence.approved = false
            restart.complete(Unit); release.complete(Unit)
            fixture.close(); root.deleteRecursively()
        }
    }

    @Test fun approvalStopsForegroundAndResumesBeforeToolExecution() = runBlocking {
        val root = Files.createTempDirectory("chat83-").toFile()
        val presence = RecordingPresence()
        val count = AtomicInteger()
        val fixture = fixture(root, count, provider(), presence = presence)
        try {
            fixture.owner.open().join(); fixture.owner.send("goal").join()
            await { presence.events.any { it.second == ChatTaskProgress.Approval } }
            assertEquals(0, count.get())
            val record = fixture.owner.state.value.records.values.single()
            val before = presence.events.size
            fixture.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !fixture.owner.state.value.active }
            assertEquals(ChatTaskProgress.Model, presence.events[before].second)
            assertEquals(1, count.get())
            assertNull(presence.events.last().second)
        } finally { fixture.close(); root.deleteRecursively() }
    }

    @Test fun failedForegroundRestartDoesNotApproveOrExecuteAndCanRetry() = runBlocking {
        val root = Files.createTempDirectory("chat83-").toFile()
        val presence = RecordingPresence()
        val count = AtomicInteger()
        val fixture = fixture(root, count, provider(), presence = presence)
        try {
            fixture.owner.open().join(); fixture.owner.send("goal").join()
            await { presence.events.any { it.second == ChatTaskProgress.Approval } }
            val record = fixture.owner.state.value.records.values.single()
            presence.failure = true
            fixture.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            assertEquals(0, count.get())
            assertEquals(record.pending, fixture.host.ledger.get(record.callId)!!.pending)
            assertTrue(fixture.host.ledger.get(record.callId)!!.receipts.isEmpty())
            presence.failure = false
            fixture.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !fixture.owner.state.value.active }
            assertEquals(1, count.get())
            assertNull(presence.events.last().second)
        } finally { fixture.close(); root.deleteRecursively() }
    }

    @Test fun internalApprovalSuspendsPresenceAndResumesBeforeAuthorizedWork() = runBlocking {
        for (decision in listOf(Decision.ALLOW_ONCE, Decision.DENY)) {
            val root = Files.createTempDirectory("chat-internal-presence").toFile()
            val presence = RecordingPresence()
            val writes = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val read = object : Tool {
                override val id = "read"
                override val spec = ToolSpec(id, "read with internal write", schema, risk = ToolRisk.READ)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    val allowed = env.requestApproval("write step", ToolRisk.WRITE)
                    if (allowed) writes.incrementAndGet()
                    release.await()
                    return ToolResult("done", effect = if (allowed) ToolEffect.APPLIED else ToolEffect.NONE)
                }
            }
            val fixture = fixture(root, AtomicInteger(), provider(), hostTool = read, presence = presence)
            try {
                val owner = fixture.owner
                owner.open().join(); owner.send("goal").join()
                await { owner.state.value.records.values.any { it.pending?.internal == true } }
                owner.latest().join()
                val record = owner.state.value.records.values.single()
                assertEquals(Status.RUNNING, record.state)
                assertEquals(InstanceState.UNCONFIRMED, record.instance!!.state)
                assertEquals(ChatTaskProgress.Approval, presence.events.last().second)
                assertEquals(0, writes.get())
                val before = presence.events.size
                owner.decide(record.callId, record.pending!!.id, decision).join()
                await { presence.events.drop(before).any { it.second is ChatTaskProgress.Tool } }
                assertEquals(ChatTaskProgress.Model,
                    presence.events.drop(before).first { it.second != ChatTaskProgress.Approval }.second)
                release.complete(Unit)
                await { !owner.state.value.active }
                assertEquals(if (decision == Decision.ALLOW_ONCE) 1 else 0, writes.get())
                assertNull(presence.events.last().second)
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            } finally { release.complete(Unit); fixture.close(); root.deleteRecursively() }
        }
    }

    @Test fun withdrawnInternalApprovalWaitsBeforeSameToolContinues() = runBlocking {
        withdrawnApprovalToolContinuation(false)
    }

    @Test fun withdrawnInternalApprovalFailureRetainsResultsAndWorkerLease() = runBlocking {
        withdrawnApprovalToolContinuation(true)
    }

    private suspend fun withdrawnApprovalToolContinuation(reject: Boolean) {
        val root = Files.createTempDirectory("chat-withdrawn-tool").toFile()
        val withdraw = CompletableDeferred<Unit>()
        val restartEntered = CompletableDeferred<Unit>()
        val restart = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val continued = AtomicInteger()
        val presence = object : ChatTaskPresence {
            @Volatile var protected = false
            @Volatile var suspended = false
            override suspend fun update(executionId: String, progress: ChatTaskProgress) {
                if (progress == ChatTaskProgress.Approval) {
                    protected = false
                    suspended = true
                } else {
                    if (!protected && suspended) {
                        restartEntered.complete(Unit)
                        restart.await()
                        if (reject) throw ChatTaskPresenceException(IllegalStateException("tool restart rejected"))
                    }
                    protected = true
                }
            }
            override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) { protected = false }
        }
        val read = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "continued read", schema, risk = ToolRisk.READ, exclusiveResource = "continued-read")
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = coroutineScope {
                if (entered.incrementAndGet() == 1) return@coroutineScope ToolResult("first result retained", effect = ToolEffect.NONE)
                try {
                    val approval = launch { env.requestApproval("optional write", ToolRisk.WRITE) }
                    withdraw.await()
                    approval.cancelAndJoin()
                    continued.incrementAndGet()
                    assertTrue("tool continued without foreground acknowledgement", presence.protected)
                    ToolResult("continued read result", effect = ToolEffect.NONE)
                } finally {
                    withContext(NonCancellable) { cleanupEntered.complete(Unit); cleanup.await() }
                }
            }
        }
        val model = provider(calls = 2)
        val fixture = fixture(root, AtomicInteger(), model, hostTool = read, presence = presence)
        var closed = false
        fun journalBytes() = BundledSQLiteDriver().open(File(root, AgentDatabase.FILE_NAME).path).use { connection ->
            connection.prepare("SELECT name, ordinal, hex(bytes) FROM history_chunks WHERE name LIKE 'journal/%' ORDER BY name, ordinal").use { query ->
                buildList { while (query.step()) add("${query.getText(0)}:${query.getLong(1)}:${query.getText(2)}") }
            }
        }
        try {
            val owner = fixture.owner
            owner.open().join(); owner.send("original goal").join()
            await { presence.suspended && owner.state.value.records.values.any { it.pending?.internal == true } }
            val waiting = owner.state.value.records.values.single { it.pending?.internal == true }
            val originalJournal = journalBytes()
            assertTrue(originalJournal.isNotEmpty())
            withdraw.complete(Unit)
            withTimeout(10_000) { restartEntered.await() }
            delay(100)
            assertEquals(0, continued.get())
            assertEquals(1, model.requests)
            assertEquals(originalJournal, journalBytes())
            assertEquals(waiting.instance, fixture.host.ledger.get(waiting.callId)!!.instance)
            assertTrue(fixture.budget.occupiedSlots() > 0)
            restart.complete(Unit)
            withTimeout(10_000) { cleanupEntered.await() }
            if (reject) {
                await { !owner.state.value.active }
                assertEquals(0, continued.get())
                assertEquals(1, model.requests)
                assertEquals(2, entered.get())
                assertEquals(originalJournal, journalBytes())
                assertEquals(RunState.PAUSED, owner.state.value.result?.state)
                assertEquals(R.string.chat_background_failed, owner.state.value.error)
                val stopped = fixture.host.ledger.get(waiting.callId)!!
                assertEquals(Status.CANCELLED, stopped.state)
                assertEquals(waiting.instance, stopped.instance)
                assertTrue(fixture.budget.occupiedSlots() > 0)
                assertTrue(owner.state.value.messages.any { message -> message.parts.any {
                    it is ContentPart.ToolResult && it.text.contains("first result retained")
                } })
            } else assertEquals(1, continued.get())
            cleanup.complete(Unit)
            fixture.host.awaitObservedExit(waiting.callId)
            await { !owner.state.value.active }
            assertEquals(InstanceState.EXIT_CONFIRMED, fixture.host.ledger.get(waiting.callId)!!.instance!!.state)
            assertEquals(if (reject) 1 else 2, model.requests)
            assertEquals(2, entered.get())
            if (!reject) assertEquals(RunState.COMPLETED, owner.state.value.result?.state)
            else {
                fixture.close(); closed = true
                val cold = fixture(root, AtomicInteger(), model, hostTool = read)
                try {
                    cold.owner.open().join()
                    assertEquals(1, model.requests)
                    assertEquals(2, entered.get())
                    assertEquals(originalJournal, journalBytes())
                    assertEquals(Status.CANCELLED, cold.owner.state.value.records.getValue(waiting.callId).state)
                    assertTrue(cold.owner.state.value.messages.any { message -> message.parts.any {
                        it is ContentPart.ToolResult && it.text.contains("first result retained")
                    } })
                } finally { cold.close() }
            }
        } finally {
            withdraw.complete(Unit); restart.complete(Unit); cleanup.complete(Unit)
            if (!closed) fixture.close()
            root.deleteRecursively()
        }
    }

    @Test fun withdrawnInternalApprovalWaitsForModelAdmission() = runBlocking {
        withdrawnApprovalModelAdmission(false)
    }

    @Test fun withdrawnInternalApprovalRejectsModelAndRetainsColdExplicitRetry() = runBlocking {
        withdrawnApprovalModelAdmission(true)
    }

    private suspend fun withdrawnApprovalModelAdmission(reject: Boolean) {
        val root = Files.createTempDirectory("chat-withdrawn-admission").toFile()
        fun submittedJournal() = BundledSQLiteDriver().open(File(root, AgentDatabase.FILE_NAME).path).use { connection ->
            connection.prepare("SELECT name, ordinal, hex(bytes) FROM history_chunks WHERE name LIKE 'journal/%' ORDER BY name, ordinal").use { query ->
                buildList {
                    while (query.step()) add("${query.getText(0)}:${query.getLong(1)}:${query.getText(2)}")
                }
            }
        }
        val withdraw = CompletableDeferred<Unit>()
        val restartEntered = CompletableDeferred<Unit>()
        val restart = CompletableDeferred<Unit>()
        val toolCalls = AtomicInteger()
        val requests = AtomicInteger()
        val protectedRequests = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val presence = object : ChatTaskPresence {
            @Volatile var protected = false
            @Volatile var suspended = false
            override suspend fun update(executionId: String, progress: ChatTaskProgress) {
                if (progress == ChatTaskProgress.Approval) {
                    protected = false
                    suspended = true
                } else {
                    if (suspended && progress == ChatTaskProgress.Model) {
                        restartEntered.complete(Unit)
                        restart.await()
                        if (reject) throw ChatTaskPresenceException(IllegalStateException("restart rejected"))
                    }
                    protected = true
                }
            }
            override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) { protected = false }
        }
        val read = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "withdrawn internal approval", schema, risk = ToolRisk.READ)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = coroutineScope {
                toolCalls.incrementAndGet()
                val approval = launch { env.requestApproval("optional write", ToolRisk.WRITE) }
                withdraw.await()
                approval.cancelAndJoin()
                ToolResult("read result retained", effect = ToolEffect.NONE)
            }
        }
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                protectedRequests += presence.protected
                val count = requests.incrementAndGet()
                emit(Events.started("response-$count", "model"))
                if (count == 1) {
                    emit(Events.toolStart(0, "wire", "host_clock")); emit(Events.toolDelta(0, "{}")); emit(Events.end(0))
                    emit(Events.finished(FinishReason.TOOL_CALLS))
                } else {
                    assertTrue(request.messages.any { message -> message.parts.any {
                        it is ContentPart.ToolResult && it.text.contains("read result retained")
                    } })
                    restart.await()
                    emit(Events.text(0, "complete")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
        }
        val fixture = fixture(root, toolCalls, model, hostTool = read, presence = presence)
        var closed = false
        try {
            val owner = fixture.owner
            owner.open().join(); owner.send("original goal").join()
            await { owner.state.value.records.values.any { it.pending?.internal == true } && presence.suspended }
            val originalJournal = submittedJournal()
            assertTrue(originalJournal.isNotEmpty())
            withdraw.complete(Unit)
            withTimeout(10_000) { restartEntered.await() }
            delay(100)
            assertEquals(1, requests.get())
            assertEquals(listOf(true), protectedRequests.toList())
            assertEquals(originalJournal, submittedJournal())
            restart.complete(Unit)
            await { !owner.state.value.active }
            assertEquals(if (reject) 1 else 2, requests.get())
            assertEquals(1, toolCalls.get())
            assertEquals(Status.SUCCEEDED, owner.state.value.records.values.single().state)
            assertTrue(owner.state.value.messages.any { message -> message.parts.any {
                it is ContentPart.ToolResult && it.text.contains("read result retained")
            } })
            assertTrue(owner.state.value.journal.isEmpty())
            if (reject) {
                assertEquals(originalJournal, submittedJournal())
                assertEquals(R.string.chat_background_failed, owner.state.value.error)
                assertEquals(RunState.PAUSED, owner.state.value.result?.state)
                val token = requireNotNull(owner.state.value.recovery)
                assertTrue(ChatRecoveryAction.CONTINUE in recoveryActions(owner.state.value))
                fixture.close(); closed = true
                val cold = fixture(root, toolCalls, model, hostTool = read)
                try {
                    cold.owner.open().join()
                    assertEquals(token, cold.owner.state.value.recovery)
                    assertEquals(1, requests.get())
                    assertEquals(originalJournal, submittedJournal())
                    assertTrue(cold.owner.state.value.journal.isEmpty())
                    cold.owner.recover(token, ChatRecoveryAction.CONTINUE).join()
                    await { !cold.owner.state.value.active }
                    assertEquals(2, requests.get())
                    assertTrue(submittedJournal().containsAll(originalJournal))
                    assertEquals(1, toolCalls.get())
                    assertEquals(1, cold.owner.state.value.messages.count { it.role == Role.USER })
                    assertEquals(RunState.COMPLETED, cold.owner.state.value.result?.state)
                } finally { cold.close() }
            } else {
                assertEquals(listOf(true, true), protectedRequests.toList())
                assertEquals(RunState.COMPLETED, owner.state.value.result?.state)
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            }
        } finally {
            withdraw.complete(Unit); restart.complete(Unit)
            if (!closed) fixture.close()
            root.deleteRecursively()
        }
    }

    @Test fun internalApprovalKeepsForegroundUntilOtherParallelWorkerStops() = runBlocking {
        val root = Files.createTempDirectory("chat-parallel-presence").toFile()
        val presence = RecordingPresence()
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val read = object : Tool {
            override val id = "read"
            override val spec = ToolSpec(id, "parallel read", schema, risk = ToolRisk.READ, parallelSafe = true)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                if (entered.incrementAndGet() == 1) release.await()
                else env.requestApproval("write step", ToolRisk.WRITE)
                return ToolResult("done", effect = ToolEffect.NONE)
            }
        }
        val fixture = fixture(root, AtomicInteger(), provider(calls = 2), hostTool = read, presence = presence)
        try {
            val owner = fixture.owner
            owner.open().join(); owner.send("goal").join()
            await { owner.state.value.records.values.any { it.pending?.internal == true } }
            owner.latest().join()
            assertTrue(presence.events.last().second is ChatTaskProgress.Tool)
            val working = owner.state.value.records.values.single { it.state == Status.RUNNING && it.pending == null }
            release.complete(Unit)
            fixture.host.awaitObservedExit(working.callId)
            owner.latest().join()
            assertEquals(ChatTaskProgress.Approval, presence.events.last().second)
            val waiting = owner.state.value.records.values.single { it.pending != null }
            assertEquals(InstanceState.UNCONFIRMED, waiting.instance!!.state)
            owner.decide(waiting.callId, waiting.pending!!.id, Decision.DENY).join()
            await { !owner.state.value.active }
            assertNull(presence.events.last().second)
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        } finally { release.complete(Unit); fixture.close(); root.deleteRecursively() }
    }

    @Test fun staleNotificationCannotCancelLaterExecutionAndCurrentStopSettles() = runBlocking {
        val root = Files.createTempDirectory("chat83-").toFile()
        val presence = RecordingPresence()
        val hold = CompletableDeferred<Unit>()
        val fixture = fixture(root, AtomicInteger(), provider(false, hold), presence = presence)
        try {
            fixture.owner.open().join(); fixture.owner.send("goal").join()
            await { fixture.owner.previewText().isNotEmpty() }
            fixture.owner.stop("old-execution").join()
            assertTrue(fixture.owner.state.value.active)
            fixture.owner.stop(fixture.owner.state.value.executionId!!).join()
            await { !fixture.owner.state.value.active }
            assertEquals(R.string.chat_stopped, fixture.owner.state.value.notice)
            assertNull(presence.events.last().second)
        } finally { fixture.close(); root.deleteRecursively() }
    }

    @Test fun modelFailureAndContextPauseBothStopForegroundPresence() = runBlocking {
        for (reason in listOf(FinishReason.CONTENT_FILTER, FinishReason.CONTEXT_EXCEEDED)) {
            val root = Files.createTempDirectory("chat83-").toFile()
            val presence = RecordingPresence()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    emit(Events.started("response", "model")); emit(Events.finished(reason))
                }
            }
            val fixture = fixture(root, AtomicInteger(), model, presence = presence)
            try {
                fixture.owner.open().join(); fixture.owner.send("goal").join()
                await { !fixture.owner.state.value.active }
                assertNotEquals(R.string.chat_complete, fixture.owner.state.value.notice)
                assertEquals(ChatTaskProgress.Model, presence.events.first().second)
                assertNull(presence.events.last().second)
            } finally { fixture.close(); root.deleteRecursively() }
        }
    }

    @Test fun runningToolReportsProgressAndCancellationDoesNotReleaseWorkerResourcesEarly() = runBlocking {
        val root = Files.createTempDirectory("chat83-").toFile()
        val presence = RecordingPresence()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val held = object : Tool {
            override val id = "held"
            override val spec = ToolSpec("held", "test", schema, risk = ToolRisk.WRITE, timeoutMs = 5_000)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = withContext(NonCancellable) {
                count.incrementAndGet(); release.await(); ToolResult("done", effect = ToolEffect.APPLIED)
            }
        }
        val fixture = fixture(root, count, provider(), hostTool = held, presence = presence)
        try {
            fixture.owner.open().join(); fixture.owner.send("goal").join()
            await { presence.events.any { it.second == ChatTaskProgress.Approval } }
            val record = fixture.owner.state.value.records.values.single()
            fixture.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            await { presence.events.any { it.second is ChatTaskProgress.Tool } }
            fixture.owner.stop(fixture.owner.state.value.executionId!!).join()
            await { !fixture.owner.state.value.active }
            assertEquals(InstanceState.UNCONFIRMED, fixture.host.ledger.get(record.callId)!!.instance!!.state)
            assertEquals(1, count.get())
            release.complete(Unit)
            fixture.host.awaitObservedExit(record.callId)
        } finally { release.complete(Unit); fixture.close(); root.deleteRecursively() }
    }

    @Test fun completedQuestionsHaveIndependentBudgetsInOnePersistentSession() = runBlocking {
        val root = Files.createTempDirectory("chat-budgets").toFile()
        val requests = AtomicInteger()
        val model = countingProvider(requests) { FinishReason.STOP }
        try {
            var f = fixture(root, AtomicInteger(), model)
            f.owner.open().join()
            val session = f.owner.state.value.sessionId
            repeat(51) { index ->
                if (index == 50) {
                    f.close()
                    f = fixture(root, AtomicInteger(), model)
                    f.owner.open().join()
                }
                f.owner.send("question $index").join()
                await { !f.owner.state.value.active }
                assertEquals(R.string.chat_complete, f.owner.state.value.notice)
                assertEquals(index + 1, requests.get())
            }
            assertEquals(session, f.owner.state.value.sessionId)
            assertTrue(f.failures.toString(), f.failures.isEmpty())
            f.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun refusedFilteredAndInterruptedRepliesSurviveColdOpenButNeverReplay() = runBlocking {
        for (finish in listOf(FinishReason.REFUSAL, FinishReason.CONTENT_FILTER, null)) {
            val root = Files.createTempDirectory("chat-retained").toFile()
            val requests = mutableListOf<ChatRequest>()
            val allowSuccess = java.util.concurrent.atomic.AtomicBoolean(false)
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request
                    emit(Events.started("retained-${requests.size}", "model"))
                    if (!allowSuccess.get()) {
                        emit(Events.text(0, "private non-replayable reply"))
                        if (finish == null) throw java.io.IOException("connection interrupted")
                        emit(Events.end(0)); emit(Events.finished(finish))
                    } else {
                        emit(Events.text(0, "safe answer")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, AtomicInteger(), model)
                first.owner.open().join(); first.owner.send("question").join(); await { !first.owner.state.value.active }
                val reply = first.owner.state.value.journal.first()
                assertEquals("private non-replayable reply", (reply.partial.parts.single() as ContentPart.Text).text)
                assertEquals(finish?.name ?: "NETWORK", reply.reason)
                assertEquals(1, first.owner.state.value.messages.size)
                val session = first.owner.state.value.sessionId
                first.close()
                val cold = fixture(root, AtomicInteger(), model)
                cold.owner.open().join()
                assertEquals(session, cold.owner.state.value.sessionId)
                assertEquals(reply.requestId, cold.owner.state.value.journal.first().requestId)
                allowSuccess.set(true)
                cold.owner.send("next question").join(); await { !cold.owner.state.value.active }
                assertEquals(R.string.chat_complete, cold.owner.state.value.notice)
                assertFalse(requests.last().messages.any { message -> message.parts.any { part ->
                    part is ContentPart.Text && part.text.contains("non-replayable")
                } })
                assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                cold.close()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun oversizedHistoryOffersExplicitBoundedRecoveryWithoutLosingOriginals() = runBlocking {
        val root = Files.createTempDirectory("chat-context").toFile()
        val requests = mutableListOf<ChatRequest>()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                requests += request
                emit(Events.started("bounded-${requests.size}", "model"))
                emit(Events.text(0, if (request.tools.isEmpty()) "Prior conversation summary" else "final answer"))
                emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
            }
        }
        try {
            val seed = fixture(root, AtomicInteger(), model)
            seed.store.createHistory("session").use { disk ->
                repeat(34) { disk.append(disk.revision(), listOf(message(Role.USER, textPart("u".repeat(32_000))),
                    message(Role.ASSISTANT, textPart("a".repeat(32_000))))) }
            }
            seed.owner.open().join(); seed.owner.send("Continue this conversation").join(); await { !seed.owner.state.value.active }
            assertTrue(requests.isEmpty())
            assertEquals(R.string.chat_pause_context, chatOutcome(seed.owner.state.value.result!!))
            assertEquals("BUDGET", seed.owner.state.value.checkpoint!!.pauseClass)
            seed.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.owner.open().join()
            assertEquals("BUDGET", cold.owner.state.value.checkpoint!!.pauseClass)
            assertTrue(requests.isEmpty())
            val selection = requireNotNull(cold.owner.state.value.recovery)
            cold.owner.recover(selection, ChatRecoveryAction.SUMMARIZE, 1_000_000, confirmed = true).join()
            await { !cold.owner.state.value.active }
            assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
            assertTrue(requests.any { it.tools.isEmpty() })
            assertTrue(requests.last().tools.isNotEmpty())
            assertTrue(requests.last().messages.size < 69)
            assertEquals(0L, cold.owner.state.value.acceptedMessages)
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            cold.close()
            val verify = fixture(root, AtomicInteger(), model)
            verify.store.openHistory("session").use { disk ->
                val stored = disk.snapshot().messages
                assertEquals(70, stored.size)
                assertEquals("u".repeat(32_000), (stored.first().parts.single() as ContentPart.Text).text)
                assertEquals("a".repeat(32_000), (stored[67].parts.single() as ContentPart.Text).text)
                assertTrue(disk.chatResult()!!.steps > 1)
            }
            verify.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun activeSummaryDefersRecoveryInspectionUntilStopped() = runBlocking {
        val root = Files.createTempDirectory("chat-active-checkpoint").toFile()
        val entered = CompletableDeferred<Unit>()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                assertTrue(request.tools.isEmpty())
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val fixture = fixture(root, AtomicInteger(), model)
        try {
            fixture.store.createHistory("session").use { disk ->
                repeat(34) {
                    disk.append(disk.revision(), listOf(message(Role.USER, textPart("u".repeat(32_000))),
                        message(Role.ASSISTANT, textPart("a".repeat(32_000)))))
                }
            }
            fixture.owner.open().join()
            fixture.owner.send("Continue this conversation").join()
            await { !fixture.owner.state.value.active }
            assertEquals("BUDGET", fixture.owner.state.value.checkpoint!!.pauseClass)
            fixture.owner.resume(summaryWindowTokens = 8192).join()
            withTimeout(10_000) { entered.await() }
            fixture.owner.latest().join()
            assertTrue(fixture.owner.state.value.active)
            assertNull(fixture.owner.state.value.checkpoint)
            assertNull(fixture.owner.state.value.reconciliation)
            assertNull(fixture.owner.state.value.recovery)
            fixture.owner.stop().join()
            await { !fixture.owner.state.value.active }
            assertNotNull(fixture.owner.state.value.checkpoint)
            assertNotNull(fixture.owner.state.value.reconciliation)
            assertNotNull(fixture.owner.state.value.recovery)
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        } finally {
            fixture.close()
            root.deleteRecursively()
        }
    }

    @Test fun savedContextCheckpointRequiresSelectionAndPreservesTaskBudget() = runBlocking {
        val root = Files.createTempDirectory("chat-checkpoint").toFile()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("checkpoint-$count", "model")); emit(Events.text(0, "bounded answer")); emit(Events.end(0))
                emit(Events.finished(if (request.tools.isNotEmpty() && count <= 2) FinishReason.CONTEXT_EXCEEDED else FinishReason.STOP))
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("unfinished goal").join(); await { !first.owner.state.value.active }
            first.owner.resume(summaryWindowTokens = 8192).join(); await { !first.owner.state.value.active }
            val checkpoint = requireNotNull(first.owner.state.value.checkpoint)
            assertTrue(first.failures.toString(), checkpoint.canContinue)
            val count = requests.get()
            first.close()
            val presence = RecordingPresence().also { it.admitted = CompletableDeferred() }
            val cold = fixture(root, AtomicInteger(), model, presence = presence)
            cold.owner.open().join()
            assertEquals(count, requests.get())
            assertEquals(checkpoint.id, cold.owner.state.value.checkpoint!!.id)
            val selection = requireNotNull(cold.owner.state.value.recovery)
            val retainedSession = cold.owner.state.value.sessionId!!
            cold.owner.deleteCurrentSession().join()
            assertTrue(cold.store.sessionIds().contains(retainedSession))
            assertEquals(selection, cold.owner.state.value.recovery)
            assertEquals(R.string.chat_delete_recovery_required, cold.owner.state.value.error)
            assertEquals(count, requests.get())
            cold.owner.recover(selection, ChatRecoveryAction.CONTINUE_SUMMARY, confirmed = false).join()
            assertEquals(count, requests.get())
            cold.owner.recover(selection, ChatRecoveryAction.CONTINUE_SUMMARY, confirmed = true).join()
            await { presence.events.isNotEmpty() }
            assertEquals(ChatTaskProgress.Model, presence.events.first().second)
            assertEquals(count, requests.get())
            presence.admitted!!.complete(Unit)
            await { !cold.owner.state.value.active }
            assertNull(presence.events.last().second)
            assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
            assertTrue(cold.owner.state.value.result!!.steps > checkpoint.steps)
            assertEquals(1, cold.failures.size)
            cold.owner.recover(selection, ChatRecoveryAction.CONTINUE_SUMMARY, confirmed = true).join()
            assertEquals(count + 1, requests.get())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun largerWindowReadmissionPreservesRawHistoryWithoutSummaryCalls() = runBlocking {
        val root = Files.createTempDirectory("chat-window-readmit").toFile()
        val requests = AtomicInteger()
        val presence = RecordingPresence()
        try {
            val first = fixture(root, AtomicInteger(), countingProvider(requests) { FinishReason.STOP }, presence = presence)
            first.store.createHistory("session").use { disk ->
                disk.saveChatContext(ChatTaskContext("session", "goal", 0, null, 1024))
            }
            first.owner.open().join(); first.owner.send("goal ".repeat(400)).join()
            await { !first.owner.state.value.active }
            assertEquals(0, requests.get())
            assertEquals("BUDGET", first.owner.state.value.checkpoint!!.pauseClass)
            val token = requireNotNull(first.owner.state.value.recovery)
            first.owner.recover(token, ChatRecoveryAction.READMIT, 1024, true).join()
            assertEquals(0, requests.get())
            presence.events.clear()
            presence.admitted = CompletableDeferred()
            first.owner.recover(token, ChatRecoveryAction.READMIT, 8192, true).join()
            await { presence.events.isNotEmpty() }
            assertEquals(ChatTaskProgress.Model, presence.events.first().second)
            assertEquals(0, requests.get())
            presence.admitted!!.complete(Unit)
            await { !first.owner.state.value.active }
            assertNull(presence.events.last().second)
            assertEquals(1, requests.get())
            assertEquals(R.string.chat_complete, first.owner.state.value.notice)
            assertEquals("goal ".repeat(400), (first.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
            first.owner.recover(token, ChatRecoveryAction.READMIT, 8192, true).join()
            assertEquals(1, requests.get())
            first.close()
            val reopened = fixture(root, AtomicInteger(), countingProvider(requests) { FinishReason.STOP })
            reopened.store.openHistory("session").use { disk ->
                assertNull(disk.chatSummaryWindow())
                assertEquals(8192L, disk.chatContextWindow())
            }
            reopened.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun coldUnattendedRunsCannotBePromotedByUiOrDirectResume() = runBlocking {
        for (uncertain in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-unattended-recovery").toFile()
            val requests = AtomicInteger()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests.incrementAndGet()
                    emit(Events.started("uncertain", "model"))
                    throw java.io.IOException("unknown outcome")
                }
            }
            try {
                val seed = fixture(root, AtomicInteger(), model)
                seed.store.createHistory("session").use { disk ->
                    disk.saveChatContext(ChatTaskContext("session", "goal", 0))
                    disk.append(0, listOf(message(Role.USER, textPart("goal"))))
                    val identity = RunIdentity("session", "session", "unattended", true)
                    val definitions = seed.host.catalog.freeze(identity.runId).values.toList()
                    val result = AgentRunLoop(identity, ModelTarget("test", model, Credentials { "test" }),
                        chatRequest("model") {
                            maxOutputTokens = 100
                            tools = definitions.map { modelTool(it.modelName, it.description, Json.parseToJsonElement(it.inputSchema).jsonObject) }
                        }, disk,
                        LedgerToolExecutor(seed.host.ledger, seed.host.catalog.gate, definitions,
                            seed.host.runBridge(), RunApprovals { error("No tool should request approval") }),
                        journal = StoredRunJournal(disk),
                        budget = ContextBudget({ 100L }, { if (uncertain) 8192L else 10L }),
                        contextPolicy = ContextPolicy("goal")).runChatTask()
                    disk.saveChatResult(result)
                }
                val submitted = requests.get()
                seed.close()
                val cold = fixture(root, AtomicInteger(), model)
                cold.owner.open().join()
                val token = requireNotNull(cold.owner.state.value.recovery)
                assertTrue(token.identity.unattended)
                assertTrue(recoveryActions(cold.owner.state.value).isEmpty())
                val action = if (uncertain) ChatRecoveryAction.RETRY else ChatRecoveryAction.READMIT
                cold.owner.recover(token, action, 8192, true).join()
                cold.owner.recover(token.copy(identity = token.identity.copy(unattended = false)), action, 8192, true).join()
                cold.owner.resume(summaryWindowTokens = 8192, reconcileId = token.reconciliation?.id).join()
                cold.owner.send("continue this unattended task").join()
                assertEquals(submitted, requests.get())
                assertEquals(token, cold.owner.state.value.recovery)
                cold.close()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun cancellationRetainsPartialReplyAndJournalPagingSurvivesReopen() = runBlocking {
        val root = Files.createTempDirectory("chat-journal-pages").toFile()
        val arrived = CompletableDeferred<Unit>()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                emit(Events.started("cancelled", "model")); emit(Events.text(0, "unfinished text"))
                arrived.complete(Unit); awaitCancellation()
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("start").join(); arrived.await()
            await { first.owner.previewText() == "unfinished text" }
            first.owner.stop().join(); await { !first.owner.state.value.active }
            assertEquals("INTERRUPTED", first.owner.state.value.journal.single().reason)
            assertEquals("unfinished text", (first.owner.state.value.journal.single().partial.parts.single() as ContentPart.Text).text)
            val session = first.owner.state.value.sessionId!!
            first.close()
            val seed = fixture(root, AtomicInteger(), provider(false))
            seed.store.openHistory(session).use { disk ->
                val journal = StoredRunJournal(disk)
                repeat(25) { index ->
                    journal.submitted("refusal-$index", "test", ModelExecutionMode.GENERATION_ONLY)
                    journal.finished(ModelAttempt("refusal-$index", "test", ModelExecutionMode.GENERATION_ONLY,
                        message(Role.ASSISTANT, textPart("refusal-$index")), Events.finished(FinishReason.REFUSAL), null, false))
                }
            }
            seed.owner.open().join()
            assertEquals(20, seed.owner.state.value.journal.size)
            seed.owner.olderReplies().join()
            assertEquals(26, seed.owner.state.value.journal.size)
            assertNull(seed.owner.state.value.olderJournal)
            assertEquals(1, seed.owner.state.value.messages.size)
            seed.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun uncertainRequestNeedsExactExplicitReconciliationAfterColdOpen() = runBlocking {
        val root = Files.createTempDirectory("chat-reconciliation").toFile()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("remote-$count", "model")); emit(Events.text(0, "remote reply"))
                if (count == 1) throw java.io.IOException("unknown server outcome")
                emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("goal").join(); await { !first.owner.state.value.active }
            val receipt = requireNotNull(first.owner.state.value.reconciliation)
            val completedSteps = first.owner.state.value.result!!.steps
            assertEquals(R.string.chat_pause_reconcile, chatOutcome(first.owner.state.value.result!!))
            first.close()
            val presence = RecordingPresence().also { it.admitted = CompletableDeferred() }
            val cold = fixture(root, AtomicInteger(), model, presence = presence)
            cold.owner.open().join(); cold.owner.resume().join()
            assertEquals(1, requests.get())
            cold.owner.resume(reconcileId = "stale").join()
            assertEquals(receipt.id, cold.owner.state.value.reconciliation!!.id)
            val selection = requireNotNull(cold.owner.state.value.recovery)
            cold.owner.recover(selection, ChatRecoveryAction.RETRY, confirmed = false).join()
            assertEquals(1, requests.get())
            cold.owner.recover(selection.copy(revision = selection.revision - 1), ChatRecoveryAction.RETRY, confirmed = true).join()
            assertEquals(receipt.id, cold.owner.state.value.reconciliation!!.id)
            cold.owner.recover(selection, ChatRecoveryAction.RETRY, confirmed = true).join()
            await { presence.events.isNotEmpty() }
            assertEquals(ChatTaskProgress.Model, presence.events.first().second)
            assertEquals(1, requests.get())
            presence.admitted!!.complete(Unit)
            await { !cold.owner.state.value.active }
            assertNull(presence.events.last().second)
            assertEquals(2, requests.get())
            assertEquals(R.string.chat_complete, cold.owner.state.value.notice)
            assertNull(cold.owner.state.value.reconciliation)
            assertEquals(completedSteps + 1, cold.owner.state.value.result!!.steps)
            cold.owner.recover(selection, ChatRecoveryAction.RETRY, confirmed = true).join()
            assertEquals(2, requests.get())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun coldFirstUncertainRequestRetainsRecoveryIdentity() = runBlocking { crashWhileSubmitted(false) }
    @Test fun coldUncertainRequestAfterCompletedTurnRemainsRecoverable() = runBlocking { crashWhileSubmitted(true) }

    private suspend fun crashWhileSubmitted(priorCompleted: Boolean) {
        val root = Files.createTempDirectory("chat-cold-recovery").toFile()
        val entered = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("cold-$count", "model"))
                if (count == if (priorCompleted) 2 else 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                } else {
                    emit(Events.text(0, "answer")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
        }
        try {
            val live = File(root, "live").also { it.mkdir() }
            val saved = File(root, "saved").also { it.mkdir() }
            val seed = fixture(live, AtomicInteger(), model)
            try {
                seed.owner.open().join()
                if (priorCompleted) {
                    seed.owner.send("completed goal").join(); await { !seed.owner.state.value.active }
                    assertEquals(RunState.COMPLETED, seed.owner.state.value.result!!.state)
                }
                seed.owner.send("uncertain goal").join()
                withTimeout(10_000) { entered.await() }
                BundledSQLiteDriver().open(File(live, AgentDatabase.FILE_NAME).path).use { db ->
                    db.prepare("VACUUM INTO ?").use {
                        it.bindText(1, File(saved, AgentDatabase.FILE_NAME).path); it.step()
                    }
                }
            } finally { seed.close() }
            val cold = fixture(saved, AtomicInteger(), model)
            try {
                cold.owner.open().join()
                val state = cold.owner.state.value
                assertNotNull("Unknown server result must remain pending", state.reconciliation)
                assertNotNull("Fresh attended submission must retain its recovery identity", state.recovery)
                assertEquals(setOf(ChatRecoveryAction.RETRY), recoveryActions(state))
                val count = requests.get()
                cold.owner.recover(state.recovery!!, ChatRecoveryAction.RETRY, confirmed = true).join()
                await { !cold.owner.state.value.active }
                assertEquals(count + 1, requests.get())
                assertEquals(RunState.COMPLETED, cold.owner.state.value.result!!.state)
            } finally { cold.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun checkpointResumeRejectsChangedCatalogInsteadOfGuessingAliases() = runBlocking {
        val root = Files.createTempDirectory("chat-catalog-checkpoint").toFile()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("catalog-$count", "model")); emit(Events.text(0, "summary")); emit(Events.end(0))
                emit(Events.finished(if (request.tools.isNotEmpty()) FinishReason.CONTEXT_EXCEEDED else FinishReason.STOP))
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("goal").join(); await { !first.owner.state.value.active }
            first.owner.resume(summaryWindowTokens = 8192).join(); await { !first.owner.state.value.active }
            assertTrue(first.owner.state.value.checkpoint!!.canContinue)
            val count = requests.get()
            first.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.host.catalog.registerHost("host/clock", "2", tool(AtomicInteger()))
            cold.owner.open().join(); cold.owner.resume().join(); await { !cold.owner.state.value.active }
            assertEquals(count, requests.get())
            assertNotNull(cold.owner.state.value.error)
            assertTrue(cold.owner.state.value.checkpoint!!.canContinue)
            cold.close()
        } finally { root.deleteRecursively() }
    }

    private fun countingProvider(requests: AtomicInteger, finish: (Int) -> FinishReason) = object : ModelProvider {
        override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
        override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
            val number = requests.incrementAndGet()
            emit(Events.started("response-$number", "model"))
            emit(Events.text(0, "reply")); emit(Events.end(0)); emit(Events.finished(finish(number)))
        }
    }

    @Test fun pausedTaskStillExhaustsItsOwnBudgetAfterColdOpen() = runBlocking {
        val root = Files.createTempDirectory("chat-paused-budget").toFile()
        val requests = AtomicInteger()
        val model = countingProvider(requests) { if (it == 1) FinishReason.STOP else FinishReason.LENGTH }
        try {
            val f = fixture(root, AtomicInteger(), model)
            f.owner.open().join()
            f.owner.send("first task").join(); await { !f.owner.state.value.active }
            assertEquals(R.string.chat_complete, f.owner.state.value.notice)
            repeat(50) {
                f.owner.send("continue unfinished task").join(); await { !f.owner.state.value.active }
                assertEquals(R.string.chat_paused, f.owner.state.value.notice)
                assertEquals(it + 2, requests.get())
            }
            f.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.owner.open().join(); cold.owner.send("continue after restart").join()
            await { !cold.owner.state.value.active }
            assertEquals(R.string.chat_paused, cold.owner.state.value.notice)
            assertEquals(51, requests.get())
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun completedQuestionDoesNotCancelSubsequentApproval() = runBlocking {
        for (decision in listOf(Decision.ALLOW_ONCE, Decision.DENY)) {
            val root = Files.createTempDirectory("chat-next-approval").toFile()
            val executions = AtomicInteger()
            val requests = AtomicInteger()
            val delegate = provider()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    if (requests.incrementAndGet() == 1) {
                        emit(Events.started("first", "model")); emit(Events.text(0, "first answer"))
                        emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    } else delegate.chat(request, auth).collect { emit(it) }
                }
            }
            try {
                val fixture = fixture(root, executions, model)
                try {
                    fixture.owner.open().join(); fixture.owner.send("first question").join()
                    await { !fixture.owner.state.value.active }
                    fixture.owner.send("write after first answer").join()
                    await { fixture.owner.state.value.records.values.any { it.pending != null } || !fixture.owner.state.value.active }
                    fixture.owner.latest().join()
                    assertTrue(fixture.failures.toString(), fixture.owner.state.value.active)
                    val record = fixture.owner.state.value.records.values.single()
                    assertNotNull(record.pending); assertEquals(0, executions.get())
                    assertNull(fixture.owner.state.value.checkpoint)
                    fixture.owner.decide(record.callId, record.pending!!.id, decision).join()
                    await { !fixture.owner.state.value.active }
                    assertEquals(if (decision == Decision.ALLOW_ONCE) 1 else 0, executions.get())
                    assertEquals(decision, fixture.host.ledger.get(record.callId)!!.receipts.single().decision)
                    assertEquals(6, fixture.owner.state.value.messages.size)
                    assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                } finally { fixture.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun rejectedContextRecoveryShrinksBeforeGenerationEvenWhenNewWindowWouldFit() = runBlocking {
        for (window in listOf(8192L, 32768L)) {
            val root = Files.createTempDirectory("chat-rejected-context").toFile()
            val requests = mutableListOf<ChatRequest>()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request
                    if (requests.size == 1) {
                        emit(Events.failed(modelError(ModelErrorCode.CONTEXT_TOO_LONG, "window rejected")))
                    } else {
                        emit(Events.started("recovered-${requests.size}", "model"))
                        emit(Events.text(0, if (request.tools.isEmpty()) "summary of earlier answers" else "final answer"))
                        emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, AtomicInteger(), model)
                first.store.createHistory("session").use { disk ->
                    disk.append(0, listOf(message(Role.USER, textPart("u".repeat(5000))),
                        message(Role.ASSISTANT, textPart("a".repeat(5000)))))
                }
                first.owner.open().join(); first.owner.send("continue goal").join(); await { !first.owner.state.value.active }
                assertEquals(1, requests.size)
                val retries = first.owner.state.value.result!!.retries
                first.close()
                val cold = fixture(root, AtomicInteger(), model)
                try {
                    cold.owner.open().join(); assertEquals(1, requests.size)
                    cold.owner.resume(summaryWindowTokens = window).join(); await { !cold.owner.state.value.active }
                    assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
                    assertTrue(requests[1].tools.isEmpty())
                    val generation = requests.last()
                    assertTrue(generation.tools.isNotEmpty())
                    val codec = OpenAiChatCodec(OpenAiChatConfig("test", "https://example.invalid/v1", setOf("model")))
                    assertTrue(codec.encode(generation).length < codec.encode(requests.first()).length)
                    assertTrue(256L + codec.encode(generation).toByteArray(Charsets.UTF_8).size + generation.maxOutputTokens!! <= window)
                    assertTrue(cold.owner.state.value.result!!.retries >= retries)
                    assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                } finally { cold.close() }
                val verified = fixture(root, AtomicInteger(), model)
                try {
                    verified.store.openHistory("session").use { disk ->
                        assertEquals(4, disk.snapshot().messages.size)
                        assertEquals("u".repeat(5000), (disk.snapshot().messages.first().parts.single() as ContentPart.Text).text)
                    }
                } finally { verified.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun contextExceededReadmissionSummarizesBeforeGenerationAcrossColdOpen() = runBlocking {
        for (incomplete in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-context-exceeded").toFile()
            val requests = mutableListOf<ChatRequest>()
            val executions = AtomicInteger()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request
                    emit(Events.started("context-${requests.size}", "model"))
                    if (requests.size == 1) {
                        emit(Events.text(0, "truncated reply")); emit(Events.end(0))
                        if (incomplete) {
                            emit(Events.toolStart(1, "wire", "host_clock"))
                            emit(Events.toolDelta(1, "{")); emit(Events.end(1))
                        }
                        emit(Events.finished(FinishReason.CONTEXT_EXCEEDED))
                    } else {
                        emit(Events.text(0, if (request.tools.isEmpty()) "prior summary" else "final answer"))
                        emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, executions, model)
                try {
                    first.store.createHistory("session").use { disk ->
                        disk.append(0, listOf(message(Role.USER, textPart("u".repeat(5000))),
                            message(Role.ASSISTANT, textPart("a".repeat(5000)))))
                    }
                    first.owner.open().join(); first.owner.send("continue goal").join()
                    await { !first.owner.state.value.active }
                    assertEquals(1, requests.size)
                    assertEquals("BUDGET", first.owner.state.value.checkpoint!!.pauseClass)
                    assertTrue(first.failures.toString(), first.failures.isEmpty())
                } finally { first.close() }
                val cold = fixture(root, executions, model)
                try {
                    cold.owner.open().join(); assertEquals(1, requests.size)
                    cold.owner.resume(summaryWindowTokens = 32768).join()
                    await { !cold.owner.state.value.active }
                    assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
                    assertTrue(requests[1].tools.isEmpty())
                    assertTrue(requests.last().tools.isNotEmpty())
                    val codec = OpenAiChatCodec(OpenAiChatConfig("test", "https://example.invalid/v1", setOf("model")))
                    assertTrue(codec.encode(requests.last()).length < codec.encode(requests.first()).length)
                    assertFalse(requests.last().messages.any { m -> m.parts.any { it is ContentPart.ToolCall } })
                    assertEquals(0, executions.get())
                    assertEquals(if (incomplete) 4 else 5, cold.owner.state.value.messages.size)
                    assertEquals("u".repeat(5000), (cold.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
                    assertTrue(cold.owner.state.value.result!!.steps >= 2)
                    assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                } finally { cold.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun reasoningOnlyRepliesSurviveRetryAndColdOpenWithoutReplay() = runBlocking {
        for (retrySucceeds in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-reasoning-reply").toFile()
            val requests = mutableListOf<ChatRequest>()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun canReplay(data: ReplayData, targetModel: String) = false
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request
                    emit(Events.started("response-${requests.size}", "model"))
                    if (requests.size == 1 || !retrySucceeds) {
                        emit(Events.reasoning(0, "retained reasoning"))
                        emit(Events.end(0, replayData("test", "model", "retained signature")))
                    } else {
                        emit(Events.text(0, "visible answer")); emit(Events.end(0))
                    }
                    emit(Events.finished(FinishReason.STOP))
                }
            }
            try {
                val first = fixture(root, AtomicInteger(), model)
                val replies = try {
                    first.owner.open().join(); first.owner.send("question").join()
                    await { !first.owner.state.value.active }
                    assertEquals(if (retrySucceeds) RunState.COMPLETED else RunState.FAILED, first.owner.state.value.result?.state)
                    assertEquals(2, requests.size)
                    assertTrue(requests.all { request -> request.messages.flatMap { it.parts }.none { it is ContentPart.Reasoning } })
                    assertTrue(first.failures.toString(), first.failures.isEmpty())
                    first.owner.state.value.journal.also { replies ->
                        assertEquals(if (retrySucceeds) 1 else 2, replies.size)
                        replies.forEach { reply ->
                            assertEquals("EMPTY_RESPONSE", reply.reason)
                            assertEquals(R.string.chat_diagnostic_empty, replyReason(reply.reason))
                            val part = reply.partial.parts.single() as ContentPart.Reasoning
                            assertEquals("retained reasoning", part.summary)
                            assertEquals("retained signature", part.replay?.payload)
                        }
                    }
                } finally { first.close() }
                val cold = fixture(root, AtomicInteger(), model)
                try {
                    cold.owner.open().join()
                    assertEquals(replies, cold.owner.state.value.journal)
                    assertTrue(cold.owner.state.value.messages.flatMap { it.parts }.none { it is ContentPart.Reasoning })
                    assertEquals(2, requests.size)
                    assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                } finally { cold.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun incompleteTruncatedRepliesSurviveColdOpenAndNeverReplay() = runBlocking {
        for (finish in listOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED)) {
            val root = Files.createTempDirectory("chat-truncated-reply").toFile()
            val requests = mutableListOf<ChatRequest>()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request; emit(Events.started("truncated-${requests.size}", "model"))
                    if (requests.size == 1) {
                        emit(Events.text(0, "discarded partial reply")); emit(Events.end(0))
                        emit(Events.toolStart(1, "incomplete", "host_clock")); emit(Events.toolDelta(1, "{"))
                        emit(Events.end(1))
                        emit(Events.finished(finish))
                    } else {
                        emit(Events.text(0, "safe answer")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, AtomicInteger(), model)
                first.owner.open().join(); first.owner.send("question").join(); await { !first.owner.state.value.active }
                val reply = first.owner.state.value.journal.single()
                assertEquals(finish.name, reply.reason)
                assertEquals(if (finish == FinishReason.LENGTH) R.string.chat_reply_length else R.string.chat_reply_context,
                    replyReason(reply.reason))
                assertEquals(1, first.owner.state.value.messages.size)
                assertTrue(first.failures.toString(), first.failures.isEmpty()); first.close()
                val cold = fixture(root, AtomicInteger(), model)
                try {
                    cold.owner.open().join(); assertEquals(reply.requestId, cold.owner.state.value.journal.single().requestId)
                    cold.owner.send("new question").join(); await { !cold.owner.state.value.active }
                    assertEquals(R.string.chat_complete, cold.owner.state.value.notice)
                    assertFalse(requests.last().messages.any { message -> message.parts.any { part ->
                        part is ContentPart.ToolCall || part is ContentPart.Text && part.text.contains("discarded partial")
                    } })
                    assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                } finally { cold.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun realLoopApprovesThroughHostApiAndPersistsAcrossReopen() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider())
            f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val record = f.owner.state.value.records.values.single()
            assertEquals(0, count.get()); assertTrue(showApproval(record)); assertEquals(R.string.chat_tool_approval, toolState(record))
            f.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !f.owner.state.value.active }
            assertEquals(1, count.get()); assertTrue(f.failures.toString(), f.failures.isEmpty())
            assertEquals(Decision.ALLOW_ONCE, f.host.ledger.get(record.callId)!!.receipts.single().decision)
            assertTrue(f.owner.state.value.messages.last().parts.filterIsInstance<ContentPart.Text>().single().text.contains("hello"))
            val session = f.owner.state.value.sessionId
            f.close()
            val reopened = fixture(root, count, provider(false)); reopened.owner.open().join()
            assertEquals(session, reopened.owner.state.value.sessionId)
            assertEquals(4, reopened.owner.state.value.messages.size); assertEquals(1, count.get())
            reopened.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectionRecordsReceiptAndDoesNotExecute() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider()); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val record = f.owner.state.value.records.values.single()
            f.owner.decide(record.callId, record.pending!!.id, Decision.DENY).join(); await { !f.owner.state.value.active }
            assertEquals(0, count.get()); assertEquals(Status.REJECTED, f.host.ledger.get(record.callId)!!.state)
            assertEquals(Decision.DENY, f.host.ledger.get(record.callId)!!.receipts.single().decision)
            assertTrue(f.failures.toString(), f.failures.isEmpty()); f.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun storedApprovalSurvivesColdOpenAndUsesOriginalCallForBothDecisions() = runBlocking {
        for (decision in listOf(Decision.ALLOW_ONCE, Decision.DENY)) {
            val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
            try {
                val completed = fixture(root, count, provider(false))
                completed.store.createHistory("session").close()
                completed.owner.open().join(); completed.owner.send("earlier question").join()
                await { !completed.owner.state.value.active }
                assertEquals(R.string.chat_complete, completed.owner.state.value.notice)
                completed.close()
                val seed = fixture(root, count, provider(false))
                val d = seed.host.catalog.freeze("old-run").getValue("host_clock")
                val binding = callBinding("test", "request", "response", 0, "wire")
                seed.store.openHistory("session").use { history ->
                    val chain = history.chatTaskChainId()
                    history.append(history.revision(), listOf(message(Role.USER, textPart("goal"))))
                    history.prepareStep(history.revision(), RunIdentity(chain, "session", "old-run"), "step",
                        message(Role.ASSISTANT, toolCallPart(binding, d.modelName, "{}")))
                    val input = InvocationInput(BindingValue.copyOf(binding), chain, "session", "old-run", "step", 0,
                        "{}", d, false, Risk.WRITE)
                    assertTrue(seed.host.prepare(input) is Mutation.Applied)
                }
                val original = seed.host.ledger.get(binding.callId)!!
                seed.close()
                val presence = RecordingPresence()
                val cold = fixture(root, count, provider(false), presence = presence); cold.owner.open().join()
                assertTrue(presence.events.isEmpty())
                assertEquals(original, cold.host.ledger.get(binding.callId)); assertEquals(0, count.get())
                assertTrue(cold.owner.state.value.pending)
                assertTrue(showApproval(cold.owner.state.value.records.getValue(binding.callId)))
                assertEquals(R.string.chat_tool_approval, toolState(cold.owner.state.value.records[binding.callId]))
                cold.owner.decide(binding.callId, original.pending!!.id, decision).join()
                await { !cold.owner.state.value.active }
                assertEquals(ChatTaskProgress.Model, presence.events.first().second)
                assertNull(presence.events.last().second)
                assertEquals(if (decision == Decision.DENY) 0 else 1, count.get())
                assertEquals(decision, cold.host.ledger.get(binding.callId)!!.receipts.single().decision)
                assertFalse(cold.owner.state.value.pending)
                assertEquals(5, cold.owner.state.value.messages.size)
                assertTrue(cold.failures.toString(), cold.failures.isEmpty()); cold.close()
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun coldApprovalPreservesStructuredAndOversizedOutputAndOriginalRetrievalAlias() = runBlocking {
        for (large in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-cold-output").toFile()
            val count = AtomicInteger()
            val payload = ToolResult(if (large) "x".repeat(68000) else "done",
                structured = Json.parseToJsonElement("""{"ok":true}"""), effect = ToolEffect.APPLIED)
            val write = tool(count, payload)
            try {
                val live = File(root, "live").also { it.mkdir() }
                val saved = File(root, "saved").also { it.mkdir() }
                val seed = fixture(live, count, provider(), write)
                // Aliases collide only in the original process. Capture the real pending transaction
                // before stopping its owner, so teardown cannot inject cancellation into the copy.
                seed.host.catalog.registerHost("host/blob/read", "1", write)
                seed.owner.open().join(); seed.owner.send("goal").join()
                await { seed.owner.state.value.records.values.any { it.pending != null } }
                val original = seed.owner.state.value.records.values.single()
                val run = original.input.runId
                val reader = seed.host.catalog.frozen(run)!!.values.single { it.toolKey == "host/blob_read" }
                assertNotEquals("host_blob_read", reader.modelName)
                BundledSQLiteDriver().open(File(live, AgentDatabase.FILE_NAME).path).use { db ->
                    db.prepare("VACUUM INTO ?").use {
                        it.bindText(1, File(saved, AgentDatabase.FILE_NAME).path); it.step()
                    }
                }
                seed.close()
                val cold = fixture(saved, count, provider(), write)
                cold.owner.open().join()
                val request = cold.host.ledger.get(original.callId)!!.pending!!
                assertEquals(reader, cold.host.catalog.definition(run, reader.modelName))
                cold.owner.decide(original.callId, request.id, Decision.ALLOW_ONCE).join()
                await { !cold.owner.state.value.active }
                val record = cold.host.ledger.get(original.callId)!!
                assertEquals(Status.SUCCEEDED, record.state)
                val result = requireNotNull(record.result)
                assertEquals(Effect.APPLIED, result.effect)
                assertEquals(1, record.receipts.size)
                assertEquals(1, count.get())
                val part = cold.owner.state.value.messages.last().parts.single() as ContentPart.ToolResult
                assertEquals(ModelResultText.encode(result.status, result.effect, result.text), part.text)
                assertFalse(part.isError)
                assertTrue(part.text.contains(if (large) "Retrieve with ${reader.modelName}(" else "[structured JSON]"))
                val raw = requireNotNull(result.output).raw
                assertEquals(raw, cold.host.ledger.readBlob(run, raw.id, 0).first)
                cold.owner.send("again").join()
                await { cold.owner.state.value.records.values.any { it.pending != null } }
                val next = cold.owner.state.value.records.values.single { it.pending != null }
                cold.owner.decide(next.callId, next.pending!!.id, Decision.ALLOW_ONCE).join()
                await { !cold.owner.state.value.active }
                assertEquals(2, count.get()); assertEquals(Status.SUCCEEDED, cold.host.ledger.get(next.callId)!!.state)
                assertTrue(cold.failures.toString(), cold.failures.isEmpty()); cold.close()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun failedApprovalWriteSettlesWithoutWorkerOrUncaughtFailure() = runBlocking {
        for (stage in listOf(SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) {
            val root = Files.createTempDirectory("chat-write-failure").toFile()
            val inject = java.util.concurrent.atomic.AtomicBoolean(false)
            val count = AtomicInteger()
            val fixture = fixture(root, count, provider()) {
                if (it == stage && inject.compareAndSet(true, false)) error("Injected approval write failure")
            }
            try {
                val owner = fixture.owner
                owner.open().join(); owner.send("goal").join()
                await { owner.state.value.records.values.any { it.pending != null } }
                val record = owner.state.value.records.values.single()
                inject.set(true); owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
                await { !owner.state.value.active }
                assertFalse(inject.get()); assertEquals(0, count.get()); assertNotNull(owner.state.value.error)
                assertTrue(fixture.host.submissionsClosed)
            } finally { fixture.close(); root.deleteRecursively() }
        }
    }

    @Test fun lateToolExitPublishesRecoveryOnIdleChat() = runBlocking {
        val root = Files.createTempDirectory("chat-late-recovery").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = object : Tool {
            override val id = "write"
            override val spec = ToolSpec(id, "test write", schema, risk = ToolRisk.WRITE)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return ToolResult("late", effect = ToolEffect.UNKNOWN)
            }
        }
        val fixture = fixture(root, AtomicInteger(), provider(), write)
        try {
            val owner = fixture.owner
            owner.open().join(); owner.send("goal").join()
            await { owner.state.value.records.values.any { it.pending != null } }
            val record = owner.state.value.records.values.single()
            owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            withTimeout(10_000) { entered.await() }
            owner.stop().join(); await { !owner.state.value.active }
            assertNull(owner.state.value.recoveries[record.callId])
            release.complete(Unit); fixture.host.awaitObservedExit(record.callId)
            val recovery = fixture.host.ledger.pendingRecoveries().single()
            await { owner.state.value.recoveries[record.callId] == recovery }
            assertEquals(InstanceState.EXIT_CONFIRMED, owner.state.value.records.getValue(record.callId).instance!!.state)
            assertFalse(owner.state.value.active)
        } finally { release.complete(Unit); fixture.close(); root.deleteRecursively() }
    }

    @Test fun completedTextTasksReleaseBothRunDirectories() = runBlocking {
        val root = Files.createTempDirectory("chat-catalog-lifetime").toFile()
        try {
            val f = fixture(root, AtomicInteger(), provider(false)); f.owner.open().join()
            repeat(24) {
                f.owner.send("question $it").join(); await { !f.owner.state.value.active }
                assertEquals(R.string.chat_complete, f.owner.state.value.notice)
            }
            f.owner.newSession().join()
            assertEquals(0, f.host.catalog.frozenRunCount)
            assertEquals(0, f.host.catalog.gate.frozenRunCount)
            f.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun parallelUnknownResultRenewsInlineApprovalWithoutLosingTheCall() = runBlocking {
        for (decision in listOf(Decision.DENY, Decision.ALLOW_ONCE)) {
            val root = Files.createTempDirectory("chat-renewal").toFile()
            val count = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val parallelTool = object : Tool {
                override val id = "write"
                override val spec = ToolSpec(id, "test write", schema, risk = ToolRisk.WRITE, parallelSafe = true, timeoutMs = 30_000)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = if (count.incrementAndGet() == 1) {
                    release.await(); ToolResult("interrupted", isError = true, effect = ToolEffect.UNKNOWN)
                } else ToolResult("done", effect = ToolEffect.APPLIED)
            }
            val fixture = fixture(root, count, provider(calls = 2), parallelTool)
            try {
                val owner = fixture.owner
                owner.open().join(); owner.send("two writes").join()
                suspend fun pending(order: Int): InvocationRecord {
                    await { owner.state.value.records.values.any { it.pending != null && it.input.callOrder == order } }
                    return owner.state.value.records.values.single { it.input.callOrder == order }
                }
                val first = pending(0)
                owner.decide(first.callId, first.pending!!.id, Decision.ALLOW_ONCE).join()
                val second = pending(1)
                val oldId = second.pending!!.id
                release.complete(Unit)
                await { fixture.host.ledger.get(first.callId)?.instance?.state == InstanceState.EXIT_CONFIRMED }
                owner.decide(second.callId, oldId, Decision.ALLOW_ONCE).join()
                val updated = requireNotNull(pending(1).pending)
                assertNotEquals(oldId, updated.id)
                assertEquals(listOf(first.callId), updated.unknownCalls)
                assertEquals(1, count.get())
                owner.decide(second.callId, updated.id, decision).join()
                await { !owner.state.value.active }
                val final = requireNotNull(fixture.host.ledger.get(second.callId))
                assertEquals(updated.id, final.receipts.single().request.id)
                assertEquals(decision, final.receipts.single().decision)
                assertEquals(if (decision == Decision.DENY) Status.REJECTED else Status.SUCCEEDED, final.state)
                assertEquals(if (decision == Decision.DENY) 1 else 2, count.get())
                assertEquals(R.string.chat_complete, owner.state.value.notice)
            } finally { release.complete(Unit); fixture.close(); root.deleteRecursively() }
        }
    }

    @Test fun validatedStreamingAppearsBeforeFinishAndCancellationSettlesOwner() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val hold = CompletableDeferred<Unit>()
        try {
            val f = fixture(root, AtomicInteger(), provider(false, hold)); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.draft == "hello" }
            assertTrue(f.owner.state.value.active); assertEquals(1, f.owner.state.value.messages.size)
            f.owner.stop().join(); await { !f.owner.state.value.active }
            assertEquals(R.string.chat_stopped, f.owner.state.value.notice); assertEquals("", f.owner.state.value.draft)
            f.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun historyPagingAndNewSessionStaySeparate() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile()
        try {
            val f = fixture(root, AtomicInteger(), provider(false))
            f.store.createHistory("session").use { h -> repeat(85) { h.append(h.revision(), listOf(message(Role.USER, textPart("message-$it")))) } }
            f.owner.open().join(); assertEquals(40, f.owner.state.value.messages.size)
            f.owner.older().join(); assertEquals("message-5", (f.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
            f.owner.newSession().join(); assertTrue(f.owner.state.value.messages.isEmpty())
            f.owner.select("session").join(); assertEquals(40, f.owner.state.value.messages.size)
            f.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun staleUiApprovalCannotGrantAndUnattendedNeverShowsActions() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider()); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val record = f.owner.state.value.records.values.single()
            assertFalse(showApproval(record.copy(input = record.input.copy(unattended = true))))
            f.owner.decide(record.callId, "stale-id", Decision.ALLOW_ONCE).join()
            assertEquals(record.pending, f.host.ledger.get(record.callId)!!.pending)
            assertEquals(0, count.get()); assertNotNull(f.owner.state.value.error)
            f.owner.stop().join(); await { !f.owner.state.value.active }
            assertEquals(Status.CANCELLED, f.host.ledger.get(record.callId)!!.state)
            assertNull(f.host.ledger.get(record.callId)!!.instance)
            f.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun changedDefinitionCannotExecuteRecoveredApprovalButCanBeDenied() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider()); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val original = f.owner.state.value.records.values.single()
            f.host.catalog.registerHost("host/clock", "2", tool(count))
            f.owner.decide(original.callId, original.pending!!.id, Decision.ALLOW_ONCE).join()
            assertEquals(0, count.get())
            val current = f.host.ledger.get(original.callId)!!
            // The ledger may reject a stale definition outright; no permission is silently renewed.
            assertTrue(current.state == Status.REJECTED || current.pending != null)
            if (current.pending != null) f.owner.decide(current.callId, current.pending!!.id, Decision.DENY).join()
            await { !f.owner.state.value.active }
            assertEquals(0, count.get()); f.close()
        } finally { root.deleteRecursively() }
    }

}
