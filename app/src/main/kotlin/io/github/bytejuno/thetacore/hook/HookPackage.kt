package io.github.bytejuno.thetacore.hook

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.telecom.TelecomManager
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal const val HOOK_TARGET = "io.github.bytejuno.thetacore.sample.hooktarget"
internal const val HOOK_GROUP = "theta-hook-s1"
internal const val HOOK_LIMIT = 8 * 1024 * 1024

/** Deliberately one explicit ordinary-app allowlist entry for this S1 slice. */
internal object HookTargetPolicy {
    fun check(context: Context, target: String) {
        require(target == HOOK_TARGET) { "S1 only admits the dedicated Hook target" }
        val pm = context.packageManager
        val info = pm.getApplicationInfo(target, 0)
        require(info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
            "System applications are not Hook targets"
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        require(target !in homes && target != dialer && target != context.packageName.takeIf { it != HOOK_TARGET }) {
            "Recovery-critical target rejected"
        }
        // The exact ordinary sample allowlist excludes self, system_server, settings,
        // SystemUI, phone and every framework manager without guessing manager packages.
    }
}

internal fun InputStream.bounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit) { "Hook payload exceeds limit" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal data class HookDescriptor(val id: String, val version: Long, val target: String, val entry: String) {
    companion object {
        fun read(bytes: ByteArray): HookDescriptor {
            var manifest: ByteArray? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entries = 0
                var expanded = 0
                while (true) {
                    val item = zip.nextEntry ?: break
                    require(++entries <= 4096)
                    val part = zip.bounded(2 * HOOK_LIMIT - expanded)
                    expanded += part.size
                    if (item.name == "assets/theta-hook.json") {
                        require(manifest == null && part.size <= 16384)
                        manifest = part
                    }
                }
            }
            val json = JSONObject(checkNotNull(manifest) { "Missing assets/theta-hook.json" }.decodeToString())
            require(json.getInt("schema") == 1)
            val value = HookDescriptor(json.getString("id"), json.getLong("version"),
                json.getString("target"), json.getString("entry"))
            require(validHookId(value.id))
            require(value.entry.matches(Regex("[A-Za-z][A-Za-z0-9_.$]{0,199}")))
            require(value.version > 0 && value.target == HOOK_TARGET)
            return value
        }
    }
}
