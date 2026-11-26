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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class PluginEntryRuntimeTest {
    @Test fun closeDuringBackendOpenClosesLateHandleAndCannotReviveRuntime() = runBlocking {
        val budget = PluginExecutionBudget()
        val job = SupervisorJob()
        val hostRef = CompletableDeferred<EntryHost>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val session = object : io.github.bytejuno.thetacore.sdk.EntrySession {
            override val events = emptyFlow<io.github.bytejuno.thetacore.sdk.SessionEvent>()
            override suspend fun send(text: String) = Unit
            override fun close() { closed.incrementAndGet() }
        }
        val runtime = PluginEntryRuntime(budget, "p", CoroutineScope(job), adapter { host ->
            host.ready(); hostRef.complete(host); awaitCancellation()
        }, DeviceInfo(33), openSession = { _, _ -> entered.complete(Unit); release.await(); session })
        try {
            runtime.start()
            val host = withTimeout(3_000) { hostRef.await() }
            val opening = async { runCatching { host.open(EntryRequest("hello")) } }
            withTimeout(3_000) { entered.await() }
            runtime.close(); release.complete(Unit)
            assertTrue(opening.await().exceptionOrNull() is EntryUnavailableException)
            assertEquals(1, closed.get())
            assertEquals(EntryRuntimeStatus.STOPPED, runtime.state.value.status)
        } finally { release.complete(Unit); runtime.close(); job.cancel(); budget.close() }
    }

    @Test fun registrationRevocationRejectsRetainedHostBeforeMainRefresh() = runBlocking {
        val budget = PluginExecutionBudget()
        val job = SupervisorJob()
        val hostRef = CompletableDeferred<EntryHost>()
        val registered = java.util.concurrent.atomic.AtomicBoolean(true)
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val runtime = PluginEntryRuntime(budget, "p", CoroutineScope(job), adapter { host ->
            host.ready(); hostRef.complete(host); awaitCancellation()
        }, DeviceInfo(33), openSession = { _, _ -> opens.incrementAndGet(); throw EntryUnavailableException() },
            isRegistered = registered::get)
        try {
            runtime.start()
            val host = withTimeout(3_000) { hostRef.await() }
            registered.set(false)
            try { host.open(EntryRequest("revoked")); fail("Revoked") } catch (_: EntryUnavailableException) { }
            assertEquals(0, opens.get())
        } finally { runtime.close(); job.cancel(); budget.close() }
    }

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
