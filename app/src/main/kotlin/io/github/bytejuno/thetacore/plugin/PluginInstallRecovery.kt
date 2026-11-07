package io.github.bytejuno.thetacore.plugin

/** Cleanup must never replace the failure that made an install unsuccessful. */
internal inline fun <T> withPluginCleanup(cleanup: () -> Unit, action: () -> T): T {
    var failure: Throwable? = null
    try {
        return action()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            cleanup()
        } catch (error: Throwable) {
            val original = failure
            if (original == null) throw error
            if (original !== error) original.addSuppressed(error)
        }
    }
}

/** Only for actions whose return means publication committed. A cleanup failure after return
 * is already owned by a PluginStageCleanup ticket; do not turn success into install failure.
 * Pre-commit failures retain their original exception plus any cleanup failure as suppressed.
 * Ordinary cleanup (provider close, validation, etc.) must continue using withPluginCleanup.
 */
internal inline fun <T> withCommittedPluginCleanup(cleanup: () -> Unit, action: () -> T): T {
    val result = try { action() } catch (error: Throwable) {
        withPluginCleanup(cleanup) { throw error }
    }
    try { cleanup() } catch (_: Exception) { /* ticket retains retry ownership */ }
    return result
}

/**
 * Runs under the manager's operation lock. A failed first publication can leave
 * trust without an APK, after restore has already completed. Expose that ID for
 * explicit clear-data recovery now; never roll back or replace its signer binding.
 * Query current managed state, not the pre-install snapshot: even failures after
 * registration must not expose a live plugin through the rejected-package UI.
 * Inline callbacks let the manager keep disk work on IO and list changes on Main.
 */
internal inline fun <T> withPluginInstallRecovery(
    id: String,
    isManaged: () -> Boolean,
    retainedIds: () -> Set<String>,
    rejectedIds: MutableList<String>,
    cleanup: () -> Unit,
    action: () -> T,
): T = withPluginCleanup(cleanup) {
    try {
        action()
    } catch (error: Throwable) {
        try {
            if (!isManaged() && id in retainedIds() && id !in rejectedIds) {
                rejectedIds.add(id)
            }
        } catch (recoveryError: Throwable) {
            // Enumeration can fail too. Preserve the install failure and its cause.
            if (recoveryError !== error) error.addSuppressed(recoveryError)
        }
        throw error
    }
}
