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

class ImageAttachmentsTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = byteArrayOf(1, 2, 3)
    private val args = buildJsonObject { put("prompt", "cat by the window") }
    private fun snapshot() = OpenAiRunSnapshot(OpenAiProviderSettings("images", "https://example.invalid/v1", "image-model"), ApiKeyCredentials("private-test-key"))
    private fun env(session: String = "session", call: String = "call") = ToolEnv("run", session, call, false, { _, _ -> })
    @Test fun completedCallIsPrivateSessionScopedAndNeverReplayedAfterReopen() = runBlocking {
        val root = temp.newFolder()
        val count = AtomicInteger()
        var store = ImageAttachments(root)
        var tool = HostImageGenerationTool(::snapshot, store, { _, _ -> count.incrementAndGet(); bytes }, { it })
        val result = tool.call(args, env())
        assertEquals(IMAGE_COMPLETE, result.text)
        assertTrue(result.content.isEmpty())
        assertFalse(result.toString().contains("private-test-key"))
        assertArrayEquals(bytes, store.read("session", "call"))
        assertNull(store.read("foreign", "call"))
        store = ImageAttachments(root)
        tool = HostImageGenerationTool(::snapshot, store, { _, _ -> count.incrementAndGet(); bytes }, { it })
        assertEquals(IMAGE_ATTEMPTED, tool.call(args, env()).text)
        assertEquals(1, count.get())
        assertArrayEquals(bytes, store.read("session", "call"))
        store.deleteSession("session")
        assertNull(store.read("session", "call"))
        assertEquals(IMAGE_FAILED, tool.call(args, env()).text)
        assertEquals(1, count.get())
    }
    @Test fun cancelledAndFailedCallsKeepDurableAttemptWithoutAutomaticRetry() = runBlocking {
        for (cancel in listOf(false, true)) {
            val root = temp.newFolder()
            val entered = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val tool = HostImageGenerationTool(::snapshot, ImageAttachments(root), { _, _ ->
                count.incrementAndGet(); entered.complete(Unit)
                if (cancel) awaitCancellation() else error("secret signed-url image-body")
            }, { it })
            val job = async { tool.call(args, env()) }
            entered.await()
            if (cancel) job.cancelAndJoin() else {
                val result = job.await()
                assertEquals(IMAGE_FAILED, result.text)
                assertEquals(ToolEffect.UNKNOWN, result.effect)
                assertFalse(result.toString().contains("secret"))
            }
            val reopened = HostImageGenerationTool(::snapshot, ImageAttachments(root), { _, _ -> count.incrementAndGet(); bytes }, { it })
            assertEquals(IMAGE_ATTEMPTED, reopened.call(args, env()).text)
            assertEquals(1, count.get())
        }
    }
    @Test fun missingConfigurationDoesNotChargeOrConsumeAttempt() = runBlocking {
        val store = ImageAttachments(temp.newFolder())
        val tool = HostImageGenerationTool({ throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED) }, store,
            { _, _ -> throw AssertionError("Must not request") }, { it })
        assertEquals(IMAGE_CONFIGURE, tool.call(args, env()).text)
        assertTrue(store.claim("session", "call"))
        assertFalse(store.claim("session", "call"))
    }
    @Test fun sizeAndSessionDeletionBoundaries() {
        val store = ImageAttachments(temp.newFolder())
        assertTrue(store.claim("one", "same")); store.save("one", "same", bytes)
        assertTrue(store.claim("two", "same")); store.save("two", "same", bytes)
        store.deleteSession("one")
        assertNull(store.read("one", "same"))
        assertArrayEquals(bytes, store.read("two", "same"))
        assertTrue(store.claim("two", "oversize"))
        try { store.save("two", "oversize", ByteArray(ImageLimits.BYTES + 1)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun interruptedSessionDeletionIsCleanedOnNextOpenWithoutLosingOtherImages() {
        val root = temp.newFolder()
        val store = ImageAttachments(root)
        assertTrue(store.claim("deleted", "call")); store.save("deleted", "call", bytes)
        store.prepareDelete("deleted")
        assertTrue(store.claim("retained", "call")); store.save("retained", "call", bytes)
        ImageAttachments(root).removeDeletedSessions(setOf("retained"))
        assertNull(store.read("deleted", "call"))
        assertArrayEquals(bytes, store.read("retained", "call"))
    }
}

