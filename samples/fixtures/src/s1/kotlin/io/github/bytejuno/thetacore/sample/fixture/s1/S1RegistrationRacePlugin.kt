package io.github.bytejuno.thetacore.sample.fixture.s1

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.bytejuno.thetacore.sample.fixture.BuildConfig
import io.github.bytejuno.thetacore.sdk.PluginClosedException
import io.github.bytejuno.thetacore.sdk.PluginContext
import io.github.bytejuno.thetacore.sdk.Registration
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.ThetaPlugin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Two finite producer threads. Never relies on the cancelled plugin scope to run post-close probes. */
class S1RegistrationRacePlugin : ThetaPlugin {
    private lateinit var ctx: PluginContext
    private lateinit var evidence: BoundaryEvidence
    private val start = CountDownLatch(1)
    private val getterEntered = CountDownLatch(1)
    private val firstConcurrentCleanup = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val done = CountDownLatch(2)
    private val rows = mutableListOf<Row>() // Maximum 125 rows; no externally supplied identities.
    private class Row(val id: String, val kind: String) {
        @Volatile var outcome = "pending"
        @Volatile var handle: Registration? = null
        val cleaned = AtomicInteger()
    }

    override suspend fun onEnable(ctx: PluginContext) {
        this.ctx = ctx
        evidence = BoundaryEvidence(ctx)
        // First registered => last host cleanup. Reports without fixture salvage revocation.
        ctx.onCleanup {
            evidence.mark("race:host-cleanup-tail")
            val drained = awaitBoundaryLatch(done, 1_000)
            evidence.mark("race:producers-drained", "ok=$drained")
            val snapshot = synchronized(rows) { rows.toList() }
            snapshot.forEach { row ->
                evidence.mark("race:row", "id=${row.id}|kind=${row.kind}|outcome=${row.outcome}|cleaned=${row.cleaned.get()}")
            }
            // Never revoke handles here: fixture salvage would mask a host removal failure.
            // Preserve the observed state through the runner's post-close UI window.
        }
        attempt("pre-register", false)
        attempt("pre-cleanup", true)
        Thread({
            try {
                if (!awaitBoundaryLatch(start, 1_000)) return@Thread
                attempt("boundary-register", false, stallGetter = true)
                if (closed.count == 0L) attempt("post-register", false)
                else evidence.mark("race:invalid", "reason=no-close-register")
            } finally { done.countDown() }
        }, "s1-register-producer").apply { isDaemon = true }.start()
        Thread({
            try {
                if (!awaitBoundaryLatch(start, 1_000)) return@Thread
                if (!awaitBoundaryLatch(getterEntered, 1_000)) return@Thread
                // Fixed ceiling; active across the runner's disable, never an unbounded loop.
                for (index in 0 until 120) {
                    attempt("boundary-cleanup-$index", true)
                    if (index == 0) firstConcurrentCleanup.countDown()
                    if (awaitBoundaryLatch(closed, 1_000)) break
                }
                if (closed.count == 0L) attempt("post-cleanup", true)
                else evidence.mark("race:invalid", "reason=no-close-cleanup")
            } finally { done.countDown() }
        }, "s1-cleanup-producer").apply { isDaemon = true }.start()
        start.countDown()
        check(awaitBoundaryLatch(getterEntered, 1_000) && awaitBoundaryLatch(firstConcurrentCleanup, 1_000)) {
            "S1_RACE_PRODUCER_NOT_READY"
        }
        evidence.mark("race:armed", "mode=${BuildConfig.SCENARIO}")
        if (BuildConfig.SCENARIO == "s1RegisterFail") {
            evidence.mark("race:enable-throw")
            error("S1_RACE_ENABLE_FAILURE")
        }
        evidence.mark("race:enable-return")
    }

    private fun attempt(identity: String, cleanup: Boolean, stallGetter: Boolean = false) {
        val id = identity
        val row = Row(identity, if (cleanup) "cleanup" else "register")
        synchronized(rows) { rows += row }
        evidence.mark("race:attempt", "id=$id|kind=${row.kind}")
        try {
            row.handle = if (cleanup) ctx.onCleanup {
                val count = row.cleaned.incrementAndGet()
                evidence.mark("race:cleaned", "id=$id|count=$count")
            } else ctx.register(object : SettingsPage {
                override val id: String
                    get() {
                        if (stallGetter) {
                            evidence.mark("race:getter-enter", "id=$identity")
                            getterEntered.countDown()
                            if (!awaitBoundaryLatch(closed, 120_000)) {
                                evidence.mark("race:invalid", "reason=getter-close-timeout")
                            }
                            evidence.mark("race:getter-return", "id=$identity")
                        }
                        return identity
                    }
                override val title = "S1 race $identity"
                @Composable override fun Content() { Text("S1 registration identity $identity") }
            })
            row.outcome = "accepted"
        } catch (_: PluginClosedException) {
            row.outcome = "rejected-closed"
        } catch (error: Exception) {
            row.outcome = "error-${error.javaClass.simpleName}"
        }
        evidence.mark("race:outcome", "id=$id|kind=${row.kind}|outcome=${row.outcome}")
    }

    override suspend fun onDisable() {
        // In the inspected manager this is AFTER beginClose, BEFORE cleanup actions.
        evidence.mark("race:onDisable-after-close")
        closed.countDown()
    }
}
