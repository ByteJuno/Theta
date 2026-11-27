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

    @Test fun restoredPickerResultWaitsForStoreAndUpdatesOriginalSession() = kotlinx.coroutines.runBlocking {
        val root = temp.newFolder()
        var available: SessionComposerStore? = null
        val saver = ChatComposerDrafts.saver { available }
        val other = "another-session"
        val token = AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            store.createHistory(session).close()
            store.createHistory(other).close()
            available = store.composerDrafts
            val page = ChatComposerDrafts { available }
            page[session].edit("original")
            page[other].edit("other")
            with(saver) { requireNotNull(saverScope.save(page)) }
        }
        available = null
        val page = requireNotNull(saver.restore(token))
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            val target = requireNotNull(page.pickerTarget(session) { available = store.composerDrafts })
            assertEquals("original", target.input)
            target.images = listOf(image("picked"))
            page.checkpoint()
        }
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            val restored = store.composerDrafts.read(session)
            assertEquals("original", restored.text)
            assertArrayEquals("picked".toByteArray(), restored.images.single().bytes)
            val untouched = store.composerDrafts.read(other)
            assertEquals("other", untouched.text)
            assertTrue(untouched.images.isEmpty())
        }
    }

    private fun writeLegacyDraftFile(root: java.io.File, sessionId: String, version: String, text: String, images: List<PendingUserImage>) {
        val dir = root.resolve("composer-drafts-v1").apply { mkdirs() }
        val file = dir.resolve(UUID.nameUUIDFromBytes(sessionId.toByteArray()).toString())
        java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeUTF(version)
            out.writeInt(text.length)
            out.writeChars(text)
            out.writeInt(images.size)
            for (img in images) {
                out.writeUTF(img.localId)
                out.writeUTF(img.mimeType)
                val bytes = img.bytes
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
    }

    @Test fun corruptLegacyDraftIsIsolatedAcrossTwoReopensWhileDatabaseErrorsPropagate() {
        val root = temp.newFolder()
        val good = "good-session"
        val bad = "bad-session"
        val badHeader = "bad-header-session"
        val removed = "removed-session"
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            store.createHistory(good).close()
            store.createHistory(bad).close()
            store.createHistory(badHeader).close()
        }
        val legacyDir = root.resolve("composer-drafts-v1").apply { mkdirs() }
        writeLegacyDraftFile(root, good, "v-good", "keep good", listOf(image("g1")))
        val corruptBytes = byteArrayOf(0x00, 0x05, 0x41)
        val corruptFile = legacyDir.resolve(UUID.nameUUIDFromBytes(bad.toByteArray()).toString())
        corruptFile.writeBytes(corruptBytes)
        val badHeaderFile = legacyDir.resolve(UUID.nameUUIDFromBytes(badHeader.toByteArray()).toString())
        java.io.DataOutputStream(badHeaderFile.outputStream().buffered()).use { out ->
            out.writeUTF("v-bad-header")
            out.writeInt(999_999)
        }
        val badHeaderBytes = badHeaderFile.readBytes()
        val orphanFile = legacyDir.resolve(UUID.nameUUIDFromBytes(removed.toByteArray()).toString())
        orphanFile.writeBytes(byteArrayOf(1, 2, 3))

        // First reopen migrates the healthy session, removes orphan, and preserves corrupt originals.
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            val goodDraft = store.composerDrafts.read(good)
            assertEquals("keep good", goodDraft.text)
            assertEquals(listOf("g1"), goodDraft.images.map { it.id })
            assertEquals("", store.composerDrafts.read(bad).text)
            assertTrue(store.composerDrafts.read(bad).images.isEmpty())
            assertEquals("", store.composerDrafts.read(badHeader).text)
            assertTrue(corruptFile.isFile)
            assertArrayEquals(corruptBytes, corruptFile.readBytes())
            assertTrue(badHeaderFile.isFile)
            assertArrayEquals(badHeaderBytes, badHeaderFile.readBytes())
            assertFalse(orphanFile.exists())
        }

        // Second consecutive reopen also succeeds with corrupt originals still intact.
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            val goodDraft = store.composerDrafts.read(good)
            assertEquals("keep good", goodDraft.text)
            assertEquals(listOf("g1"), goodDraft.images.map { it.id })
            assertTrue(corruptFile.isFile)
            assertArrayEquals(corruptBytes, corruptFile.readBytes())
            assertTrue(badHeaderFile.isFile)
            assertArrayEquals(badHeaderBytes, badHeaderFile.readBytes())
        }

        // Database read/write errors during migration must NOT be swallowed.
        val dbFailRoot = temp.newFolder()
        AndroidAgentStore.open(dbFailRoot, BundledSQLiteDriver()).use { it.createHistory(good).close() }
        writeLegacyDraftFile(dbFailRoot, good, "v-fail", "must not swallow", emptyList())
        fun failingDriver(sqlMatch: String) = object : androidx.sqlite.SQLiteDriver {
            private val base = BundledSQLiteDriver()
            override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
                val delegate = base.open(fileName)
                return object : androidx.sqlite.SQLiteConnection by delegate {
                    override fun prepare(sql: String): androidx.sqlite.SQLiteStatement {
                        if (sql.contains(sqlMatch)) {
                            throw androidx.sqlite.SQLiteException("simulated database error: $sqlMatch")
                        }
                        return delegate.prepare(sql)
                    }
                }
            }
        }
        assertThrows(androidx.sqlite.SQLiteException::class.java) {
            AndroidAgentStore.open(dbFailRoot, failingDriver("composer_parts"))
        }
        assertThrows(androidx.sqlite.SQLiteException::class.java) {
            AndroidAgentStore.open(dbFailRoot, failingDriver("SELECT session_id FROM sessions"))
        }
        val preservedGood = dbFailRoot.resolve("composer-drafts-v1")
            .resolve(UUID.nameUUIDFromBytes(good.toByteArray()).toString())
        assertTrue(preservedGood.isFile)
    }

    @Test fun crossPageReconcileClearsConsumedPartsKeepsUnsentImageAndProtectsNewerDraft() {
        val root = temp.newFolder()
        val otherSession = "other"
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            store.createHistory(session).use { history ->
                store.createHistory(otherSession).close()
                val pageA = ChatComposerDrafts { store.composerDrafts }
                val sentImage = image("sent-1")
                pageA[session].edit("first")
                pageA[session].images = listOf(sentImage)
                pageA.checkpoint()

                val pageB = ChatComposerDrafts { store.composerDrafts }
                val draftB = pageB[session]
                val firstReceipt = pageB.receipt(session)
                history.appendUserInput(draftB.input, emptyList(), firstReceipt)
                draftB.acknowledge(firstReceipt)

                draftB.edit("second")
                val secondReceipt = pageB.receipt(session)
                history.appendUserInput(draftB.input, emptyList(), secondReceipt)
                draftB.acknowledge(secondReceipt)

                // Another page then saves a new unsent draft, and latest receipt even moves to another session.
                draftB.edit("newer page-b draft")
                pageB.checkpoint()
                val unrelatedReceipt = ChatDraftReceipt(otherSession, "other-version", emptySet())

                // Page A still holds consumed "first" and sentImage, plus an unsent new image.
                val keepImage = image("keep-a")
                pageA[session].images = listOf(sentImage, keepImage)
                pageA.reconcile(session, unrelatedReceipt)

                assertEquals("", pageA[session].input)
                assertEquals(listOf("keep-a"), pageA[session].images.map { it.localId })
                assertArrayEquals("keep-a".toByteArray(), pageA[session].images.single().bytes)

                // Checkpointing Page A after reconcile keeps Page B's newer text and persists keep-a.
                pageA.checkpoint()

                // Re-submitting an already-consumed receipt directly at history boundary fails without poisoning.
                try {
                    history.appendUserInput("first", emptyList(), firstReceipt)
                    fail("Expected already-consumed draft to be rejected")
                } catch (_: IllegalArgumentException) {
                    // expected
                }

                // Fresh image-only send using Page A's remaining unsent attachment succeeds cleanly.
                val freshImageOnlyReceipt = pageA.receipt(session)
                val keepMedia = listOf(io.github.bytejuno.thetacore.agent.protocol.mediaPart(
                    io.github.bytejuno.thetacore.agent.protocol.MediaKind.IMAGE,
                    io.github.bytejuno.thetacore.agent.protocol.blobRef("blob-keep-a", "image/png", 6L),
                ))
                history.appendUserInput("", keepMedia, freshImageOnlyReceipt)
                try {
                    history.appendUserInput("", keepMedia, freshImageOnlyReceipt)
                    fail("Expected already-consumed image-only draft to be rejected")
                } catch (_: IllegalArgumentException) {
                    // expected
                }
            }
        }
        AndroidAgentStore.open(root, BundledSQLiteDriver()).use { store ->
            val saved = store.composerDrafts.read(session)
            assertEquals("newer page-b draft", saved.text)
            assertTrue(saved.images.isEmpty())
            store.openHistory(session).use { history ->
                assertEquals(3L, history.page(null, 10).totalMessages)
            }
        }
    }

    @Test fun freshEditSupersedingConsumedBaselineCanStillBeSubmitted() {
        val store = AndroidAgentStore.open(temp.newFolder(), BundledSQLiteDriver()).also { stores += it }
        store.createHistory(session).use { history ->
            val pageA = ChatComposerDrafts { store.composerDrafts }
            pageA[session].edit("old")
            pageA[session].images = listOf(image("old-img"))
            pageA.checkpoint()

            val pageB = ChatComposerDrafts { store.composerDrafts }
            val receiptB = pageB.receipt(session)
            history.appendUserInput("old", emptyList(), receiptB)

            // Page A replaces the old draft with brand-new unsent text and attachment without reconciling first.
            val freshImg = image("fresh-img")
            pageA[session].edit("brand new")
            pageA[session].images = listOf(freshImg)
            val receiptA = pageA.receipt(session)
            history.appendUserInput("brand new", emptyList(), receiptA)
            assertEquals(2L, history.page(null, 10).totalMessages)
        }
    }

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
