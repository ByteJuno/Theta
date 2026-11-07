package io.github.bytejuno.thetacore.sample.fixture

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.bytejuno.thetacore.sdk.PluginClosedException
import io.github.bytejuno.thetacore.sdk.PluginContext
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.ThetaPlugin
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ThetaFixture"
// Longer than the host's 10s enable + 5s close deadlines, but finite: no leaked forever-loop.
private const val BLOCK_MS = 60_000L
private val BROKEN_UPDATES = setOf("initThrowV2", "initBlockV2", "scopeInitThrowV2")
private fun event(message: String) { Log.i(TAG, "${BuildConfig.SCENARIO}|$message") }

/** Intentionally broken callbacks. Never ship this fixture as a real plugin. */
class FixturePlugin : ThetaPlugin {
    override suspend fun onEnable(ctx: PluginContext) {
        event("enable:start")
        ctx.onCleanup { event("cleanup:first") }
        ctx.onCleanup {
            event("cleanup:second:start")
            when (BuildConfig.SCENARIO) {
                "cleanupThrow" -> {
                    event("cleanup:throw")
                    error("fixture cleanup failure")
                }
                "cleanupBlock" -> {
                    event("cleanup:block:start")
                    SystemClock.sleep(BLOCK_MS) // Ignores cancellation and interrupts.
                    event("cleanup:block:end")
                }
            }
            event("cleanup:second:end")
        }
        if (BuildConfig.SCENARIO in BROKEN_UPDATES) {
            File(ctx.dataDir, "counter.txt").writeText("99") // Only a real snapshot restore undoes this.
            event("data:damaged")
        }
        when (BuildConfig.SCENARIO) {
            "scopeThrow" -> ctx.register(FixturePage(ctx.dataDir) {
                // The UI only launches; failure is in the managed scope, not Compose.
                ctx.scope.launch {
                    event("scope:throw")
                    error("fixture managed runtime failure")
                }
            })
            "scopeInitThrow", "scopeInitThrowV2" -> {
                ctx.register(FixturePage(ctx.dataDir))
                // Join observes completion, not the failure of this independent supervisor child.
                val child = ctx.scope.launch {
                    event("scope:init:throw")
                    error("fixture managed initialization failure")
                }
                // Closing cancels this worker; the probe below must still run.
                withContext(NonCancellable) { child.join() }
                try {
                    // A new ID: a repeated one would throw "duplicate" even with admission open.
                    ctx.register(FixturePage(ctx.dataDir, "fixture-late"))
                    event("late-register:ACCEPTED")
                } catch (_: PluginClosedException) {
                    event("late-register:rejected")
                }
            }
            "initThrow", "initThrowV2" -> {
                ctx.register(FixturePage(ctx.dataDir))
                event("enable:throw")
                error("fixture initialization failure")
            }
            "initBlock", "initBlockV2" -> {
                event("enable:block:start")
                SystemClock.sleep(BLOCK_MS)
                event("enable:block:end")
                try {
                    ctx.register(FixturePage(ctx.dataDir))
                    event("late-register:ACCEPTED")
                } catch (_: PluginClosedException) {
                    event("late-register:rejected")
                }
                try {
                    ctx.onCleanup { event("late-cleanup:executed") }
                    event("late-cleanup:ACCEPTED")
                } catch (_: PluginClosedException) {
                    event("late-cleanup:rejected")
                }
            }
            else -> ctx.register(FixturePage(ctx.dataDir))
        }
        event("enable:return")
    }

    override suspend fun onDisable() {
        event("disable:start")
        if (BuildConfig.SCENARIO == "disableThrow") {
            event("disable:throw")
            error("fixture onDisable failure")
        }
        event("disable:end")
    }
}

private class FixturePage(
    private val dir: File,
    override val id: String = "fixture",
    private val throwInScope: (() -> Unit)? = null,
) : SettingsPage {
    override val title = "Fixture page"

    @Composable
    override fun Content() {
        val counter = remember { File(dir, "counter.txt") }
        var count by remember { mutableIntStateOf(if (counter.exists()) counter.readText().trim().toInt() else 0) }
        Column {
            // Compiled constants expose which dex is actually executing, not installed metadata.
            Text("Fixture runtime ${BuildConfig.SCENARIO}")
            Text("Counter $count")
            throwInScope?.let { action -> Button(onClick = action) { Text("Throw in managed scope") } }
            Button(onClick = {
                val next = count + 1
                counter.writeText(next.toString())
                count = next
                event("counter:$next")
            }) { Text("Increment counter") }
        }
    }
}
