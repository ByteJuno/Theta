package io.github.bytejuno.thetacore.sample.fixture.s1

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.bytejuno.thetacore.sample.fixture.BuildConfig
import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.EntryAdapter
import io.github.bytejuno.thetacore.sdk.EntryHost
import io.github.bytejuno.thetacore.sdk.PluginContext
import io.github.bytejuno.thetacore.sdk.PluginPrefs
import io.github.bytejuno.thetacore.sdk.PluginSecrets
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.ThetaPlugin
import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.ToolEffect
import io.github.bytejuno.thetacore.sdk.ToolEnv
import io.github.bytejuno.thetacore.sdk.ToolResult
import io.github.bytejuno.thetacore.sdk.ToolRisk
import io.github.bytejuno.thetacore.sdk.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "ThetaS1Fixture"
private const val BLOCK_MS = 60_000L
private const val PREF_KEY = "s1.preference"
private const val SECRET_KEY = "s1.secret"
// In prefs (therefore snapshot-restored), independent of user-edited preference values.
private const val V2_MIGRATION_KEY = "s1.migration.settings-v2"
private const val SCENARIO = BuildConfig.SCENARIO
private fun event(message: String) { Log.i(TAG, "$SCENARIO|$message") }

// Test-fixture-only diagnostics for host SDK calls using synthetic values. Never log
// stored values; full exception/cause logging here must not migrate to host diagnostics.
private inline fun <T> storageStep(label: String, action: () -> T): T {
    event("storage:$label:start")
    try {
        return action().also { event("storage:$label:ok") }
    } catch (cancelled: CancellationException) {
        event("storage:$label:cancelled")
        throw cancelled
    } catch (error: Exception) {
        Log.e(TAG, "$SCENARIO|storage:$label:failed", error)
        throw error
    }
}

private fun worker(callback: String) {
    check(Looper.myLooper() != Looper.getMainLooper()) { "S1 plugin callback on Main: $callback" }
    event("worker:$callback")
}

/** Disposable local fixtures. Blocking cases are finite but deliberately ignore cancellation. */
class S1FixturePlugin : ThetaPlugin {
    override suspend fun onEnable(ctx: PluginContext) {
        // First instruction: distinguish pre-callback HostContext failure from fixture IO.
        event("enable:entered:before-storage")
        event("enable:start")
        val prefs = storageStep("context.prefs") { ctx.prefs }
        val secrets = storageStep("context.secrets") { ctx.secrets }
        val store = FixtureStore(prefs, secrets)
        // This crash probe must expose missing data on recovery, never repair it by seeding.
        if (SCENARIO != "s1SettingsComposeCrash") {
            withContext(Dispatchers.IO) { storageStep("seed") { store.seed() } }
        }
        when (SCENARIO) {
            "s1SettingsV2" -> withContext(Dispatchers.IO) { store.migrateV2() }
            "s1SettingsFailV2" -> {
                withContext(Dispatchers.IO) { store.write("fail_v2") }
                event("settings:update:fail-v2:written")
                event("settings:update:fail-v2:throw")
                error("S1_SETTINGS_FAIL_V2_AFTER_WRITES")
            }
        }
        val longReady = if (SCENARIO == "s1EntryLongReady") LongReadyFixture(ctx.appContext) else null
        val composeCrash = if (SCENARIO == "s1SettingsComposeCrash") ComposeCrashFixture(
            prefs,
            establishBaseline = { store.establishComposeBaseline() },
            verifyBaseline = { store.verifyComposeBaseline() },
        ) else null
        if (longReady != null) {
            // Also cover enable failure / registration-close races. Release is idempotent.
            ctx.onCleanup { longReady.close("cleanup") }
            ctx.register(longReady)
        }
        ctx.register(FixtureSettings(store, crash = SCENARIO == "s1SettingsCrash",
            longReady = longReady, composeCrash = composeCrash))
        when (SCENARIO) {
            "s1EntryReady", "s1EntryLate", "s1EntryNonCooperative" -> ctx.register(FixtureEntry())
            "s1ToolSuccess", "s1ToolThrow", "s1ToolBlock", "s1ToolProgress" -> ctx.register(FixtureTool(store))
        }
        ctx.onCleanup { event("cleanup") }
        event("enable:return")
    }

    override suspend fun onDisable() { event("disable") }
}

