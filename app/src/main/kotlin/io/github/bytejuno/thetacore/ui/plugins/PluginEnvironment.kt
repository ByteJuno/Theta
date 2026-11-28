package io.github.bytejuno.thetacore.ui.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens

@Composable
internal fun PluginManifestEnvironmentSummary(
    manifest: PluginManifest,
    environment: HostEnvironmentStatus,
    onOpenAccessibility: () -> Unit,
    showAdvisoryNotice: Boolean = true,
) {
    val strings = uiStrings()
    val evaluation = manifest.requires.evaluate(environment)
    Text(
        strings.get(
            R.string.package_capabilities,
            manifest.provides.takeIf { it.isNotEmpty() }?.joinToString() ?: strings.get(R.string.none),
            manifest.declares.takeIf { it.isNotEmpty() }?.joinToString() ?: strings.get(R.string.none),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (!evaluation.hasRequirements) {
        PluginBadge(strings.get(R.string.plugin_env_none_required))
    } else {
        if (evaluation.isRootFree) {
            PluginBadge(strings.get(R.string.plugin_env_root_free))
        }
        val tags = evaluation.checks.map { check ->
            when (check.key) {
                HostRequirementKey.ACCESSIBILITY -> strings.get(when (check.readiness) {
                    EnvironmentReadiness.READY -> R.string.plugin_req_accessibility_ready
                    EnvironmentReadiness.DETECTED_UNVERIFIED, EnvironmentReadiness.UNAVAILABLE ->
                        R.string.plugin_req_accessibility_missing
                })
                HostRequirementKey.SHIZUKU -> strings.get(when (check.readiness) {
                    EnvironmentReadiness.READY -> R.string.plugin_req_shizuku_ready
                    EnvironmentReadiness.DETECTED_UNVERIFIED -> R.string.plugin_req_shizuku_unverified
                    EnvironmentReadiness.UNAVAILABLE -> R.string.plugin_req_shizuku_missing
                })
                HostRequirementKey.ROOT -> strings.get(when (check.readiness) {
                    EnvironmentReadiness.READY -> R.string.plugin_req_root_ready
                    EnvironmentReadiness.DETECTED_UNVERIFIED -> R.string.plugin_req_root_unverified
                    EnvironmentReadiness.UNAVAILABLE -> R.string.plugin_req_root_missing
                })
                HostRequirementKey.LSPOSED -> strings.get(when (check.readiness) {
                    EnvironmentReadiness.READY -> R.string.plugin_req_lsposed_ready
                    EnvironmentReadiness.DETECTED_UNVERIFIED, EnvironmentReadiness.UNAVAILABLE ->
                        R.string.plugin_req_lsposed_missing
                })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalThetaThemeTokens.current.smallGap),
            verticalArrangement = Arrangement.spacedBy(LocalThetaThemeTokens.current.smallGap)) {
            tags.forEach { PluginBadge(it) }
        }
        if (evaluation.missingAccessibility) {
            Text(strings.get(R.string.plugin_env_accessibility_guide), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenAccessibility) {
                Text(strings.get(R.string.plugin_env_open_accessibility))
            }
        }
        when (evaluation.shizukuReadiness) {
            EnvironmentReadiness.DETECTED_UNVERIFIED -> Text(strings.get(R.string.plugin_env_shizuku_unverified_guide), style = MaterialTheme.typography.bodySmall)
            EnvironmentReadiness.UNAVAILABLE -> Text(strings.get(R.string.plugin_env_shizuku_guide), style = MaterialTheme.typography.bodySmall)
            EnvironmentReadiness.READY, null -> Unit
        }
        if (evaluation.rootReadiness == EnvironmentReadiness.DETECTED_UNVERIFIED) {
            Text(strings.get(R.string.plugin_env_root_unverified_guide), style = MaterialTheme.typography.bodySmall)
        }
        if (evaluation.rootReadiness == EnvironmentReadiness.UNAVAILABLE ||
            evaluation.lsposedReadiness == EnvironmentReadiness.UNAVAILABLE) {
            Text(strings.get(R.string.plugin_env_privileged_guide), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showAdvisoryNotice) {
        Text(strings.get(R.string.plugin_env_advisory_notice), style = MaterialTheme.typography.bodySmall)
    }
}
