package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Tool
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

/**
 * Host-only, per-plugin-owner managed-call failure accounting. Record each invocation's unique
 * terminal exactly once. This is not a ledger, a retry scheduler, or a process sandbox.
 *
 * Tool identity never invokes plugin equals/hashCode/id/spec. Quarantine is sticky for this
 * owner's lifetime, including removal/re-registration of the same object. To bound retained
 * identities without forgetting quarantines, once the record cap is reached unknown tools
 * fail closed; existing tracked tools still use their own rolling window. No background timer.
 */
internal class PluginToolFailureWindow(
    private val threshold: Int = 3,
    windowMillis: Long = 30_000,
    private val maxTrackedTools: Int = 32,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(threshold > 0)
        require(windowMillis > 0 && windowMillis <= Long.MAX_VALUE / 1_000_000)
        require(maxTrackedTools > 0)
    }
    private class Failures {
        val times = ArrayDeque<Long>()
        var quarantined = false
    }
    private val windowNanos = TimeUnit.MILLISECONDS.toNanos(windowMillis)
    private val lock = Any()
    private val records = IdentityHashMap<Tool, Failures>()

    /** Returns the resulting quarantine state. Success/cancel/rejection neither count nor reset. */
    fun record(tool: Tool, status: PluginInvocationStatus): Boolean = synchronized(lock) {
        val existing = records[tool]
        if (existing?.quarantined == true) return true
        if (existing == null && records.size >= maxTrackedTools) return true
        if (status != PluginInvocationStatus.FAILED && status != PluginInvocationStatus.TIMED_OUT) return false
        val failures = existing ?: Failures().also { records[tool] = it }
        val now = nanoTime()
        // A failure exactly windowMillis old has left the half-open rolling window.
        // Subtraction supports System.nanoTime wrapping, assuming intervals below 2^63 ns.
        while (failures.times.isNotEmpty() && now - failures.times.peekFirst() >= windowNanos) {
            failures.times.removeFirst()
        }
        failures.times.addLast(now)
        if (failures.times.size >= threshold) {
            failures.quarantined = true
            failures.times.clear()
        }
        failures.quarantined
    }

    fun isQuarantined(tool: Tool): Boolean = synchronized(lock) {
        records[tool]?.quarantined ?: (records.size >= maxTrackedTools)
    }
}
