package io.github.bytejuno.thetacore.plugin

import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/** Current signer set only: certificate rotation/history does not authorize a new key. */
internal class PluginSigners(fingerprints: Collection<String>) {
    val sha256: List<String> = Collections.unmodifiableList(fingerprints.distinct().sorted())

    init {
        if (sha256.isEmpty() || sha256.any { !Regex("[0-9a-f]{64}").matches(it) }) {
            throw PluginPackageException("Invalid or empty APK signer set")
        }
    }

    override fun equals(other: Any?): Boolean = other is PluginSigners && sha256 == other.sha256
    override fun hashCode(): Int = sha256.hashCode()
}

internal fun interface PluginSignatureVerifier {
    fun verify(file: File): PluginSigners
}

/**
 * Public platform verification, not merely ZIP certificate extraction. Both flags are
 * intentional: GET_SIGNATURES requests certificate collection on platform archive
 * parsing paths; GET_SIGNING_CERTIFICATES exposes the full current signer set.
 * On modern Android the targetSdk >= 30 policy requires at least APK Signature
 * Scheme v2. This platform behavior MUST be device-tested; it is not an all-OEM
 * guarantee. No hidden API or runtime apksig dependency is used here.
 */
internal class PlatformPluginSignatureVerifier(private val packages: PackageManager) : PluginSignatureVerifier {
    @Suppress("DEPRECATION")
    override fun verify(file: File): PluginSigners {
        try {
            val flags = PackageManager.GET_SIGNATURES.toLong() or PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            val info = packages.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags))
                ?: throw PluginPackageException("APK signature verification failed")
            val targetSdk = info.applicationInfo?.targetSdkVersion
                ?: throw PluginPackageException("Missing APK application information")
            if (targetSdk < 30) throw PluginPackageException("Plugin APK must target Android API 30 or newer")
            val signers = info.signingInfo?.apkContentsSigners
            if (signers.isNullOrEmpty()) throw PluginPackageException("APK has no verified current signers")
            return PluginSigners(signers.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            })
        } catch (error: RuntimeException) {
            // Preserve the package-error contract consumed by the import UI.
            throw PluginPackageException("APK signature verification failed: ${error.message}")
        }
    }
}
