package io.github.bytejuno.thetacore.agent

import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/** Real loopback sockets only. No internet requests, secrets, or model endpoint. */
class OkHttpModelTransportTest {
    @Test fun `cancelling blocked socket read disconnects server`(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val disconnected = CountDownLatch(1)
            val failure = CompletableDeferred<Throwable?>()
            val worker = thread(isDaemon = true, name = "s2-loopback") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: error("Unexpected EOF")
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n: heartbeat\n\n".toByteArray())
                            flush()
                        }
                        assertEquals(-1, input.read())
                        disconnected.countDown()
                    }
                    failure.complete(null)
                } catch (error: Throwable) { failure.complete(error) }
            }
            val received = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                OkHttpModelTransport().exchange("http://127.0.0.1:${server.localPort}/chat/completions", "{}", "")
                    .collect { if (it is TransportEvent.Bytes) received.complete(Unit) }
            }
            try {
                withTimeout(5_000) { received.await() }
                job.cancelAndJoin()
                assertTrue(withContext(Dispatchers.IO) { disconnected.await(5, TimeUnit.SECONDS) }, "Cancellation did not close socket")
                failure.await()?.let { throw it }
            } finally { job.cancel(); server.close(); worker.join(5_000) }
        }
    }

    @Test fun `immediate 503 retry hint never resubmits generation`(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true, name = "s2-no-retry") {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 503 Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                    }
                }
            }
            // A retry would connect again and hang because no second response is served.
            val events = withTimeout(5_000) {
                OkHttpModelTransport().exchange("http://127.0.0.1:${server.localPort}/", "{}", "").toList()
            }
            val headers = events.filterIsInstance<TransportEvent.Headers>().single()
            assertEquals(503, headers.status); assertEquals("0", headers.retryAfter)
            worker.join(5_000)
        }
    }

    @Test fun `redirect is surfaced without following or retrying`(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val requests = AtomicInteger()
            val worker = thread(isDaemon = true, name = "s2-redirect") {
                server.accept().use { socket ->
                    requests.incrementAndGet()
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:1/never\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                    }
                }
            }
            val events = withTimeout(5_000) {
                OkHttpModelTransport().exchange("http://127.0.0.1:${server.localPort}/", "{}", "").toList()
            }
            assertEquals(307, events.filterIsInstance<TransportEvent.Headers>().single().status)
            assertEquals(1, requests.get())
            worker.join(5_000)
        }
    }
}
