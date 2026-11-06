package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.util.zip.ZipFile
import org.json.JSONException
import org.json.JSONObject

private const val MANIFEST_PATH = "assets/theta-plugin.json"
private const val MANIFEST_MAX_BYTES = 64 * 1024L
private val ID_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

internal class PluginPackageException(message: String) : Exception(message)

internal class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val apiLevel: Int,
    val entry: String,
)

internal fun readPluginManifest(apk: File): PluginManifest {
    val text = ZipFile(apk).use { zip ->
        val entry = zip.getEntry(MANIFEST_PATH) ?: throw PluginPackageException("Missing $MANIFEST_PATH")
        if (entry.size > MANIFEST_MAX_BYTES) throw PluginPackageException("Manifest is too large")
        zip.getInputStream(entry).use { it.readBytes().decodeToString() }
    }
    val manifest = try {
        val json = JSONObject(text)
        PluginManifest(
            id = json.getString("id"),
            name = json.getString("name"),
            version = json.getString("version"),
            versionCode = json.getLong("versionCode"),
            apiLevel = json.getInt("apiLevel"),
            entry = json.getString("entry"),
        )
    } catch (e: JSONException) {
        throw PluginPackageException("Invalid manifest: ${e.message}")
    }
    // id 和 versionCode 会变成目录名，这里挡住路径穿越。
    if (!ID_PATTERN.matches(manifest.id)) throw PluginPackageException("Invalid plugin id")
    if (manifest.versionCode < 1) throw PluginPackageException("Invalid versionCode")
    return manifest
}
