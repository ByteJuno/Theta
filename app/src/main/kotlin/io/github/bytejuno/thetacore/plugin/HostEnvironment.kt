package io.github.bytejuno.thetacore.plugin

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.github.bytejuno.thetacore.hook.HookHost
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"

internal enum class HostRequirementKey {
    ACCESSIBILITY,
    SHIZUKU,
    ROOT,
    LSPOSED,
}

/**
 * Passive host environment states:
 * - READY: verified active/connected for this host (own AccessibilityService enabled, or HookHost connected, or explicitly verified).
 * - DETECTED_UNVERIFIED: component/binary detected passively (e.g. Shizuku manager package or `su` file), but live service/grant status is unconfirmed without prompting.
 * - UNAVAILABLE: not enabled, not detected, or not connected.
 */
internal enum class EnvironmentReadiness {
    READY,
    DETECTED_UNVERIFIED,
    UNAVAILABLE,
}

/** Read-only snapshot of host device capabilities. Advisory only, never a security boundary. */
internal data class HostEnvironmentStatus(
    val accessibilityEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val rootAvailable: Boolean = false,
    val lsposedActive: Boolean = false,
    val shizukuReady: Boolean = false,
    val rootReady: Boolean = false,
) {
    fun readiness(key: HostRequirementKey): EnvironmentReadiness = when (key) {
        HostRequirementKey.ACCESSIBILITY ->
            if (accessibilityEnabled) EnvironmentReadiness.READY else EnvironmentReadiness.UNAVAILABLE
        HostRequirementKey.SHIZUKU -> when {
            shizukuReady -> EnvironmentReadiness.READY
            shizukuAvailable -> EnvironmentReadiness.DETECTED_UNVERIFIED
            else -> EnvironmentReadiness.UNAVAILABLE
        }
        HostRequirementKey.ROOT -> when {
            rootReady -> EnvironmentReadiness.READY
            rootAvailable -> EnvironmentReadiness.DETECTED_UNVERIFIED
            else -> EnvironmentReadiness.UNAVAILABLE
        }
        HostRequirementKey.LSPOSED ->
            if (lsposedActive) EnvironmentReadiness.READY else EnvironmentReadiness.UNAVAILABLE
    }
}

internal data class RequirementCheck(
    val key: HostRequirementKey,
    val readiness: EnvironmentReadiness,
) {
    val satisfied: Boolean get() = readiness == EnvironmentReadiness.READY
}

internal data class PluginEnvironmentEvaluation(
    val checks: List<RequirementCheck>,
) {
    val hasRequirements: Boolean get() = checks.isNotEmpty()
    val isRootFree: Boolean
        get() = checks.none { it.key == HostRequirementKey.ROOT || it.key == HostRequirementKey.LSPOSED }
    val missing: List<HostRequirementKey>
        get() = checks.filter { it.readiness == EnvironmentReadiness.UNAVAILABLE }.map { it.key }
    val unverified: List<HostRequirementKey>
        get() = checks.filter { it.readiness == EnvironmentReadiness.DETECTED_UNVERIFIED }.map { it.key }
    val allSatisfied: Boolean
        get() = checks.all { it.satisfied }
    val missingAccessibility: Boolean
        get() = checks.any { it.key == HostRequirementKey.ACCESSIBILITY && it.readiness == EnvironmentReadiness.UNAVAILABLE }
    val shizukuReadiness: EnvironmentReadiness?
        get() = checks.firstOrNull { it.key == HostRequirementKey.SHIZUKU }?.readiness
    val rootReadiness: EnvironmentReadiness?
        get() = checks.firstOrNull { it.key == HostRequirementKey.ROOT }?.readiness
    val lsposedReadiness: EnvironmentReadiness?
        get() = checks.firstOrNull { it.key == HostRequirementKey.LSPOSED }?.readiness
}

internal fun PluginRequirements.evaluate(status: HostEnvironmentStatus): PluginEnvironmentEvaluation {
    val items = buildList {
        if (accessibility) add(RequirementCheck(HostRequirementKey.ACCESSIBILITY, status.readiness(HostRequirementKey.ACCESSIBILITY)))
        if (shizuku) add(RequirementCheck(HostRequirementKey.SHIZUKU, status.readiness(HostRequirementKey.SHIZUKU)))
        if (root) add(RequirementCheck(HostRequirementKey.ROOT, status.readiness(HostRequirementKey.ROOT)))
        if (lsposed) add(RequirementCheck(HostRequirementKey.LSPOSED, status.readiness(HostRequirementKey.LSPOSED)))
    }
    return PluginEnvironmentEvaluation(items)
}

internal fun interface HostEnvironmentDetector {
    suspend fun detect(): HostEnvironmentStatus
}

