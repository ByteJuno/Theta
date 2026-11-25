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

/** Read-only snapshot of host device capabilities. Advisory only, never a security boundary. */
internal data class HostEnvironmentStatus(
    val accessibilityEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val rootAvailable: Boolean = false,
    val lsposedActive: Boolean = false,
) {
    fun isSatisfied(key: HostRequirementKey): Boolean = when (key) {
        HostRequirementKey.ACCESSIBILITY -> accessibilityEnabled
        HostRequirementKey.SHIZUKU -> shizukuAvailable
        HostRequirementKey.ROOT -> rootAvailable
        HostRequirementKey.LSPOSED -> lsposedActive
    }
}

internal data class RequirementCheck(
    val key: HostRequirementKey,
    val satisfied: Boolean,
)

internal data class PluginEnvironmentEvaluation(
    val checks: List<RequirementCheck>,
) {
    val hasRequirements: Boolean get() = checks.isNotEmpty()
    val isRootFree: Boolean
        get() = checks.none { it.key == HostRequirementKey.ROOT || it.key == HostRequirementKey.LSPOSED }
    val missing: List<HostRequirementKey>
        get() = checks.filterNot { it.satisfied }.map { it.key }
    val allSatisfied: Boolean
        get() = checks.all { it.satisfied }
    val missingAccessibility: Boolean
        get() = checks.any { it.key == HostRequirementKey.ACCESSIBILITY && !it.satisfied }
    val missingShizuku: Boolean
        get() = checks.any { it.key == HostRequirementKey.SHIZUKU && !it.satisfied }
    val missingPrivileged: Boolean
        get() = checks.any { (it.key == HostRequirementKey.ROOT || it.key == HostRequirementKey.LSPOSED) && !it.satisfied }
}

internal fun PluginRequirements.evaluate(status: HostEnvironmentStatus): PluginEnvironmentEvaluation {
    val items = buildList {
        if (accessibility) add(RequirementCheck(HostRequirementKey.ACCESSIBILITY, status.accessibilityEnabled))
        if (shizuku) add(RequirementCheck(HostRequirementKey.SHIZUKU, status.shizukuAvailable))
        if (root) add(RequirementCheck(HostRequirementKey.ROOT, status.rootAvailable))
        if (lsposed) add(RequirementCheck(HostRequirementKey.LSPOSED, status.lsposedActive))
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
 * Non-intrusive background environment detector.
 * Never executes `su` or triggers root/privilege permission prompts during passive refreshes.
 */
internal class AndroidHostEnvironmentDetector(
    context: Context,
    private val suDirectories: () -> List<File> = ::defaultSuSearchDirectories,
    private val lsposedConnected: () -> Boolean = { HookHost.isConnected },
) : HostEnvironmentDetector {
    private val app = context.applicationContext

    override suspend fun detect(): HostEnvironmentStatus = withContext(Dispatchers.IO) {
        runCatching { HookHost.connect() }
        HostEnvironmentStatus(
            accessibilityEnabled = runCatching { checkAccessibilityEnabled(app) }.getOrDefault(false),
            shizukuAvailable = runCatching { checkShizukuAvailable(app) }.getOrDefault(false),
            rootAvailable = runCatching { hasSuBinaryInPaths(suDirectories()) }.getOrDefault(false),
            lsposedActive = runCatching { lsposedConnected() }.getOrDefault(false),
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

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    if (!manager.isEnabled) return false
    val installedOwn = manager.installedAccessibilityServiceList.any {
        it.resolveInfo?.serviceInfo?.packageName == context.packageName
    }
    val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return if (installedOwn) {
        enabled.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    } else {
        enabled.isNotEmpty()
    }
}

private fun checkShizukuAvailable(context: Context): Boolean {
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
