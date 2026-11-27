package io.github.bytejuno.thetacore.root

import kotlinx.coroutines.*

internal const val ROOT_MAX_TIMEOUT_MS = 120_000L
internal const val ROOT_OUTPUT_LIMIT = 1024 * 1024

internal enum class RootOutcome { COMPLETED, UNAVAILABLE, DENIED, START_FAILED, TIMED_OUT, CANCELLED }
internal data class RootResult(
    val outcome: RootOutcome,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val outputTruncated: Boolean,
    val actualExit: Boolean,
    val commandStarted: Boolean,
)

/** A backend owns all readers and subprocesses until actualExit, including after cancellation. */
internal interface RootProcess : AutoCloseable {
    val rootConfirmed: Boolean
    val commandStarted: Boolean
    val actualExit: Boolean
    val outcome: RootOutcome
    val exitCode: Int?
    fun requestStop()
    fun output(): Pair<String, String>
    val outputTruncated: Boolean
}

internal fun interface RootBackend { fun start(command: String): RootProcess }

/** No process, root prompt or IO at construction/metadata discovery time. */
internal class RootBridge(
    private val backend: RootBackend,
    private val onRootConfirmed: (Boolean) -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime,
) {
    suspend fun execute(command: String, timeoutMs: Long = 30_000): RootResult {
        require(command.isNotBlank() && command.length <= 16_384 && '\u0000' !in command)
        require(timeoutMs in 1..ROOT_MAX_TIMEOUT_MS)
        currentCoroutineContext().ensureActive()
        val process = try { backend.start(command) } catch (_: java.io.IOException) {
            onRootConfirmed(false)
            return RootResult(RootOutcome.UNAVAILABLE, null, "", "", false, true, false)
        }
        var outcome: RootOutcome? = null
        var result: RootResult? = null
        val deadline = nowNanos() + timeoutMs * 1_000_000
        try {
            while (!process.actualExit) {
                if (nowNanos() >= deadline) { outcome = RootOutcome.TIMED_OUT; break }
                delay(20)
            }
        } catch (cancelled: CancellationException) {
            outcome = RootOutcome.CANCELLED
            throw cancelled
        } finally {
            // The outer execution budget publishes logical timeout/cancellation independently.
            // Never return the worker (and its lease) while a privileged child is unconfirmed.
            withContext(NonCancellable) {
                if (!process.actualExit) process.requestStop()
                while (!process.actualExit) delay(20)
                onRootConfirmed(process.rootConfirmed)
                val (stdout, stderr) = process.output()
                result = RootResult(outcome ?: process.outcome, process.exitCode, stdout, stderr,
                    process.outputTruncated, process.actualExit, process.commandStarted)
                process.close()
            }
        }
        return checkNotNull(result)
    }
}
