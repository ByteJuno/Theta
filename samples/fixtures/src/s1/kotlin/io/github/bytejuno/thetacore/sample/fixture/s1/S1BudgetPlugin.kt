package io.github.bytejuno.thetacore.sample.fixture.s1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import io.github.bytejuno.thetacore.sdk.PluginContext
import io.github.bytejuno.thetacore.sdk.ThetaPlugin
import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.ToolEffect
import io.github.bytejuno.thetacore.sdk.ToolEnv
import io.github.bytejuno.thetacore.sdk.ToolResult
import io.github.bytejuno.thetacore.sdk.ToolSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val BUDGET_ACTION = "io.github.bytejuno.thetacore.fixture.S1_BUDGET_RELEASE"

/** All invocations go through the existing host UI; the receiver can ONLY release a latch. */
class S1BudgetPlugin : ThetaPlugin {
    override suspend fun onEnable(ctx: PluginContext) {
        val evidence = BoundaryEvidence(ctx)
        val gate = Any()
        val releases = Array(2) { CountDownLatch(1) }
        val started = Array(2) { AtomicBoolean() }
        var registered = false
        var closed = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BUDGET_ACTION || intent.getStringExtra("plugin") != ctx.pluginId) return
                val slot = when (intent.getStringExtra("slot")) { "1" -> 0; "2" -> 1; else -> return }
                synchronized(gate) {
                    if (closed || !started[slot].get()) return
                    // Mark before releasing, so a worker return cannot precede its release record.
                    evidence.mark("budget:release", "slot=${slot + 1}")
                    releases[slot].countDown()
                }
            }
        }
        ctx.onCleanup {
            synchronized(gate) {
                closed = true
                releases.forEach { it.countDown() }
                if (registered) {
                    ctx.appContext.unregisterReceiver(receiver)
                    registered = false
                    evidence.mark("budget:receiver-unregistered")
                }
                evidence.mark("budget:cleanup-released-all")
            }
        }
        synchronized(gate) {
            check(!closed)
            ctx.appContext.registerReceiver(receiver, IntentFilter(BUDGET_ACTION), Context.RECEIVER_EXPORTED)
            registered = true
            evidence.mark("budget:receiver-registered")
        }
        // Fixed order maps to UI Invoke tool 1, 2, 3. No EntryAdapter consumes another slot.
        for (slot in 0..2) {
            ctx.register(object : Tool {
                override val id = "s1-budget-${slot + 1}"
                override val spec: ToolSpec
                    get() {
                        check(Looper.myLooper() != Looper.getMainLooper())
                        evidence.mark("budget:spec-enter", "tool=${slot + 1}")
                        return ToolSpec(
                            name = if (slot < 2) "s1_block_${slot + 1}" else "s1_probe",
                            description = "S1 budget fixture; invoke with {}; slot ${slot + 1}",
                            inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                            parallelSafe = true,
                            // No timeout override: production idle 60s / total 600s unchanged.
                        )
                    }
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    evidence.mark("budget:call-enter", "tool=${slot + 1}|callId=${env.callId}")
                    if (slot == 2) {
                        evidence.mark("budget:probe-return", "callId=${env.callId}")
                        return ToolResult(text = "S1_BUDGET_PROBE_OK", effect = ToolEffect.NONE)
                    }
                    check(started[slot].compareAndSet(false, true)) { "S1_BUDGET_BLOCKER_SINGLE_USE" }
                    try {
                        evidence.mark("budget:block-start", "slot=${slot + 1}|callId=${env.callId}")
                        val released = awaitBoundaryLatch(releases[slot], 300_000)
                        evidence.mark(if (released) "budget:block-released" else "budget:INVALID-auto-expiry",
                            "slot=${slot + 1}|callId=${env.callId}")
                        return ToolResult(text = "S1_BUDGET_LATE_RETURN", effect = ToolEffect.NONE)
                    } finally {
                        // This is callback unwind, NOT the host's actualExited signal.
                        evidence.mark("budget:callback-finally", "slot=${slot + 1}|callId=${env.callId}")
                    }
                }
            })
        }
        evidence.mark("budget:enabled", "tools=block1,block2,probe|boundMs=300000")
    }
}
