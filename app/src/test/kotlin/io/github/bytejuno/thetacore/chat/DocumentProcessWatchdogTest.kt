package io.github.bytejuno.thetacore.chat

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DocumentProcessWatchdogTest {
    @Test fun deadlineDoesNotDependOnUncooperativeWorkerOrMainLooper() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = Thread {
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val watchdog = DocumentProcessWatchdog(25) { calls.incrementAndGet(); terminated.countDown() }
        try {
            worker.interrupt()
            assertTrue(terminated.await(2, TimeUnit.SECONDS))
            assertTrue(worker.isAlive) // An interrupt is not an exit receipt.
            watchdog.finish()
            assertEquals(1, calls.get())
        } finally { release.countDown(); worker.join(2000) }
    }

    @Test fun deadlineCoversPipeReadThatIgnoresInterrupts() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val terminated = CountDownLatch(1)
        val pipe = object : java.io.InputStream() {
            override fun read(): Int {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                return -1
            }
        }
        val worker = Thread { DocumentExtraction.readBounded(pipe) }.apply { isDaemon = true; start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val watchdog = DocumentProcessWatchdog(25) { terminated.countDown() }
        try {
            worker.interrupt()
            assertTrue(terminated.await(2, TimeUnit.SECONDS))
            assertTrue(worker.isAlive)
        } finally { watchdog.finish(); release.countDown(); worker.join(2000) }
    }

    @Test fun normalCompletionTerminatesExactlyOnce() {
        val calls = AtomicInteger()
        val watchdog = DocumentProcessWatchdog(60_000) { calls.incrementAndGet() }
        watchdog.finish(); watchdog.finish()
        assertEquals(1, calls.get())
    }
}