private class FixtureEntry : EntryAdapter {
    override val id = "s1-entry"
    override val displayName: String
        get() {
            worker("entry.displayName")
            return "S1 entry $SCENARIO"
        }

    override fun isAvailable(device: DeviceInfo): Boolean {
        worker("entry.isAvailable")
        event("entry:available:sdk=${device.sdkInt}")
        return true
    }

    override suspend fun attach(host: EntryHost) {
        worker("entry.attach")
        event("entry:attach:start")
        try {
            if (SCENARIO == "s1EntryLate") {
                // Host initialization deadline is 30s. Sleep survives timeout cancellation.
                event("entry:late:block:start")
                SystemClock.sleep(BLOCK_MS)
                event("entry:late:ready:attempt")
                host.ready() // Must not resurrect the timed-out entry.
                event("entry:late:ready:return")
            } else {
                host.ready()
                event("entry:ready:sent")
                if (SCENARIO == "s1EntryNonCooperative") {
                    event("entry:noncooperative:block:start")
                    SystemClock.sleep(BLOCK_MS)
                    event("entry:noncooperative:block:end")
                }
            }
            awaitCancellation()
        } finally {
            event("entry:attach:finally")
        }
    }
}

private class FixtureTool(private val store: FixtureStore) : Tool {
    override val id = "s1-tool"
    override val spec: ToolSpec
        get() {
            worker("tool.spec")
            return ToolSpec(
                name = "s1_fixture",
                description = "Disposable S1 fixture: $SCENARIO; invoke with {}",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                risk = if (SCENARIO == "s1ToolSuccess") ToolRisk.WRITE else ToolRisk.READ,
                timeoutMs = 2_500L,
            )
        }

    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        worker("tool.call")
        event("tool:call:start:callId=${env.callId}")
        try {
            when (SCENARIO) {
                "s1ToolThrow" -> {
                    event("tool:throw")
                    error("S1 deliberate tool failure")
                }
                "s1ToolBlock" -> {
                    event("tool:block:start")
                    SystemClock.sleep(BLOCK_MS)
                    // Both late progress and the return must be ignored after terminal timeout.
                    event("tool:block:end")
                    env.progress("S1_LATE_PROGRESS_MUST_BE_IGNORED", 1f)
                    return ToolResult(text = "S1_LATE_RESULT_MUST_BE_IGNORED", effect = ToolEffect.NONE)
                }
                "s1ToolProgress" -> {
                    // Total duration exceeds the idle deadline; each tick refreshes it.
                    repeat(5) { index ->
                        delay(1_000L)
                        env.progress("S1_PROGRESS_${index + 1}_OF_5", (index + 1) / 5f)
                        event("tool:progress:${index + 1}/5")
                    }
                    event("tool:progress:success")
                    return ToolResult(text = "S1_PROGRESS_OK", effect = ToolEffect.NONE)
                }
                else -> {
                    withContext(Dispatchers.IO) { store.write("tool") }
                    event("tool:success")
                    return ToolResult(
                        text = "S1_TOOL_OK",
                        structured = JsonObject(mapOf("marker" to JsonPrimitive("S1_TOOL_OK"))),
                        effect = ToolEffect.APPLIED,
                    )
                }
            }
        } finally {
            event("tool:call:finally:callId=${env.callId}")
        }
    }
}

private data class StoreSnapshot(val preference: String?, val secret: String?)

/** Synchronous SDK methods are called only from an IO coroutine, never composition/Main. */
private class FixtureStore(private val prefs: PluginPrefs, private val secrets: PluginSecrets) {
    fun seed() {
        worker("store.seed")
        // Do not overwrite prior tool/page writes on disable-enable or process restart.
        if (storageStep("seed.prefs.get") { prefs.get(PREF_KEY) } == null) {
            storageStep("seed.prefs.put") { prefs.put(PREF_KEY, "S1_PREF_SEED") }
        }
        if (storageStep("seed.secrets.get") { secrets.get(SECRET_KEY) } == null) {
            storageStep("seed.secrets.put") { secrets.put(SECRET_KEY, "S1_SECRET_SEED_DISPOSABLE") }
        }
        event("store:seeded")
    }

    fun establishComposeBaseline() {
        worker("store.establishComposeBaseline")
        // Require fresh data. A lost claim with surviving values must not re-arm or repair.
        val prior = read()
        check(prior.preference == null && prior.secret == null) { "S1 compose baseline requires fresh data" }
        write("compose_baseline")
        verifyComposeBaseline()
    }

