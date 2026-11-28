package io.github.bytejuno.thetacore.screen

import android.content.Context
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.root.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

internal fun screenShellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/** Only uses an already explicitly confirmed root grant; never probes su on discovery. */
internal class RootScreenBackend(
    private val context: Context,
    private val bridge: RootBridge,
    private val authorized: () -> Boolean = { RootRuntime.rootReady },
) : ScreenControlBackend {
    override val ready get() = authorized()
    override suspend fun execute(action: ScreenAction): ScreenReply = withContext(Dispatchers.IO) {
        if (!ready) return@withContext unavailableScreenReply(context.getString(R.string.screen_permission_required))
        val parent = File(context.noBackupFilesDir, "screen-calls").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "call-").toFile()
        val data = File(directory, "data")
        try {
            val output = screenShellQuote(data.absolutePath)
            val command = when (action.name) {
                // A 16 MiB per-file cap also bounds a malicious/broken producer before allocation.
                "screen_capture" -> "ulimit -f 32768; screencap -p > $output"
                "screen_ui_tree" -> "ulimit -f 2048; uiautomator dump --compressed $output >/dev/null"
                "screen_tap" -> "input tap ${action.number("x")} ${action.number("y") }"
                "screen_long_press" -> "input swipe ${action.number("x")} ${action.number("y")} ${action.number("x")} ${action.number("y")} ${action.number("durationMs", 800)}"
                "screen_swipe" -> "input swipe ${action.number("x1")} ${action.number("y1")} ${action.number("x2")} ${action.number("y2")} ${action.number("durationMs", 400)}"
                "screen_key" -> "input keyevent " + mapOf("back" to 4, "home" to 3, "recents" to 187, "enter" to 66).getValue(action.text("key"))
                "app_launch" -> {
                    val component = context.packageManager.getLaunchIntentForPackage(action.text("packageName"))?.component
                        ?: return@withContext unavailableScreenReply(context.getString(R.string.screen_app_missing))
                    "am start -W -n ${screenShellQuote(component.flattenToString())}"
                }
                "screen_input_text" -> {
                    // Payload is private UTF-8 data, never a shell/argv value or diagnostic string.
                    data.writeText(action.text("text"), Charsets.UTF_8)
                    "CLASSPATH=${screenShellQuote(context.applicationInfo.sourceDir)} app_process /system/bin " +
                        "io.github.bytejuno.thetacore.screen.ScreenClipboardMain $output && input keyevent 279"
                }
                else -> return@withContext unavailableScreenReply("Unsupported root screen action")
            }
            val result = bridge.execute(command, 25_000)
            if (!result.commandStarted) return@withContext unavailableScreenReply(context.getString(R.string.screen_permission_required))
            if (action.name == "screen_input_text" && result.outcome == RootOutcome.COMPLETED && result.exitCode == 78)
                return@withContext unavailableScreenReply(context.getString(R.string.screen_focus_required))
            if (result.outcome != RootOutcome.COMPLETED || result.exitCode != 0) {
                // Reading may fall back only after the root worker has positively exited.
                if (!action.writes) return@withContext unavailableScreenReply(context.getString(R.string.screen_read_failed))
                return@withContext ScreenReply(ToolResult(context.getString(R.string.screen_action_unknown),
                    isError = true, effect = ToolEffect.UNKNOWN))
            }
            val value = when (action.name) {
                "screen_capture" -> decodeScreenCapture(readBounded(data, 16 * 1024 * 1024))
                "screen_ui_tree" -> ToolResult(structured = parseScreenXml(String(readBounded(data, 1024 * 1024), Charsets.UTF_8)), effect = ToolEffect.NONE)
                else -> ToolResult(context.getString(R.string.screen_action_sent), effect = ToolEffect.APPLIED)
            }
            ScreenReply(value)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            ScreenReply(ToolResult(context.getString(if (action.writes) R.string.screen_action_unknown else R.string.screen_read_failed),
                isError = true, effect = if (action.writes) ToolEffect.UNKNOWN else ToolEffect.NONE), !action.writes)
        } finally {
            // RootBridge has already joined privileged process-group cleanup and readers.
            data.delete(); directory.delete()
        }
    }
}

internal fun readBounded(file: File, limit: Int): ByteArray = file.inputStream().use {
    val bytes = it.readNBytes(limit + 1)
    require(bytes.size <= limit)
    bytes
}
