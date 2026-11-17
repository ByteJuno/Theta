package io.github.bytejuno.thetacore

import io.github.bytejuno.thetacore.plugin.EntryRuntimeStatus
import io.github.bytejuno.thetacore.plugin.PluginInvocationStatus
import io.github.bytejuno.thetacore.plugin.UpdatePhase
import io.github.bytejuno.thetacore.sdk.ToolRisk
import io.github.bytejuno.thetacore.sdk.ToolEffect

internal fun UiStrings.label(value: EntryRuntimeStatus): String = get(when (value) {
    EntryRuntimeStatus.NEW -> R.string.label_entryruntimestatus_new
    EntryRuntimeStatus.INITIALIZING -> R.string.label_entryruntimestatus_initializing
    EntryRuntimeStatus.READY -> R.string.label_entryruntimestatus_ready
    EntryRuntimeStatus.UNAVAILABLE -> R.string.label_entryruntimestatus_unavailable
    EntryRuntimeStatus.STOPPED -> R.string.label_entryruntimestatus_stopped
})

internal fun UiStrings.label(value: PluginInvocationStatus): String = get(when (value) {
    PluginInvocationStatus.SUCCEEDED -> R.string.label_plugininvocationstatus_succeeded
    PluginInvocationStatus.FAILED -> R.string.label_plugininvocationstatus_failed
    PluginInvocationStatus.REJECTED -> R.string.label_plugininvocationstatus_rejected
    PluginInvocationStatus.TIMED_OUT -> R.string.label_plugininvocationstatus_timed_out
    PluginInvocationStatus.CANCELLED -> R.string.label_plugininvocationstatus_cancelled
})

internal fun UiStrings.label(value: UpdatePhase): String = get(when (value) {
    UpdatePhase.OBSERVING -> R.string.label_updatephase_observing
    UpdatePhase.ROLLBACK -> R.string.label_updatephase_rollback
    UpdatePhase.RECOVERED -> R.string.label_updatephase_recovered
})

internal fun UiStrings.label(value: ToolRisk): String = get(when (value) {
    ToolRisk.READ -> R.string.label_toolrisk_read
    ToolRisk.WRITE -> R.string.label_toolrisk_write
    ToolRisk.DEVICE_CONTROL -> R.string.label_toolrisk_device_control
    ToolRisk.ROOT -> R.string.label_toolrisk_root
})

internal fun UiStrings.label(value: ToolEffect): String = get(when (value) {
    ToolEffect.NONE -> R.string.label_tooleffect_none
    ToolEffect.APPLIED -> R.string.label_tooleffect_applied
    ToolEffect.UNKNOWN -> R.string.label_tooleffect_unknown
})

internal fun UiStrings.label(value: Boolean): String = get(if (value) R.string.label_yes else R.string.label_no)

internal fun UiStrings.get(value: io.github.bytejuno.thetacore.plugin.DataStatus): String = get(when (value) {
    io.github.bytejuno.thetacore.plugin.DataStatus.SAFE_MODE -> R.string.status_data_safe_mode
    io.github.bytejuno.thetacore.plugin.DataStatus.DISABLED -> R.string.status_disabled
    io.github.bytejuno.thetacore.plugin.DataStatus.ENABLED -> R.string.status_data_enabled
    io.github.bytejuno.thetacore.plugin.DataStatus.QUARANTINED -> R.string.data_quarantine_notice
    io.github.bytejuno.thetacore.plugin.DataStatus.CRASH_QUARANTINED -> R.string.status_data_crash_quarantined
    io.github.bytejuno.thetacore.plugin.DataStatus.DELETE_PENDING -> R.string.status_data_delete_pending
})
