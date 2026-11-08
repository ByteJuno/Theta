package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.EntryAdapter
import io.github.bytejuno.thetacore.sdk.EntryHost
import io.github.bytejuno.thetacore.sdk.EntryRequest
import io.github.bytejuno.thetacore.sdk.EntryUnavailableException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PluginEntryRuntimeTest {
    private fun adapter(attach: suspend (EntryHost) -> Unit) = object : EntryAdapter {
        override val id = "entry"
        override val displayName = "Entry"
        override fun isAvailable(device: DeviceInfo) = true
        override suspend fun attach(host: EntryHost) = attach(host)
    }

    @Test fun readyOnceHasNoListeningDeadlineAndNormalOwnerStopIsNotFailure() = runBlocking {
        val budget = PluginExecutionBudget()
        val job = SupervisorJob()
        val hostRef = CompletableDeferred<EntryHost>()
        val finalized = CountDownLatch(1)
        val runtime = PluginEntryRuntime(budget, "p", CoroutineScope(job), adapter { host ->
            try { host.ready(); host.ready(); hostRef.complete(host); awaitCancellation() }
            finally { finalized.countDown() }
        }, DeviceInfo(35), initializationTimeoutMillis = 100)
        try {
            runtime.start()
            val host = withTimeout(3_000) { hostRef.await() }
            val later = CompletableDeferred<Unit>()
            budget.schedule(200) { later.complete(Unit); Unit }
            withTimeout(3_000) { later.await() }
            assertEquals(EntryRuntimeStatus.READY, runtime.state.value.status)
            try { host.open(EntryRequest("hello")); fail("No fake backend") }
            catch (_: EntryUnavailableException) { }
            job.cancel()
            withTimeout(3_000) { runtime.actualExited.await(); job.join() }
            assertEquals(EntryRuntimeStatus.STOPPED, runtime.state.value.status)
            assertEquals(0L, finalized.count)
        } finally { runtime.close(); budget.close(); job.cancel() }
    }

    @Test fun lateReadyCannotReviveTimedOutBlockedAttach() = runBlocking {
        val budget = PluginExecutionBudget(1, 1)
        val job = SupervisorJob()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hostRef = AtomicReference<EntryHost>()
        val runtime = PluginEntryRuntime(budget, "p", CoroutineScope(job), adapter { host ->
            hostRef.set(host); entered.countDown(); release.await(); host.ready()
        }, DeviceInfo(35), initializationTimeoutMillis = 100)
        try {
            runtime.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { runtime.state.first { it.status == EntryRuntimeStatus.UNAVAILABLE } }
            assertEquals(1, budget.occupiedSlots())
            assertFalse(runtime.actualExited.isCompleted)
            hostRef.get().ready()
            assertEquals(EntryRuntimeStatus.UNAVAILABLE, runtime.state.value.status)
            release.countDown()
            withTimeout(3_000) { runtime.actualExited.await() }
            assertEquals(EntryRuntimeStatus.UNAVAILABLE, runtime.state.value.status)
        } finally { release.countDown(); runtime.close(); budget.close(); job.cancel() }
    }

    @Test fun returnFailureAndAvailabilityStayLocalAndMetadataRunsOffCaller() = runBlocking {
        for (mode in 0..2) {
            val budget = PluginExecutionBudget()
            val job = SupervisorJob()
            val caller = Thread.currentThread()
            val plugin = object : EntryAdapter {
                override val id = "entry"
                override val displayName: String get() { assertNotSame(caller, Thread.currentThread()); return "Entry" }
                override fun isAvailable(device: DeviceInfo): Boolean {
                    assertNotSame(caller, Thread.currentThread()); return mode != 2
                }
                override suspend fun attach(host: EntryHost) {
                    host.ready()
                    if (mode == 1) throw object : RuntimeException() {
                        override val message: String get() = error("Do not read plugin error messages")
                    }
                }
            }
            val runtime = PluginEntryRuntime(budget, "p", CoroutineScope(job), plugin, DeviceInfo(35))
            try {
                runtime.start()
                withTimeout(3_000) { runtime.actualExited.await() }
                assertEquals(EntryRuntimeStatus.UNAVAILABLE, runtime.state.value.status)
                assertTrue(job.isActive)
                assertNotNull(runtime.state.value.message)
            } finally { runtime.close(); budget.close(); job.cancel() }
        }
    }
}
