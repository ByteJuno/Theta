package io.github.bytejuno.thetacore.root

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Android API 33 compatible: no ProcessHandle, inherited credentials or startup root check. */
internal class SuRootBackend(private val scratch: File) : RootBackend {
    override fun start(command: String): RootProcess {
        scratch.mkdirs()
        val directory = java.nio.file.Files.createTempDirectory(scratch.toPath(), "call-").toFile()
        val builder = ProcessBuilder("su", "-c", rootSupervisorScript(command, directory))
        builder.environment().clear()
        builder.environment().putAll(rootProcessEnvironment())
        val process = try { builder.start() } catch (error: java.io.IOException) {
            directory.delete()
            throw error
        }
        return SuRootProcess(process, directory)
    }
}

internal fun rootProcessEnvironment() = mapOf(
    "PATH" to "/system/bin:/system/xbin:/sbin:/su/bin:/debug_ramdisk", "LANG" to "C",
)

private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/**
 * The session leader stays alive until the supervisor kills its entire process group. The
 * command never runs before the group identity has been recorded. A stdin watcher requests
 * cleanup on cancellation or host death; it is outside the command's process group.
 * Deliberate daemonization/setsid by arbitrary root code is outside this containment boundary.
 */
internal fun rootSupervisorScript(command: String, directory: File): String {
    val dir = shellQuote(directory.absolutePath)
    val child = """
        d=$dir
        printf '%s' "${'$'}${'$'}" > "${'$'}d/group"
        if [ -e "${'$'}d/stop" ]; then
            printf 125 > "${'$'}d/code"
            while :; do /system/bin/sleep 1; done
        fi
        /system/bin/env -i PATH=/system/bin:/system/xbin LANG=C /system/bin/sh -c ${shellQuote(command)}
        printf '%s' "${'$'}?" > "${'$'}d/code"
        while :; do /system/bin/sleep 1; done
    """.trimIndent()
    return """
        d=$dir
        # Receipts must be readable by the app UID; the containing directory is app-private 0700.
        umask 022
        if [ "${'$'}(/system/bin/id -u)" != 0 ]; then printf denied > "${'$'}d/rejected"; exit 125; fi
        printf ready > "${'$'}d/ready"
        if [ -e "${'$'}d/stop" ]; then printf clean > "${'$'}d/clean"; exit 125; fi
        if ! command -v setsid >/dev/null 2>&1; then printf unavailable > "${'$'}d/rejected"; exit 125; fi
        printf launching > "${'$'}d/launched"
        setsid -w /system/bin/sh -c ${shellQuote(child)} &
        launcher=${'$'}!
        # Explicit fd duplication avoids the shell's /dev/null stdin for asynchronous jobs.
        exec 3<&0
        ( read -r signal <&3; printf stop > "${'$'}d/stop" ) &
        watcher=${'$'}!
        while [ ! -s "${'$'}d/group" ]; do
            if ! /system/bin/kill -0 "${'$'}launcher" 2>/dev/null; then
                wait "${'$'}launcher"
                /system/bin/kill "${'$'}watcher" 2>/dev/null; wait "${'$'}watcher" 2>/dev/null
                printf failed > "${'$'}d/rejected"
                printf clean > "${'$'}d/clean"
                exit 125
            fi
            /system/bin/sleep 0.02
        done
        group=${'$'}(cat "${'$'}d/group")
        while [ ! -s "${'$'}d/code" ] && [ ! -e "${'$'}d/stop" ]; do /system/bin/sleep 0.02; done
        /system/bin/kill -KILL -- -"${'$'}group" 2>/dev/null
        wait "${'$'}launcher" 2>/dev/null
        # No receipt until the entire group has disappeared, not merely its leader.
        while /system/bin/kill -0 -- -"${'$'}group" 2>/dev/null; do /system/bin/sleep 0.02; done
        /system/bin/kill "${'$'}watcher" 2>/dev/null; wait "${'$'}watcher" 2>/dev/null
        printf clean > "${'$'}d/clean"
    """.trimIndent()
}

internal class BoundedOutput(stream: InputStream) {
    private val bytes = ByteArrayOutputStream()
    @Volatile var truncated = false
        private set
    @Volatile var failed = false
        private set
    val reader = thread(name = "theta-root-output", isDaemon = true) {
        try {
            stream.use {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    synchronized(bytes) {
                        val keep = minOf(count, ROOT_OUTPUT_LIMIT - bytes.size())
                        bytes.write(buffer, 0, keep)
                        if (keep < count) truncated = true
                    }
                }
            }
        } catch (_: java.io.IOException) { failed = true }
    }
    fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
}

internal class SuRootProcess(private val process: Process, private val directory: File) : RootProcess {
    private val stdout = BoundedOutput(process.inputStream)
    private val stderr = BoundedOutput(process.errorStream)
    private val stopped = AtomicBoolean()
    override val rootConfirmed get() = directory.resolve("ready").isFile
    override val commandStarted get() = directory.resolve("group").isFile
    private val cleaned get() = directory.resolve("clean").isFile
    override val actualExit: Boolean get() {
        // A killed su client alone says nothing about its daemon-owned privileged children.
        val safe = cleaned || !directory.resolve("launched").exists()
        if (stopped.get() && cleaned && process.isAlive) process.destroyForcibly()
        return safe && !process.isAlive && !stdout.reader.isAlive && !stderr.reader.isAlive
    }
    override val exitCode: Int? get() = directory.resolve("code").takeIf { it.isFile && it.length() <= 12 }
        ?.readText()?.toIntOrNull()
    override val outcome get() = when {
        !rootConfirmed -> RootOutcome.DENIED
        !commandStarted -> RootOutcome.START_FAILED
        exitCode == null || stdout.failed || stderr.failed -> RootOutcome.START_FAILED
        else -> RootOutcome.COMPLETED
    }
    override fun requestStop() {
        if (stopped.compareAndSet(false, true)) {
            // Written before touching stdin: a late user grant must not start a cancelled command.
            runCatching { directory.resolve("stop").writeText("stop") }
            // Closing the pipe also wakes the privileged watcher if writing fails.
            runCatching { process.outputStream.write("stop\n".toByteArray()); process.outputStream.flush() }
            runCatching { process.outputStream.close() }
            // Killing a waiting su client cannot prove its daemon will not launch the command.
            // Retain the worker until the supervisor receipt; the outer budget reports timeout.
        }
    }
    override fun output() = stdout.text() to stderr.text()
    override val outputTruncated get() = stdout.truncated || stderr.truncated
    override fun close() {
        check(actualExit)
        runCatching { process.outputStream.close() }
        // Only our fixed receipt files, after confirmed exit. Never traverse root-created paths.
        listOf("ready", "rejected", "launched", "group", "code", "stop", "clean").forEach {
            directory.resolve(it).delete()
        }
        directory.delete()
    }
}
