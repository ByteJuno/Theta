package io.github.bytejuno.thetacore.root

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RootBridgeTest {
    private class Process : RootProcess {
        override var rootConfirmed = true
        override var commandStarted = true
        @Volatile override var actualExit = true
        override var outcome = RootOutcome.COMPLETED
        override var exitCode: Int? = 0
        override var outputTruncated = false
        var stdout = "ok"
        var stderr = "warning"
        val stopped = CompletableDeferred<Unit>()
        var closed = false
        override fun requestStop() { stopped.complete(Unit) }
        override fun output() = stdout to stderr
        override fun close() { check(actualExit); closed = true }
    }
    private fun env() = ToolEnv("run", "session", "call", false, { _, _ -> })
    private fun args(command: String = "id", timeout: Long = 30_000) = buildJsonObject {
        put("command", command); put("timeoutMs", timeout)
    }

    @Test fun constructionAndMetadataDoNotStartSuAndExecutionPreservesOutput() = runBlocking {
        val starts = AtomicInteger()
        val p = Process()
        var confirmed = false
        val tool = HostRootTool(RootBridge(RootBackend { starts.incrementAndGet(); p }, { confirmed = it }))
        assertEquals(ToolRisk.ROOT, tool.spec.risk)
        assertFalse(tool.spec.idempotent)
        assertFalse(tool.spec.parallelSafe)
        assertEquals("root-shell", tool.spec.exclusiveResource)
        assertEquals(0, starts.get())
        val result = tool.call(args(), env())
        assertEquals(ToolEffect.APPLIED, result.effect)
        assertEquals("ok", result.structured!!.jsonObject.getValue("stdout").jsonPrimitive.content)
        assertEquals("warning", result.structured!!.jsonObject.getValue("stderr").jsonPrimitive.content)
        assertTrue(confirmed)
        assertTrue(p.closed)
    }

    @Test fun timeoutRequestsCleanupButDoesNotReturnOrReleaseUntilActualExit() = runBlocking {
        val p = Process().apply { actualExit = false }
        var clock = 0L
        val bridge = RootBridge(RootBackend { p }, nowNanos = { clock.also { clock = 2_000_000 } })
        val job = async { bridge.execute("sleep", 1) }
        withTimeout(5_000) { p.stopped.await() }
        assertFalse(job.isCompleted)
        assertFalse(p.closed)
        p.actualExit = true
        val result = withTimeout(5_000) { job.await() }
        assertEquals(RootOutcome.TIMED_OUT, result.outcome)
        assertTrue(result.actualExit)
        assertTrue(p.closed)
    }

    @Test fun cancellationPropagatesOnlyAfterCleanupAndRetainsTheWorker() = runBlocking {
        val p = Process().apply { actualExit = false }
        val entered = CompletableDeferred<Unit>()
        val job = launch { RootBridge(RootBackend { entered.complete(Unit); p }).execute("sleep") }
        entered.await()
        job.cancel()
        withTimeout(5_000) { p.stopped.await() }
        assertFalse(job.isCompleted)
        assertFalse(p.closed)
        p.actualExit = true
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue(p.closed)
    }

    @Test fun unavailableAndDeniedAreClearNonExecutionFailures() = runBlocking {
        for (backend in listOf(RootBackend { throw IOException("sensitive detail") },
            RootBackend { Process().apply { rootConfirmed = false; commandStarted = false; outcome = RootOutcome.DENIED; exitCode = null } })) {
            val result = HostRootTool(RootBridge(backend)).call(args(), env())
            assertTrue(result.isError)
            assertEquals(ToolEffect.NONE, result.effect)
            assertFalse(result.structured.toString().contains("sensitive detail"))
            assertTrue(result.structured!!.jsonObject.getValue("status").jsonPrimitive.content in listOf("denied", "unavailable"))
        }
    }

    @Test fun nonzeroExitAndTimeoutAreUnknownEvenAfterConfirmedExit() = runBlocking {
        val failed = HostRootTool(RootBridge(RootBackend { Process().apply { exitCode = 7 } })).call(args(), env())
        assertTrue(failed.isError)
        assertEquals(ToolEffect.UNKNOWN, failed.effect)
        val p = Process().apply { actualExit = false }
        var clock = 0L
        val tool = HostRootTool(RootBridge(RootBackend { p }, nowNanos = { clock.also { clock = 2_000_000 } }))
        val job = async { tool.call(args(timeout = 1), env()) }
        p.stopped.await(); p.actualExit = true
        val result = job.await()
        assertEquals(ToolEffect.UNKNOWN, result.effect)
        assertTrue(result.isError)
    }

    @Test fun invalidCommandsAndTimeoutsNeverStartAProcess() = runBlocking {
        val starts = AtomicInteger()
        val tool = HostRootTool(RootBridge(RootBackend { starts.incrementAndGet(); Process() }))
        for (input in listOf(args(" "), args("a\u0000b"), args("x".repeat(16_385)), args(timeout = 0),
            args(timeout = ROOT_MAX_TIMEOUT_MS + 1), Json.parseToJsonElement("""{"command":"id","timeoutMs":"5"}""").jsonObject)) {
            try { tool.call(input, env()); fail("invalid input accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, starts.get())
    }

    @Test fun processEnvironmentIsAnAllowlistAndCommandIsShellQuoted() {
        assertEquals(setOf("PATH", "LANG"), rootProcessEnvironment().keys)
        val script = rootSupervisorScript("printf '%s' 'a; b'\nexit 7", java.io.File("/private/a'b"))
        assertTrue(script.contains("env -i"))
        assertTrue(script.contains("kill -KILL -- -"))
        assertTrue(script.contains("while /system/bin/kill -0 -- -"))
        assertTrue(script.contains("a'\\''b"))
    }

    @Test fun outputReaderDrainsHugeStreamsWithABoundedCapture() {
        val stream = object : java.io.InputStream() {
            var remaining = ROOT_OUTPUT_LIMIT * 4
            override fun read(): Int = if (remaining-- > 0) 120 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, len)
                b.fill(120, off, off + count); remaining -= count
                return count
            }
        }
        val output = BoundedOutput(stream)
        output.reader.join(5_000)
        assertFalse(output.reader.isAlive)
        assertEquals(0, stream.remaining)
        assertEquals(ROOT_OUTPUT_LIMIT, output.text().toByteArray().size)
        assertTrue(output.truncated)
        assertFalse(output.failed)
    }
}
