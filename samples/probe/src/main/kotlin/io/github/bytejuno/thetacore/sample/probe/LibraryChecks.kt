package io.github.bytejuno.thetacore.sample.probe

import android.util.Log
import io.github.bytejuno.thetacore.sdk.ThetaPlugin
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion

internal class LibraryCheck(val name: String, val run: suspend () -> Unit)

internal val libraryChecks = listOf(
    LibraryCheck("kotlin stdlib", ::checkStdlib),
    LibraryCheck("coroutines", ::checkCoroutines),
    LibraryCheck("serialization", ::checkSerialization),
    LibraryCheck("class visibility", ::checkClassVisibility),
    LibraryCheck("okhttp https", ::checkHttps),
)

private enum class Color { RED, GREEN }

private fun checkStdlib() {
    check(sequence { yield(1); yieldAll(listOf(2, 3)) }.map { it * 2 }.toList() == listOf(2, 4, 6))
    check(Regex("""(\d+)-(\d+)""").find("a 12-34")?.groupValues?.get(2) == "34")
    check(buildString { append("ab"); repeat(2) { append('c') } } == "abcc")
    check(listOf(3, 1, 2).sortedDescending().first() == 3)
    check(listOf("a", "bb").associateWith { it.length }.getValue("bb") == 2)
    check(runCatching { error("x") }.isFailure)
    check(30.seconds.inWholeMilliseconds == 30_000L)
    check(Random(1).nextInt(1, 2) == 1)
    check(lazy { 1 }.value == 1)
    check(ArrayDeque<Int>().apply { addFirst(1) }.first() == 1)
    check("abc".encodeToByteArray().decodeToString() == "abc")
    check(Color.entries.size == 2)
    check(Color::class.simpleName == "Color")
}

private suspend fun checkCoroutines() = coroutineScope {
    check((1..20).map { n -> async(Dispatchers.Default) { n * 2 } }.awaitAll().sum() == 420)
    check(flow { repeat(5) { emit(it) } }.map { it + 1 }.filter { it % 2 == 0 }.toList() == listOf(2, 4))

    val state = MutableStateFlow(0)
    launch { state.value = 1 }
    check(state.first { it == 1 } == 1)

    val channel = Channel<Int>(Channel.BUFFERED)
    launch {
        channel.send(7)
        channel.close()
    }
    check(channel.receive() == 7)

    val mutex = Mutex()
    mutex.withLock { check(mutex.isLocked) }
    check(withTimeoutOrNull(20) { delay(60_000) } == null)
    check(withContext(Dispatchers.IO) { Thread.currentThread().name.isNotEmpty() })
}

@Serializable
private data class Sample(val id: Int, val tags: List<String>, val extra: Map<String, Long> = emptyMap())

@Serializable
private sealed class Event {
    @Serializable
    @SerialName("ping")
    data class Ping(val count: Int) : Event()

    @Serializable
    @SerialName("stop")
    data object Stop : Event()
}

private fun checkSerialization() {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    val sample = Sample(1, listOf("a"), mapOf("k" to 2L))
    check(json.decodeFromString<Sample>(json.encodeToString(sample)) == sample)

    val events = listOf(Event.Ping(3), Event.Stop)
    check(json.decodeFromString<List<Event>>(json.encodeToString(events)) == events)

    check(Json.parseToJsonElement("""{"a":[1,2]}""").jsonObject.getValue("a").jsonArray.size == 2)
}

private fun checkClassVisibility() {
    val loader = checkNotNull(ProbePlugin::class.java.classLoader)

    // 宿主内部类看不见。MainActivity 在清单里，R8 不会改它的名字，确实存在。
    val hidden = runCatching { loader.loadClass("io.github.bytejuno.thetacore.MainActivity") }
    check(hidden.exceptionOrNull() is ClassNotFoundException) { "host class is visible" }

    // 平台类不受过滤器影响，不管它是什么包名。
    listOf(
        "android.os.Build",
        "dalvik.system.DexClassLoader",
        "java.util.concurrent.atomic.AtomicReference",
        "javax.net.ssl.SSLContext",
        "org.json.JSONObject",
        "org.xml.sax.InputSource",
    ).forEach(loader::loadClass)

    // 共享类来自宿主，自带的库来自插件。
    check(Json::class.java.classLoader !== loader) { "serialization is bundled" }
    check(ThetaPlugin::class.java.classLoader !== loader) { "sdk is bundled" }
    check(OkHttpClient::class.java.classLoader === loader) { "okhttp is not bundled" }
}

private const val HTTPS_CHECK_ID = "okhttp_https"
private const val HTTPS_CHECK_URL = "https://www.gstatic.com/generate_204"

private suspend fun checkHttps() = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    try {
        val request = Request.Builder().url(HTTPS_CHECK_URL).build()
        client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url
            val tls = response.handshake?.tlsVersion
            // Only the fixed public endpoint may appear in diagnostics; never log redirect data.
            val requestedEvidence = request.url.toString().takeIf { it == HTTPS_CHECK_URL } ?: "unexpected"
            val finalEvidence = finalUrl.toString().takeIf { it == HTTPS_CHECK_URL } ?: "unexpected"
            Log.i(
                "ThetaProbeHttps",
                "check=$HTTPS_CHECK_ID requested=$requestedEvidence final=$finalEvidence " +
                    "status=${response.code} tls=${tls?.javaName ?: "none"}",
            )
            check(request.url.isHttps && requestedEvidence == HTTPS_CHECK_URL) {
                "$HTTPS_CHECK_ID: unexpected requested endpoint"
            }
            check(finalUrl.isHttps && finalEvidence == HTTPS_CHECK_URL) {
                "$HTTPS_CHECK_ID: unexpected final endpoint"
            }
            check(response.code == 204) { "$HTTPS_CHECK_ID: HTTP ${response.code}" }
            check(tls == TlsVersion.TLS_1_2 || tls == TlsVersion.TLS_1_3) {
                "$HTTPS_CHECK_ID: expected TLSv1.2 or TLSv1.3"
            }
        }
    } finally {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
