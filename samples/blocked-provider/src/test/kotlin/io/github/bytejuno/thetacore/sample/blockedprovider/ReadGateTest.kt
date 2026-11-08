package io.github.bytejuno.thetacore.sample.blockedprovider

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class ReadGateTest {
    @Test fun immediateDoesNotWaitForRelease() {
        assertEquals("IMMEDIATE", ReadGate(ReadMode.IMMEDIATE).await())
    }

    @Test fun blockedReadRequiresReleaseAndDoesNotReleaseNextSession() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = ReadGate(ReadMode.BLOCK)
        try {
            val entered = CountDownLatch(1)
            val pending = executor.submit<String> { entered.countDown(); gate.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try {
                pending.get(50, TimeUnit.MILLISECONDS)
                fail("Blocked gate returned before release")
            } catch (_: TimeoutException) { /* expected */ }
            gate.release()
            assertEquals("RELEASED", pending.get(2, TimeUnit.SECONDS))
            assertEquals("TIMEOUT", ReadGate(ReadMode.BLOCK, lifetimeMillis = 5).await())
        } finally {
            gate.abort("TEST_END")
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun abortWinsOverRelease() {
        val gate = ReadGate(ReadMode.BLOCK)
        gate.release()
        gate.abort("RESET")
        assertEquals("RESET", gate.await())
        gate.release()
        assertEquals("RESET", gate.failure())
    }

    @Test fun openCancellationUnblocksWaiter() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = ReadGate(ReadMode.BLOCK)
        try {
            val pending = executor.submit<String> { gate.await() }
            gate.abort("OPEN_CANCEL")
            assertEquals("OPEN_CANCEL", pending.get(2, TimeUnit.SECONDS))
        } finally {
            gate.abort("TEST_END")
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun delayedReadFinishesWithoutRelease() {
        assertEquals("DELAY_DONE", ReadGate(ReadMode.DELAY, delayMillis = 5).await())
    }

    @Test fun lifetimeIsAnAbortNotAutomaticSuccess() {
        val gate = ReadGate(ReadMode.DELAY, delayMillis = 10_000, lifetimeMillis = 5)
        assertEquals("TIMEOUT", gate.await())
        gate.release()
        assertEquals("TIMEOUT", gate.failure())
    }
}
