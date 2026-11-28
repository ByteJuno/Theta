package io.github.bytejuno.thetacore.web

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Nonblocking resolver contract: start a query and return its cancellation action.
 * Android's asynchronous resolver avoids an uninterruptible InetAddress/Dns.SYSTEM worker. */
internal fun interface WebDns {
    fun start(host: String, completed: (Result<List<InetAddress>>) -> Unit): () -> Unit
}

internal suspend fun WebDns.resolve(host: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
    try {
        val cancel = start(host) { result ->
            if (!continuation.isCancelled) result.fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(WebFailure(WebError.NETWORK)) },
            )
        }
        continuation.invokeOnCancellation { cancel() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        if (!continuation.isCancelled) continuation.resumeWithException(WebFailure(WebError.NETWORK))
    }
}

/** API 29+, within Theta's API 33 floor. Cancellation reaches the native DNS query;
 * no dedicated blocking thread or unbounded executor is allocated per request. */
internal object AndroidWebDns : WebDns {
    override fun start(host: String, completed: (Result<List<InetAddress>>) -> Unit): () -> Unit {
        if (InetAddresses.isNumericAddress(host)) {
            completed(Result.success(listOf(InetAddresses.parseNumericAddress(host))))
            return {}
        }
        val signal = CancellationSignal()
        DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, Executor { it.run() }, signal,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    completed(if (rcode == 0) Result.success(answer) else Result.failure(WebFailure(WebError.NETWORK)))
                }
                override fun onError(error: DnsResolver.DnsException) { completed(Result.failure(error)) }
            })
        return signal::cancel
    }
}
