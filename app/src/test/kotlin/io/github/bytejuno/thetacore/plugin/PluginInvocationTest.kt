package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.ToolEnv
import io.github.bytejuno.thetacore.sdk.ToolResult
import io.github.bytejuno.thetacore.sdk.ToolRisk
import io.github.bytejuno.thetacore.sdk.ToolSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class PluginInvocationTest {
    private val args = JsonObject(emptyMap())
    private fun tool(timeout: Long? = null, call: suspend (ToolEnv) -> ToolResult) = object : Tool {
        override val id = "tool"
        override val spec = ToolSpec("tool", "test", args, timeoutMs = timeout)
        override suspend fun call(args: JsonObject, env: ToolEnv) = call(env)
    }
    private fun invoke(budget: PluginExecutionBudget, owner: CoroutineScope, tool: Tool,
        idle: Long = 100, total: Long = 2_000) = PluginInvocation(
        budget, "p", owner, tool, args, ToolInvocationRequest("r", "s"),
        defaultTimeoutMillis = idle, totalTimeoutMillis = total,
    ).start()

    @Test fun blockedTimeoutRetainsSlotAndLateSuccessCannotReplaceTerminal() = runBlocking {
        val budget = PluginExecutionBudget(1, 1)
        val job = SupervisorJob()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val invocation = invoke(budget, CoroutineScope(job), tool {
                entered.countDown(); release.await(); ToolResult("late")
            })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val result = withTimeout(3_000) { invocation.terminal.await() }
            assertEquals(PluginInvocationStatus.TIMED_OUT, result.status)
            assertFalse(invocation.actualExited.isCompleted)
            assertEquals(1, budget.occupiedSlots())
            val rejected = invoke(budget, CoroutineScope(job), tool { fail("Not admitted"); ToolResult() })
            assertEquals(PluginInvocationStatus.REJECTED, rejected.terminal.await().status)
            release.countDown()
            withTimeout(3_000) { invocation.actualExited.await() }
            assertSame(result, invocation.terminal.await())
            assertEquals(0, budget.occupiedSlots())
        } finally { release.countDown(); budget.close(); job.cancel() }
    }

    @Test fun cancellationRetainsChildrenAndFinallyUntilActualExit() = runBlocking {
        val budget = PluginExecutionBudget(1, 1)
        val job = SupervisorJob()
        val childEntered = CountDownLatch(1)
        val childRelease = CountDownLatch(1)
        val finallyEntered = CountDownLatch(1)
        val finallyRelease = CountDownLatch(1)
        try {
            val invocation = invoke(budget, CoroutineScope(job), tool {
                kotlinx.coroutines.coroutineScope {
                    launch { withContext(NonCancellable) { childEntered.countDown(); childRelease.await() } }
                    try { awaitCancellation() }
                    finally { finallyEntered.countDown(); finallyRelease.await() }
                }
            }, idle = 2_000)
            assertTrue(childEntered.await(3, TimeUnit.SECONDS))
            invocation.cancel()
            assertEquals(PluginInvocationStatus.CANCELLED, invocation.terminal.await().status)
            assertEquals(1, budget.occupiedSlots())
            assertFalse(invocation.actualExited.isCompleted)
            childRelease.countDown()
            assertTrue(finallyEntered.await(3, TimeUnit.SECONDS))
            assertFalse(invocation.actualExited.isCompleted)
            finallyRelease.countDown()
            withTimeout(3_000) { invocation.actualExited.await() }
            assertEquals(0, budget.occupiedSlots())
        } finally { childRelease.countDown(); finallyRelease.countDown(); budget.close(); job.cancel() }
    }

    @Test fun progressRenewsIdleButCannotExtendTotalBudget() = runBlocking {
        val budget = PluginExecutionBudget()
        val job = SupervisorJob()
        val progressCount = AtomicInteger()
        try {
            val invocation = invoke(budget, CoroutineScope(job), tool(timeout = 150) { env ->
                while (true) {
                    env.progress("working", 0.5f)
                    progressCount.incrementAndGet()
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") ToolResult()
            }, total = 350)
            val result = withTimeout(3_000) { invocation.terminal.await() }
            assertEquals(PluginInvocationStatus.TIMED_OUT, result.status)
            assertTrue(progressCount.get() >= 3)
            withTimeout(3_000) { invocation.actualExited.await() }
            assertEquals("working", invocation.progress.value!!.text)
        } finally { budget.close(); job.cancel() }
    }

    @Test fun metadataIsBoundedAndFailureMessageDoesNotExecutePluginGetter() = runBlocking {
        val budget = PluginExecutionBudget(1, 1)
        val job = SupervisorJob()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val caller = Thread.currentThread()
        val malicious = object : Tool {
            override val id = "tool"
            override val spec: ToolSpec get() {
                assertNotSame(caller, Thread.currentThread())
                entered.countDown(); release.await()
                throw object : RuntimeException() {
                    override val message: String get() = error("Must not read message")
                }
            }
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = error("Must not call")
        }
        try {
            val invocation = invoke(budget, CoroutineScope(job), malicious)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(PluginInvocationStatus.TIMED_OUT, withTimeout(3_000) { invocation.terminal.await() }.status)
            assertEquals(1, budget.occupiedSlots())
            release.countDown()
            withTimeout(3_000) { invocation.actualExited.await() }
            assertEquals(PluginInvocationStatus.TIMED_OUT, invocation.terminal.await().status)
        } finally { release.countDown(); budget.close(); job.cancel() }
    }

    @Test fun thrownFailureIsSanitizedWithoutReadingThrowableMessage() = runBlocking {
        val budget = PluginExecutionBudget()
        val job = SupervisorJob()
        try {
            val invocation = invoke(budget, CoroutineScope(job), tool {
                throw object : RuntimeException() {
                    override val message: String get() = error("Plugin message getter must not run")
                }
            }, idle = 2_000)
            val result = withTimeout(3_000) { invocation.terminal.await() }
            assertEquals(PluginInvocationStatus.FAILED, result.status)
            assertTrue(result.message!!.startsWith("Tool failed: "))
            assertTrue(job.isActive)
            withTimeout(3_000) { invocation.actualExited.await() }
        } finally { budget.close(); job.cancel() }
    }

    @Test fun resultAndApprovalInputHooksAreRealAndUnattendedCannotPrompt() = runBlocking {
        val budget = PluginExecutionBudget()
        val job = SupervisorJob()
        val prompts = AtomicInteger()
        try {
            val invocation = PluginInvocation(budget, "p", CoroutineScope(job), tool { env ->
                assertFalse(env.requestApproval("write", ToolRisk.WRITE))
                assertNull(env.requestInput("value", args))
                ToolResult("actual result")
            }, args, ToolInvocationRequest("r", "s", unattended = true),
                requestApproval = { _, _ -> prompts.incrementAndGet(); true },
                requestInput = { _, _ -> prompts.incrementAndGet(); args }).start()
            val result = withTimeout(3_000) { invocation.terminal.await() }
            assertEquals(PluginInvocationStatus.SUCCEEDED, result.status)
            assertEquals("actual result", result.result!!.text)
            assertEquals(0, prompts.get())
            withTimeout(3_000) { invocation.actualExited.await() }
        } finally { budget.close(); job.cancel() }
    }
}
