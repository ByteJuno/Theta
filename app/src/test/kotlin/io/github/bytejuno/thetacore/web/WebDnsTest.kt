package io.github.bytejuno.thetacore.web

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class WebDnsTest {
    private class PendingDns : WebDns {
        val started = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val cancellations = AtomicInteger()
        lateinit var callback: (Result<List<InetAddress>>) -> Unit
        override fun start(host: String, completed: (Result<List<InetAddress>>) -> Unit): () -> Unit {
            callback = completed
            active.incrementAndGet()
            started.complete(Unit)
            return { active.decrementAndGet(); cancellations.incrementAndGet(); Unit }
        }
    }
    @Test fun unresolvedDnsTimesOutAndReleasesQueryBeforeAnyHttpCall() = runBlocking {
        val dns = PendingDns(); val client = OkHttpClient()
        try {
            withTimeout(3000) {
                try { WebHttp(client, dns, requestTimeoutMillis = 100).get("https://web.test"); fail("Expected timeout") }
                catch (e: WebFailure) { assertEquals(WebError.NETWORK, e.reason) }
            }
            assertTrue(dns.started.isCompleted); assertEquals(1, dns.cancellations.get()); assertEquals(0, dns.active.get())
            assertEquals(0, client.dispatcher.runningCallsCount()); assertEquals(0, client.dispatcher.queuedCallsCount())
            dns.callback(Result.success(listOf(InetAddress.getLoopbackAddress()))) // Late callback cannot resume networking.
            assertEquals(0, client.dispatcher.runningCallsCount())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun cancellingUnresolvedDnsFinishesCallerAndCancelsQuery() = runBlocking {
        val dns = PendingDns(); val client = OkHttpClient()
        try {
            val call = launch { WebHttp(client, dns).get("https://web.test") }
            withTimeout(3000) { dns.started.await(); call.cancelAndJoin() }
            assertTrue(call.isCancelled); assertEquals(1, dns.cancellations.get()); assertEquals(0, dns.active.get())
            assertEquals(0, client.dispatcher.runningCallsCount())
            dns.callback(Result.failure(IllegalStateException("late failure")))
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun immediateResolverFailureBecomesNetworkError() = runBlocking {
        val dns = WebDns { _, completed -> completed(Result.failure(IllegalStateException("resolver unavailable"))); {} }
        try { WebHttp(dns = dns).get("https://web.test"); fail("Expected resolver failure") }
        catch (e: WebFailure) { assertEquals(WebError.NETWORK, e.reason) }
    }
}
