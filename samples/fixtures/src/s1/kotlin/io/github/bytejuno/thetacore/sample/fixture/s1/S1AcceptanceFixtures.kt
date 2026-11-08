package io.github.bytejuno.thetacore.sample.fixture.s1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.bytejuno.thetacore.sample.fixture.BuildConfig
import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.EntryAdapter
import io.github.bytejuno.thetacore.sdk.EntryHost
import io.github.bytejuno.thetacore.sdk.PluginPrefs
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "ThetaS1Fixture"
private const val ACTION = "io.github.bytejuno.thetacore.fixture.S1_LONG_READY_PROBE"
private const val EXTRA = "s1_probe"
private const val BEFORE = "S1_BEFORE"
private const val IDLE = "S1_AFTER_IDLE"
private const val TOTAL = "S1_AFTER_TOTAL"
private const val DISABLED = "S1_AFTER_DISABLE"
private const val COMPOSE_MARKER = "s1.compose-crash.consumed"

/** Fixed marker names; no received free-form text, credentials or unbounded event history. */
private class Evidence(private val kind: String) {
    val identity: String = UUID.randomUUID().toString()
    private val startNanos = System.nanoTime()

    fun mark(marker: String, detail: String = "") {
        val now = System.nanoTime()
        Log.i(TAG, "${BuildConfig.SCENARIO}|$marker|kind=$kind|instance=$identity|pid=${Process.myPid()}" +
            "|monoNs=$now|sinceInstanceMs=${(now - startNanos) / 1_000_000}|$detail")
    }
}

/** One real Android receiver per attach; no thread, polling timer, network or event queue. */
internal class LongReadyFixture(private val app: Context) : EntryAdapter {
    override val id = "s1-entry"
    private val evidence = Evidence("long-ready")
    private val gate = Any()
    private var registered = false
    private var closed = false
    private var readyNanos: Long? = null
    private var received = 0L
    private val status = MutableStateFlow("S1_LONG_INITIALIZING")

    override val displayName: String
        get() {
            offMain()
            return "S1 long-ready broadcast listener"
        }

    override fun isAvailable(device: DeviceInfo): Boolean {
        offMain()
        return true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val probe = intent.getStringExtra(EXTRA)
            if (probe != BEFORE && probe != IDLE && probe != TOTAL && probe != DISABLED) return
            synchronized(gate) {
                // Log even a queued callback after closure; it must not count as delivery.
                if (closed) {
                    evidence.mark("long:callback:after-close", "probe=$probe")
                    return
                }
                val ready = readyNanos
                if (ready == null) {
                    evidence.mark("long:callback:before-ready", "probe=$probe")
                    return
                }
                val ageMs = (System.nanoTime() - ready) / 1_000_000
                received++
                evidence.mark("long:event:received", "probe=$probe|received=$received|sinceReadyMs=$ageMs")
                status.value = "S1_LONG_EVENT $probe #$received readyMs=$ageMs"
            }
        }
    }

    override suspend fun attach(host: EntryHost) {
        offMain()
        evidence.mark("long:attach:start")
        try {
            synchronized(gate) {
                check(!closed && !registered) { "S1 long listener already closed/attached" }
                // Exported deliberately for the package-targeted adb broadcast after disable.
                // Payload whitelist is fixed and harmless; there is no command execution.
                app.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
                registered = true
                evidence.mark("long:receiver:registered")
            }
            host.ready()
            synchronized(gate) {
                if (!closed) {
                    readyNanos = System.nanoTime()
                    status.value = "S1_LONG_READY instance=${evidence.identity}"
                    evidence.mark("long:ready:sent", "readyNs=$readyNanos")
                }
            }
            // No ToolSpec override: this entry must survive the REAL 60s / 600s defaults.
            awaitCancellation()
        } finally {
            try {
                close("finally")
            } finally {
                evidence.mark("long:attach:finally")
            }
        }
    }

    fun close(reason: String) = synchronized(gate) {
        closed = true
        if (registered) {
            // Keep registered=true if unregister throws so the other cleanup path can retry.
            app.unregisterReceiver(receiver)
            registered = false
            evidence.mark("long:receiver:unregistered", "reason=$reason")
        }
        status.value = "S1_LONG_CLOSED"
        evidence.mark("long:closed", "reason=$reason|received=$received")
    }

    @Composable
    fun Controls() {
        val current by status.collectAsState()
        Text(current)
        Text("Send after 65s (idle); after 605s (total). Keep host awake.")
        Button(enabled = current.startsWith("S1_LONG_READY") || current.startsWith("S1_LONG_EVENT"),
            onClick = send@{
                val probe = synchronized(gate) {
                    val ready = readyNanos ?: return@send
                    val age = (System.nanoTime() - ready) / 1_000_000
                    when {
                        age >= 605_000 -> TOTAL
                        age >= 65_000 -> IDLE
                        else -> BEFORE
                    }
                }
                evidence.mark("long:event:send", "probe=$probe|source=settings")
                app.sendBroadcast(Intent(ACTION).setPackage(app.packageName).putExtra(EXTRA, probe))
            }) { Text("Send S1 broadcast probe") }
    }

    private fun offMain() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "S1 long entry callback on Main" }
    }
}

/** Verifies both stores before the durable claim; only composition deliberately throws. */
internal class ComposeCrashFixture(
    private val prefs: PluginPrefs,
    private val establishBaseline: () -> Unit,
    private val verifyBaseline: () -> Unit,
) {
    private val evidence = Evidence("compose-crash")

    @Composable
    fun BeforeContent(): Boolean {
        var state by remember { mutableStateOf("loading") }
        LaunchedEffect(Unit) {
            try {
                val throwOnce = withContext(Dispatchers.IO) {
                    val prior = prefs.get(COMPOSE_MARKER)
                    if (prior != null) {
                        check(prior.matches(Regex("consumed:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                            "Unexpected S1 compose marker"
                        }
                        evidence.mark("compose:marker:retained", "claim=$prior")
                        verifyBaseline() // Read-only: missing, seed or wrong values must fail.
                        evidence.mark("compose:baseline:retained", "claim=$prior")
                        false
                    } else {
                        establishBaseline() // Write/read back BOTH non-seed values before claim.
                        evidence.mark("compose:baseline:persisted")
                        val claim = "consumed:${evidence.identity}"
                        prefs.put(COMPOSE_MARKER, claim)
                        check(prefs.get(COMPOSE_MARKER) == claim) { "S1 compose marker readback failed" }
                        evidence.mark("compose:marker:persisted", "claim=$claim")
                        true
                    }
                }
                state = if (throwOnce) "armed" else "recovered"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                evidence.mark("compose:baseline-or-marker:error", "type=${error.javaClass.simpleName}")
                state = "error"
            }
        }
        when (state) {
            "armed" -> {
                // NOT in LaunchedEffect/onClick: recomposition after IO throws from Content.
                evidence.mark("compose:throw:before", "claim=consumed:${evidence.identity}")
                throw IllegalStateException("S1_SETTINGS_COMPOSITION_CRASH")
            }
            "recovered" -> {
                LaunchedEffect("recovered") { evidence.mark("compose:recovery:rendered") }
                Text("S1_COMPOSE_RECOVERED: one-shot claim and both non-seed baseline values retained")
                return true
            }
            "error" -> Text("S1_COMPOSE_BASELINE_OR_MARKER_ERROR: missing/wrong data or store failure; not an acceptance pass")
            else -> Text("S1_COMPOSE_ARMING: opening settings will throw once")
        }
        return false
    }
}