    fun verifyComposeBaseline() {
        worker("store.verifyComposeBaseline")
        // Expected values are code constants, independent of persisted data and seed().
        val snapshot = read()
        check(snapshot.preference == "S1_PREF_compose_baseline") { "S1 compose preference baseline mismatch" }
        check(snapshot.secret == "S1_SECRET_compose_baseline_DISPOSABLE") { "S1 compose secret baseline mismatch" }
        event("store:compose-baseline:verified")
    }

    fun migrateV2() {
        worker("store.migrateV2")
        if (storageStep("migration.v2.marker.get") { prefs.get(V2_MIGRATION_KEY) } == "done") {
            event("settings:update:v2:already-applied")
            return
        }
        event("settings:update:v2:start")
        write("v2") // Read-back verifies BOTH stores before committing the migration marker.
        storageStep("migration.v2.marker.put") { prefs.put(V2_MIGRATION_KEY, "done") }
        check(storageStep("migration.v2.marker.verify") { prefs.get(V2_MIGRATION_KEY) } == "done")
        event("settings:update:v2:applied")
    }

    fun write(source: String): StoreSnapshot {
        worker("store.write")
        storageStep("write.prefs.put") { prefs.put(PREF_KEY, "S1_PREF_$source") }
        storageStep("write.secrets.put") { secrets.put(SECRET_KEY, "S1_SECRET_${source}_DISPOSABLE") }
        // Separate stores are NOT a cross-store transaction.
        val snapshot = read()
        check(snapshot.preference == "S1_PREF_$source")
        check(snapshot.secret == "S1_SECRET_${source}_DISPOSABLE")
        event("store:write:verified:$source")
        return snapshot
    }

    fun read(): StoreSnapshot {
        worker("store.read")
        val snapshot = StoreSnapshot(
            storageStep("read.prefs.get") { prefs.get(PREF_KEY) },
            storageStep("read.secrets.get") { secrets.get(SECRET_KEY) },
        )
        event("store:read:ok")
        return snapshot
    }
}

private class FixtureSettings(
    private val store: FixtureStore,
    private val crash: Boolean,
    private val longReady: LongReadyFixture? = null,
    private val composeCrash: ComposeCrashFixture? = null,
) : SettingsPage {
    override val id = "s1-settings"
    override val title = "S1 fixture settings"

    @Composable
    override fun Content() {
        // Throw comes from this composable's body on opening, never a click/effect callback.
        if (composeCrash != null && !composeCrash.BeforeContent()) return
        var snapshot by remember { mutableStateOf<StoreSnapshot?>(null) }
        var status by remember { mutableStateOf("S1_STORE_LOADING") }
        var busy by remember { mutableStateOf(true) }
        val uiScope = rememberCoroutineScope()

        suspend fun load(write: Boolean) {
            busy = true
            status = "S1_STORE_LOADING"
            try {
                snapshot = withContext(Dispatchers.IO) {
                    if (composeCrash != null) store.verifyComposeBaseline()
                    if (write) store.write("page") else store.read()
                }
                status = "S1_STORE_OK"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snapshot = null
                status = "S1_STORE_ERROR:${error.javaClass.simpleName}"
                event(status)
            } finally {
                busy = false
            }
        }

        LaunchedEffect(Unit) { load(write = false) }
        Column {
            Text("S1 fixture runtime $SCENARIO")
            longReady?.Controls()
            Text(status)
            Text("Preference: ${snapshot?.preference ?: "<absent>"}")
            Text("Disposable secret: ${snapshot?.secret ?: "<absent>"}")
            if (composeCrash == null) {
                Button(enabled = !busy, onClick = { uiScope.launch { load(write = true) } }) {
                    Text("Write S1 prefs and secrets")
                }
            } else {
                Text("Composition-crash baseline is read-only; reopen to revalidate both values.")
            }
            Button(enabled = !busy, onClick = { uiScope.launch { load(write = false) } }) {
                Text("Refresh S1 values")
            }
            if (crash) {
                Text("Disposable host only: the next button crashes this process.")
                Button(onClick = {
                    // Intentionally uncaught Main event, not a supervised plugin-scope failure.
                    // The host page marker must already be durable when this button is visible.
                    event("settings:crash:armed")
                    throw IllegalStateException("S1_SETTINGS_PAGE_CRASH")
                }) { Text("Crash S1 settings page") }
            }
        }
    }
}
