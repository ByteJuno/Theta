package io.github.bytejuno.thetacore.plugin

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HostToolRequestBoundaryTest {
    @Test fun ordinaryWorkersCannotEnterBlockingAdapter() = runBlocking {
        val entered = AtomicInteger()
        val boundary = HostToolRequestBoundary { entered.incrementAndGet(); it }
        withContext(Dispatchers.IO) {
            try {
                boundary.requestForInstrumentation("request")
                fail("Coroutine worker accepted by blocking adapter")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("must suspend"))
            }
        }
        assertEquals(0, entered.get())
    }

    @Test fun saturatedCallerExecutorCanAlsoRunAwaitedService() = runBlocking {
        // A blocking API deadlocks here: all caller slots would be held while service IO
        // is queued to that same executor. Suspending callers release every slot.
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val allEntered = CompletableDeferred<Unit>()
            val entered = AtomicInteger()
            val boundary = HostToolRequestBoundary { request ->
                if (entered.incrementAndGet() == 16) allEntered.complete(Unit)
                allEntered.await()
                withContext(dispatcher) { yield(); request }
            }
            withTimeout(5_000) {
                val results = (1..16).map { index ->
                    async(dispatcher) { boundary.request(index.toString()) }
                }.awaitAll()
                assertEquals((1..16).map(Int::toString), results)
            }
        }
    }
}
