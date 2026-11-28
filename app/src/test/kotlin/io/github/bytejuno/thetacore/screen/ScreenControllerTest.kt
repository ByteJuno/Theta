package io.github.bytejuno.thetacore.screen

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenControllerTest {
    private class Backend(override var ready: Boolean = true,
        var reply: ScreenReply = ScreenReply(ToolResult("ok", effect = ToolEffect.APPLIED))) : ScreenControlBackend {
        val actions = mutableListOf<ScreenAction>()
        override suspend fun execute(action: ScreenAction): ScreenReply { actions += action; return reply }
    }
    private val env = ToolEnv("run", "session", "call", true, { _, _ -> })

    @Test fun prefersGrantedRootAndFallsBackOnlyBeforeAnActionStarts() = runBlocking {
        val root = Backend(); val access = Backend()
        val control = ScreenController(root, access, { "Enable Theta in Accessibility settings" })
        val action = ScreenAction("screen_tap", Json.parseToJsonElement("""{"x":1,"y":2}""").jsonObject)
        control.execute(action); assertEquals(1, root.actions.size); assertTrue(access.actions.isEmpty())
        root.ready = false; control.execute(action); assertEquals(1, access.actions.size)
        root.ready = true; root.reply = unavailableScreenReply("no grant")
        control.execute(action); assertEquals(2, access.actions.size)
        root.reply = ScreenReply(ToolResult("unknown", isError = true, effect = ToolEffect.UNKNOWN))
        assertEquals(ToolEffect.UNKNOWN, control.execute(action).effect); assertEquals(2, access.actions.size)
        root.ready = false; access.ready = false
        val denied = control.execute(action)
        assertTrue(denied.isError); assertEquals(ToolEffect.NONE, denied.effect); assertTrue(denied.text.contains("Accessibility"))
    }

    @Test fun unicodeIsPreservedAndInvalidInputNeverReachesBackend() = runBlocking {
        val backend = Backend(); val control = ScreenController(backend, backend, { "missing" })
        val tool = ScreenTool("screen_input_text", control)
        val text = "中文 😀 e\u0301\n'\";$(do-not-execute)\\"
        tool.call(buildJsonObject { put("text", text) }, env)
        assertEquals(text, backend.actions.single().text("text"))
        for (invalid in listOf("\u0000", "\uD800", "\uDC00", "x".repeat(16_385))) {
            try { tool.call(buildJsonObject { put("text", invalid) }, env); fail("Accepted malformed input") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, backend.actions.size)
        val tap = ScreenTool("screen_tap", control)
        for (json in listOf("""{"x":-1,"y":2}""", """{"x":1.5,"y":2}""", """{"x":"1","y":2}""", """{"x":1,"y":2,"extra":3}""")) {
            try { tap.call(Json.parseToJsonElement(json).jsonObject, env); fail("Invalid coordinate accepted") }
            catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
        assertEquals(1, backend.actions.size)
    }

    @Test fun everyToolUsesScreenLeaseAndReadWriteRisksRemainExplicit() {
        val b = Backend(); val c = ScreenController(b, b, { "missing" })
        assertEquals(9, ScreenTool.descriptions.size)
        ScreenTool.descriptions.keys.forEach {
            val tool = ScreenTool(it, c)
            assertEquals("screen", tool.spec.exclusiveResource)
            assertFalse(tool.spec.idempotent)
            assertEquals(if (it in setOf("screen_capture", "screen_ui_tree", "app_list")) ToolRisk.READ else ToolRisk.DEVICE_CONTROL, tool.spec.risk)
        }
    }

    @Test fun cancellationJoinsBackendAndRestoresCaptureVisibility() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val exit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val backend = object : ScreenControlBackend {
            override val ready = true
            override suspend fun execute(action: ScreenAction): ScreenReply {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { exit.await(); events += "exited" } }
            }
        }
        val hook = object : ScreenCaptureVisibilityHook {
            override suspend fun beforeCapture() { events += "hidden" }
            override suspend fun afterCapture() { events += "visible" }
        }
        val job = launch { ScreenController(backend, backend, { "missing" }, hook).execute(ScreenAction("screen_capture", buildJsonObject { })) }
        entered.await(); job.cancel(); yield()
        assertFalse(job.isCompleted); assertEquals(listOf("hidden"), events)
        exit.complete(Unit); job.join(); assertEquals(listOf("hidden", "exited", "visible"), events)
    }

    @Test fun visibilityRestoredWhenCaptureFailsBeforeStarting() = runBlocking {
        val backend = Backend(ready = false); var restored = false
        val hook = object : ScreenCaptureVisibilityHook {
            override suspend fun beforeCapture() { error("hide failure") }
            override suspend fun afterCapture() { restored = true }
        }
        try { ScreenController(backend, backend, { "missing" }, hook).execute(ScreenAction("screen_capture", buildJsonObject {})); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(restored); assertTrue(backend.actions.isEmpty())
    }

    @Test fun imageDimensionsRespectBoundsWithoutUpscalingOrOverflow() {
        assertEquals(1066 to 1600, screenImageSize(2400, 3600))
        assertEquals(1600 to 800, screenImageSize(4000, 2000))
        assertEquals(1 to 1600, screenImageSize(1, 16000))
        assertEquals(320 to 240, screenImageSize(320, 240))
        for (size in listOf(0 to 100, 100 to -1, Int.MAX_VALUE to Int.MAX_VALUE, 16000 to 16000)) {
            assertThrows(IllegalArgumentException::class.java) { screenImageSize(size.first, size.second) }
        }
    }
}
