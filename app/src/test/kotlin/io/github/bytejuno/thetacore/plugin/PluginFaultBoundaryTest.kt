package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.PluginClosedException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PluginFaultBoundaryTest {
    @Test fun sharedHostSchedulingWaitsForOperationsButFaultObservationDoesNot() = runBlocking {
        val registry = PluginRegistrations<String>()
        val operations = Mutex()
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val waitingOnLock = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context, Runnable {
                    block.run() // First dispatch returns only after suspending at the held mutex.
                    waitingOnLock.complete(Unit)
                })
            }
        }
        val handled = kotlinx.coroutines.CompletableDeferred<ManagedPluginFault>()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val calls = AtomicInteger()
        val error = IllegalStateException("child init fault")
        lateinit var boundary: PluginFaultBoundary
        boundary = PluginFaultBoundary({ registry.beginClose(); Unit }, {
            postPluginOperation(host, dispatcher, operations, { throw AssertionError(it) }) {
                boundary.takeFault(boundary)?.let {
                    events += "handled"
                    calls.incrementAndGet()
                    handled.complete(it)
                }
            }
            posted.complete(Unit)
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + boundary.handler)
        val releaseWorker = CountDownLatch(1)
        val worker = PluginWorker("contended-init-observation") { releaseWorker.await(); Unit }
        registry.register("page", "page")
        try {
            operations.withLock {
                val child = scope.launch { throw error }
                assertSame(error, withTimeout(2_000) { boundary.failure.await() })
                withTimeout(2_000) { posted.await(); waitingOnLock.await(); child.join() }
                assertSame(error, worker.await(2_000, boundary)!!.exceptionOrNull())
                assertFalse(handled.isCompleted)
                assertEquals(0, calls.get())
                assertThrows(PluginClosedException::class.java) { registry.register("late", "late") }
                assertFalse(boundary.completeStart({ fail("must not persist") }, { fail("must not publish") }))
                events += "observed-before-release"
            }
            val fault = withTimeout(2_000) { handled.await() }
            assertSame(error, fault.error)
            assertTrue(fault.duringInitialization)
            assertEquals(listOf("observed-before-release", "handled"), events.toList())
            boundary.report(IllegalStateException("duplicate"))
            assertEquals(1, calls.get())
            assertNull(boundary.takeFault(boundary))
        } finally { releaseWorker.countDown(); scope.cancel(); host.cancel() }
    }

    @Test fun runtimeFailureIsNotAnInitializationRollback() = runBlocking {
        val boundary = PluginFaultBoundary({}, {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + boundary.handler)
        try {
            assertTrue(boundary.completeStart({}, {}))
            val child = scope.launch { error("runtime failure") }
            withTimeout(2_000) { child.join() }
            assertFalse(boundary.takeFault(boundary)!!.duringInitialization)
        } finally { scope.cancel() }
    }

    @Test fun actualCancelledScopeDoesNotCreateFaultAndCleanupRunsOnce() = runBlocking {
        val registry = PluginRegistrations<String>()
        val calls = AtomicInteger()
        registry.onCleanup { calls.incrementAndGet(); Unit }
        val boundary = PluginFaultBoundary({ registry.beginClose(); Unit }, { fail("cancel is not a fault") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + boundary.handler)
        val child = scope.launch { throw CancellationException("normal stop") }
        withTimeout(2_000) { child.join() }
        boundary.close()
        val actions = registry.beginClose()!!
        val cleanup = pluginCleanupWorker("cancel-scope-test", { scope.cancel() }, {}, actions)
        assertTrue(cleanup.await(2_000)!!.getOrThrow().isEmpty())
        assertEquals(1, calls.get())
        assertNull(registry.beginClose())
        assertFalse(boundary.hasFault())
        boundary.report(IllegalStateException("late cancelled child"))
        assertFalse(boundary.hasFault())
    }

    @Test fun cancellationDuplicatesAndLateFaultsAreNoOps() {
        var closed = 0
        var posts = 0
        val boundary = PluginFaultBoundary({ closed++ }, { posts++ })
        boundary.report(CancellationException("ordinary cancellation"))
        assertFalse(boundary.hasFault())
        boundary.report(IllegalStateException("first"))
        repeat(5) { boundary.report(IllegalStateException("duplicate")) }
        assertEquals(1, closed)
        assertEquals(1, posts)
        val other = PluginFaultBoundary({}, {})
        assertNull(boundary.takeFault(other)) // Stale callback must not consume another runtime.
        assertEquals("first", boundary.takeFault(boundary)!!.error.message)
        val alreadyClosed = PluginFaultBoundary({ fail("late close") }, { fail("late post") })
        alreadyClosed.close()
        alreadyClosed.report(IllegalStateException("after disable"))
        assertFalse(alreadyClosed.hasFault())
    }

    @Test fun throwingHandlerInfrastructureNeverEscapes() = runBlocking {
        val boundary = PluginFaultBoundary(
            { error("close failure") }, { error("dispatch failure") }, { error("logger failure") },
        )
        boundary.report(IllegalStateException("plugin failure"))
        assertTrue(boundary.failure.isCompleted)
        assertFalse(boundary.completeStart({ fail("persist") }, { fail("revival") }))
    }

    @Test fun delayedPublicationReadsLatestAndNeverRevivesClosedEntries() {
        val queue = mutableListOf<() -> Unit>()
        var visible = emptyList<String>()
        lateinit var publication: PluginPublication<String>
        publication = PluginPublication { queue += { visible = publication.current() } }
        val registry = PluginRegistrations<String>(publication::update)
        registry.register("page", "old")
        registry.beginClose()
        queue.asReversed().forEach { it() } // Deliberately dispatch the old notification last.
        assertTrue(visible.isEmpty())
        assertThrows(PluginClosedException::class.java) { registry.register("page", "new") }
    }

    @Test fun failureAndPublishHaveOneLinearizationOrder() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val go = CountDownLatch(1)
                val boundary = PluginFaultBoundary({}, {})
                val published = AtomicInteger()
                val publication = executor.submit(Callable {
                    go.await()
                    runBlocking { boundary.completeStart({}, { published.incrementAndGet(); Unit }) }
                })
                val faulting = executor.submit(Callable { go.await(); boundary.report(IllegalStateException("race")) })
                go.countDown()
                val won = publication.get(2, TimeUnit.SECONDS)
                faulting.get(2, TimeUnit.SECONDS)
                assertEquals(if (won) 1 else 0, published.get())
                val fault = boundary.takeFault(boundary)!!
                if (won) assertFalse(fault.duringInitialization)
                if (fault.duringInitialization) assertFalse(won)
                // A fault between arbitration and publication is runtime, without publication.
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun initFaultWakesHostEvenIfEnableWorkerIgnoresCancellation() = runBlocking {
        val release = CountDownLatch(1)
        val boundary = PluginFaultBoundary({}, {})
        val worker = PluginWorker("managed-fault-stuck-init") { release.await(); Unit }
        try {
            boundary.report(IllegalStateException("scope child"))
            val result = worker.await(2_000, boundary)!!
            assertEquals("scope child", result.exceptionOrNull()!!.message)
            assertNull(worker.await(20)) // Fault notification did not pretend the worker exited.
        } finally { release.countDown() }
        assertTrue(worker.await(2_000)!!.isSuccess)
    }

    @Test fun durableCommitPrecedesPublicationAndFaultDuringIoNeverPublishes() = runBlocking {
        val boundary = PluginFaultBoundary({}, {})
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var active = false
        val start = launch {
            assertFalse(boundary.completeStart(
                persist = {
                    events += "io-start"
                    entered.complete(Unit)
                    release.await()
                    events += "io-durable"
                },
                publish = { active = true; events += "publish" },
            ))
        }
        withTimeout(2_000) { entered.await() }
        assertFalse(active)
        // Real competing thread: report must finish while durable IO is suspended.
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { boundary.report(IllegalStateException("during commit")) }.get(2, TimeUnit.SECONDS)
        } finally { executor.shutdownNow(); release.complete(Unit) }
        withTimeout(2_000) { start.join() }
        assertFalse(active)
        assertEquals(listOf("io-start", "io-durable"), events)
        assertFalse(boundary.takeFault(boundary)!!.duringInitialization)
    }

    @Test fun publicationRequiresBothDurableWritesAndIoFailureCannotExposePages() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(PluginFaultBoundary({}, {}).completeStart(
            { events += "updates.started"; events += "state.initializing=false" },
            { events += "active-and-running" },
        ))
        assertEquals(listOf("updates.started", "state.initializing=false", "active-and-running"), events)
        val error = java.io.IOException("state write")
        try {
            PluginFaultBoundary({}, {}).completeStart({ throw error }, { fail("pages exposed") })
            fail("missing IO error")
        } catch (caught: java.io.IOException) { assertSame(error, caught) }
    }

    @Test fun hostileThrowableDiagnosticsAndThrowingReporterCannotSkipPersistenceOrCleanup() = runBlocking {
        val diagnosticCalls = AtomicInteger()
        val hostile = object : RuntimeException() {
            override fun toString(): String { diagnosticCalls.incrementAndGet(); error("toString invoked") }
            override val message: String get() { diagnosticCalls.incrementAndGet(); error("message invoked") }
        }
        val registry = PluginRegistrations<String>()
        val events = mutableListOf<String>()
        var actions = emptyList<() -> Unit>()
        val cleanupCalls = AtomicInteger()
        registry.onCleanup { cleanupCalls.incrementAndGet(); throw hostile }
        val boundary = PluginFaultBoundary({ actions = registry.beginClose().orEmpty() }, {})
        boundary.report(hostile)
        suspend fun handle() {
            val fault = boundary.takeFault(boundary) ?: return
            finishPluginFault(
                persist = { events += "persist"; Unit },
                report = {
                    assertEquals(hostile.javaClass.name, pluginFailureIdentity(fault.error))
                    reportPluginFailure({ throw hostile }, "fault", fault.error)
                    events += "report"
                    throw hostile
                },
                close = {
                    events += "close"
                    val worker = pluginCleanupWorker("hostile-cleanup", {}, { throw hostile }, actions)
                    val errors = worker.await(2_000)!!.getOrThrow()
                    assertEquals(2, errors.size)
                    errors.forEach { reportPluginFailure({}, "cleanup", it) }
                },
            )
        }
        handle()
        handle()
        assertEquals(listOf("persist", "report", "close"), events)
        assertEquals(1, cleanupCalls.get())
        assertEquals(0, diagnosticCalls.get())
    }

    @Test fun acceptedRollbackNeverRefreshesAndAlwaysCloses() = runBlocking {
        var active = true
        var affordance = true
        var deferred = false
        var refreshes = 0
        requestPluginRollback(
            request = {},
            refreshRejected = { refreshes++; throw java.io.IOException("refresh unavailable") },
            accepted = { deferred = true; affordance = false },
            close = { active = false },
        )
        assertEquals(0, refreshes)
        assertTrue(deferred)
        assertFalse(affordance)
        assertFalse(active)
        val original = java.io.IOException("request failed")
        try {
            requestPluginRollback({ throw original }, { throw IllegalStateException("refresh") },
                { fail("not accepted") }, { fail("do not close rejected request") })
            fail("missing request error")
        } catch (caught: java.io.IOException) { assertSame(original, caught) }
    }

    @Test fun failureIdentityKeepsHostPackageMessagesOnly() {
        assertEquals("Initialization timed out", pluginFailureIdentity(PluginPackageException("Initialization timed out")))
        assertEquals("java.lang.IllegalStateException", pluginFailureIdentity(IllegalStateException("plugin text")))
    }

    @Test fun failedStartPersistenceKeepsConcurrentFaultFirstStart() = runBlocking {
        val boundary = PluginFaultBoundary({}, {})
        val failure = java.io.IOException("disk")
        try {
            boundary.completeStart(
                {
                    boundary.report(IllegalStateException("late"))
                    throw failure
                },
                { fail("nothing durable was written") },
            )
            fail("missing IO error")
        } catch (caught: java.io.IOException) { assertSame(failure, caught) }
        assertTrue(boundary.takeFault(boundary)!!.duringInitialization)
    }

    @Test fun faultAfterSuccessfulStartPersistenceStaysRuntime() = runBlocking {
        val boundary = PluginFaultBoundary({}, {})
        assertFalse(boundary.completeStart(
            { boundary.report(IllegalStateException("late")) },
            { fail("faulted instance published") },
        ))
        assertFalse(boundary.takeFault(boundary)!!.duringInitialization)
    }

    @Test fun cleanupFailureDoesNotReplaceFaultPersistenceFailure() = runBlocking {
        val failure = java.io.IOException("persist")
        try {
            finishPluginFault(
                persist = { throw failure },
                report = { fail("nothing to report") },
                close = { error("close") },
            )
            fail("missing IO error")
        } catch (caught: java.io.IOException) { assertSame(failure, caught) }
    }
}
