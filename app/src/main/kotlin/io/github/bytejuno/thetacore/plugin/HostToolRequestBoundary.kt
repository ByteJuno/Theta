package io.github.bytejuno.thetacore.plugin

import kotlinx.coroutines.runBlocking

/** Production callers suspend, including callers already on a saturated IO dispatcher.
 * The blocking adapter is exclusively for Android's dedicated Instrumentation.onStart thread.
 * It rejects ordinary workers and coroutine pools BEFORE starting/awaiting any IO work. It
 * neither creates threads per request nor assumes that moving runBlocking frees its caller.
 */
internal class HostToolRequestBoundary(private val handle: suspend (String) -> String) {
    suspend fun request(encoded: String): String = handle(encoded)

    fun requestForInstrumentation(encoded: String): String {
        check(Thread.currentThread().javaClass.name == "android.app.Instrumentation\$InstrumentationThread") {
            "Blocking host requests require Android Instrumentation.onStart; production callers must suspend"
        }
        return runBlocking { request(encoded) }
    }
}
