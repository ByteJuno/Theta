package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.plugin.tools.HostImageGenerationTool
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import org.junit.After

class ImageAttachmentsTest {
    @get:Rule val temp = TemporaryFolder()
    private val owners = mutableListOf<AndroidAgentStore>()
    private fun open(root: java.io.File, fault: (SqliteWriteStage) -> Unit = {}) =
        AndroidAgentStore.open(root, BundledSQLiteDriver(), fault).also { owners += it }
    @After fun closeOwners() { owners.forEach { it.close() } }
    private fun delete(owner: AndroidAgentStore, session: String) =
        InvocationLedger(owner, DefinitionGate(), ArgumentsValidator { _, _ -> true }).deleteSession(session)
    private val bytes = byteArrayOf(1, 2, 3)
    private val args = buildJsonObject { put("prompt", "cat by the window") }
    private fun snapshot() = OpenAiRunSnapshot(OpenAiProviderSettings("images", "https://example.invalid/v1", "image-model"), ApiKeyCredentials("private-test-key"))
    private fun env(session: String = "session", call: String = "call") = ToolEnv("run", session, call, false, { _, _ -> })
    @Test fun completedCallIsPrivateSessionScopedAndNeverReplayedAfterReopen() = runBlocking {
        val root = temp.newFolder()
        val count = AtomicInteger()
        var owner = open(root)
        var store = ImageAttachments(owner.privateImages)
        var tool = HostImageGenerationTool(store, { _, _ -> count.incrementAndGet(); bytes }, { it })
        tool.configurations.bind("run", snapshot().withImageSnapshot(snapshot()))
        val result = tool.call(args, env())
        assertEquals(IMAGE_COMPLETE, result.text)
        assertTrue(result.content.isEmpty())
        assertFalse(result.toString().contains("private-test-key"))
        assertArrayEquals(bytes, store.read("session", "call"))
        assertNull(store.read("foreign", "call"))
        owner.close(); owner = open(root)
        store = ImageAttachments(owner.privateImages)
        tool = HostImageGenerationTool(store, { _, _ -> count.incrementAndGet(); bytes }, { it })
        assertEquals(IMAGE_ATTEMPTED, tool.call(args, env()).text)
        assertEquals(1, count.get())
        assertArrayEquals(bytes, store.read("session", "call"))
        delete(owner, "session")
        assertNull(store.read("session", "call"))
        assertEquals(IMAGE_FAILED, tool.call(args, env()).text)
        assertEquals(1, count.get())
    }
    @Test fun cancelledAndFailedCallsKeepDurableAttemptWithoutAutomaticRetry() = runBlocking {
        for (cancel in listOf(false, true)) {
            val root = temp.newFolder()
            val entered = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val owner = open(root)
            val tool = HostImageGenerationTool(ImageAttachments(owner.privateImages), { _, _ ->
                count.incrementAndGet(); entered.complete(Unit)
                if (cancel) awaitCancellation() else error("secret signed-url image-body")
            }, { it })
            tool.configurations.bind("run", snapshot().withImageSnapshot(snapshot()))
            val job = async { tool.call(args, env()) }
            entered.await()
            if (cancel) job.cancelAndJoin() else {
                val result = job.await()
                assertEquals(IMAGE_FAILED, result.text)
                assertEquals(ToolEffect.UNKNOWN, result.effect)
                assertFalse(result.toString().contains("secret"))
            }
            owner.close()
            val reopened = HostImageGenerationTool(ImageAttachments(open(root).privateImages), { _, _ -> count.incrementAndGet(); bytes }, { it })
            assertEquals(IMAGE_ATTEMPTED, reopened.call(args, env()).text)
            assertEquals(1, count.get())
        }
    }
    @Test fun missingConfigurationDoesNotChargeOrConsumeAttempt() = runBlocking {
        val store = ImageAttachments(open(temp.newFolder()).privateImages)
        val tool = HostImageGenerationTool(store,
            { _, _ -> throw AssertionError("Must not request") }, { it })
        assertEquals(IMAGE_CONFIGURE, tool.call(args, env()).text)
        assertTrue(store.claim("session", "call"))
        assertFalse(store.claim("session", "call"))
    }
    @Test fun sizeAndSessionDeletionBoundaries() {
        val owner = open(temp.newFolder())
        val store = ImageAttachments(owner.privateImages)
        assertTrue(store.claim("one", "same")); store.save("one", "same", bytes)
        assertTrue(store.claim("two", "same")); store.save("two", "same", bytes)
        delete(owner, "one")
        assertNull(store.read("one", "same"))
        assertArrayEquals(bytes, store.read("two", "same"))
        assertTrue(store.claim("two", "oversize"))
        try { store.save("two", "oversize", ByteArray(ImageLimits.BYTES + 1)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun interruptedSessionDeletionIsCleanedOnNextOpenWithoutLosingOtherImages() {
        for (stage in listOf(SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) {
            val root = temp.newFolder()
            var armed = false
            val owner = open(root) { if (armed && it == stage) error("injected deletion interruption") }
            val store = ImageAttachments(owner.privateImages)
            owner.createHistory("deleted").close()
            assertTrue(store.claim("deleted", "call")); store.save("deleted", "call", bytes)
            assertTrue(store.claim("retained", "call")); store.save("retained", "call", bytes)
            armed = true
            try { delete(owner, "deleted"); fail() } catch (_: StoreException) { }
            owner.close()
            val cold = open(root)
            val reopened = ImageAttachments(cold.privateImages)
            assertArrayEquals(bytes, reopened.read("retained", "call"))
            if (stage == SqliteWriteStage.BEFORE_COMMIT) {
                assertTrue(cold.sessionIds().contains("deleted"))
                assertArrayEquals(bytes, reopened.read("deleted", "call"))
                assertFalse(reopened.claim("deleted", "call"))
                delete(cold, "deleted")
            }
            assertFalse(cold.sessionIds().contains("deleted"))
            assertNull(reopened.read("deleted", "call"))
            try { reopened.claim("deleted", "call"); fail() } catch (_: IllegalStateException) { }
        }
    }
}

