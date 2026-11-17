package io.github.bytejuno.thetacore

import android.util.Log
import io.github.bytejuno.thetacore.plugin.PluginFailureReason
import io.github.bytejuno.thetacore.plugin.PluginPackageException

/** Keep technical details in logs; retain only semantic resources in UI state.
 * No exception text matching, locale caching, or untrusted exception text in UI.
 */
internal fun uiFailure(error: Throwable): UiText {
    runCatching { Log.w("ThetaUi", "Host operation failed", error) }
    return failureText((error as? PluginPackageException)?.reason ?: PluginFailureReason.UNKNOWN)
}

internal fun failureText(reason: PluginFailureReason): UiText = uiText(when (reason) {
    PluginFailureReason.UNKNOWN -> R.string.operation_failed
    PluginFailureReason.IMPORT_CLEANUP_PENDING -> R.string.failure_import_cleanup
    PluginFailureReason.PACKAGE_DIGEST_MISMATCH -> R.string.failure_digest_mismatch
    PluginFailureReason.SIGNER_MISMATCH -> R.string.failure_signer_mismatch
    PluginFailureReason.SIGNATURE_INVALID -> R.string.failure_signature_invalid
    PluginFailureReason.SIGNER_BINDING_MISSING -> R.string.failure_signer_binding
    PluginFailureReason.HIGHER_VERSION_REQUIRED -> R.string.failure_higher_version
    PluginFailureReason.UNINSTALL_PENDING -> R.string.status_uninstall_pending
    PluginFailureReason.RESTART_PENDING -> R.string.failure_restart_pending
    PluginFailureReason.STATE_UNAVAILABLE -> R.string.status_state_unavailable
    PluginFailureReason.UNSUPPORTED_API -> R.string.failure_unsupported_api
    PluginFailureReason.CONFIRMATION_EXPIRED -> R.string.failure_confirmation_expired
    PluginFailureReason.DATA_RECOVERY -> R.string.data_import_recovery_blocked
    PluginFailureReason.DATA_PAGE_REVOKED -> R.string.failure_data_page_revoked
    PluginFailureReason.FILE_UNAVAILABLE -> R.string.failure_file_unavailable
    PluginFailureReason.TOOL_INACTIVE -> R.string.failure_tool_inactive
    PluginFailureReason.TOOL_QUARANTINED -> R.string.failure_tool_quarantined
    PluginFailureReason.TOOL_CAPACITY -> R.string.failure_tool_capacity
})

internal fun logUiDiagnostic(message: String) {
    Log.w("ThetaUi", message)
}