internal suspend fun detectHostEnvironmentSafely(detector: HostEnvironmentDetector): HostEnvironmentStatus =
    try {
        withContext(Dispatchers.IO) { detector.detect() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        HostEnvironmentStatus()
    }

/**
 * Only an enabled AccessibilityService owned by [hostPackageName] proves host/plugin availability.
 * Enabled AccessibilityServices belonging to other applications never satisfy this check.
 */
internal fun evaluateHostAccessibilityEnabled(
    hostPackageName: String,
    managerEnabled: Boolean,
    enabledServicePackages: Iterable<String>,
): Boolean = managerEnabled && enabledServicePackages.any { it == hostPackageName }

/**
 * Seeing the Shizuku manager package passively only proves the manager app is installed (`shizukuAvailable = true`),
 * NOT that the Shizuku service is running or authorized (`shizukuReady = false` unless explicitly confirmed).
 */
internal fun evaluatePassiveHostEnvironment(
    hostPackageName: String,
    accessibilityManagerEnabled: Boolean,
    enabledAccessibilityPackages: Iterable<String>,
    shizukuManagerInstalled: Boolean,
    shizukuServiceConfirmed: Boolean = false,
    suBinaryDetected: Boolean,
    rootGrantConfirmed: Boolean = false,
    lsposedConnected: Boolean,
): HostEnvironmentStatus = HostEnvironmentStatus(
    accessibilityEnabled = evaluateHostAccessibilityEnabled(
        hostPackageName = hostPackageName,
        managerEnabled = accessibilityManagerEnabled,
        enabledServicePackages = enabledAccessibilityPackages,
    ),
    shizukuAvailable = shizukuManagerInstalled,
    shizukuReady = shizukuManagerInstalled && shizukuServiceConfirmed,
    rootAvailable = suBinaryDetected,
    rootReady = suBinaryDetected && rootGrantConfirmed,
    lsposedActive = lsposedConnected,
)

/**
 * Non-intrusive background environment detector.
 * Never executes `su` or triggers Shizuku/Root permission prompts during passive refreshes.
 */
internal class AndroidHostEnvironmentDetector(
    context: Context,
    private val suDirectories: () -> List<File> = ::defaultSuSearchDirectories,
    private val lsposedConnected: () -> Boolean = { HookHost.isConnected },
) : HostEnvironmentDetector {
    private val app = context.applicationContext

    override suspend fun detect(): HostEnvironmentStatus = withContext(Dispatchers.IO) {
        runCatching { HookHost.connect() }
        val accessibility = runCatching { readAccessibilitySnapshot(app) }.getOrDefault(false to emptyList())
        val shizukuInstalled = runCatching { checkShizukuManagerVisible(app) }.getOrDefault(false)
        val suDetected = runCatching { hasSuBinaryInPaths(suDirectories()) }.getOrDefault(false)
        val lsposed = runCatching { lsposedConnected() }.getOrDefault(false)
        evaluatePassiveHostEnvironment(
            hostPackageName = app.packageName,
            accessibilityManagerEnabled = accessibility.first,
            enabledAccessibilityPackages = accessibility.second,
            shizukuManagerInstalled = shizukuInstalled,
            shizukuServiceConfirmed = false,
            suBinaryDetected = suDetected,
            rootGrantConfirmed = io.github.bytejuno.thetacore.root.RootRuntime.rootReady,
            lsposedConnected = lsposed,
        )
    }
}

internal fun hasSuBinaryInPaths(directories: Iterable<File>): Boolean =
    directories.any { dir ->
        runCatching {
            val candidate = File(dir, "su")
            candidate.exists() && candidate.isFile
        }.getOrDefault(false)
    }

private fun defaultSuSearchDirectories(): List<File> {
    val pathEntries = System.getenv("PATH")
        ?.split(File.pathSeparatorChar)
        .orEmpty()
        .filter { it.isNotBlank() }
    val standard = listOf(
        "/system/bin",
        "/system/xbin",
        "/sbin",
        "/su/bin",
        "/debug_ramdisk",
    )
    return (pathEntries + standard).distinct().map(::File)
}

private fun readAccessibilitySnapshot(context: Context): Pair<Boolean, List<String>> {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false to emptyList()
    if (!manager.isEnabled) return false to emptyList()
    val packages = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .mapNotNull { it.resolveInfo?.serviceInfo }
        .filter { it.name == io.github.bytejuno.thetacore.screen.ThetaAccessibilityService::class.java.name &&
            io.github.bytejuno.thetacore.screen.ThetaAccessibilityService.connected != null }
        .map { it.packageName }
    return true to packages
}

private fun checkShizukuManagerVisible(context: Context): Boolean {
    val packageInfo = context.packageManager.getPackageInfo(
        SHIZUKU_PACKAGE_NAME,
        PackageManager.PackageInfoFlags.of(0),
    )
    if (packageInfo.applicationInfo?.enabled == false) return false
    val provider = context.packageManager.resolveContentProvider(
        "$SHIZUKU_PACKAGE_NAME.shizuku",
        PackageManager.ComponentInfoFlags.of(0),
    )
    return provider != null || packageInfo.applicationInfo != null
}

internal fun openAccessibilitySettings(context: Context): Boolean {
    for (action in listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)) {
        val launched = runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (launched) return true
    }
    return false
}
