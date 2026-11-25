package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MANIFEST_PATH = "assets/theta-plugin.json"
private const val MANIFEST_MAX_BYTES = 64 * 1024L

internal val REQUIRED_ENVIRONMENT_KEYS = setOf("root", "lsposed", "accessibility", "shizuku")

/** Stable business reasons assigned at the failing invariant, never inferred from messages. */
internal enum class PluginFailureReason {
    UNKNOWN, IMPORT_CLEANUP_PENDING, PACKAGE_DIGEST_MISMATCH, SIGNER_MISMATCH,
    SIGNATURE_INVALID, SIGNER_BINDING_MISSING, HIGHER_VERSION_REQUIRED,
    UNINSTALL_PENDING, RESTART_PENDING, STATE_UNAVAILABLE, UNSUPPORTED_API,
    CONFIRMATION_EXPIRED, DATA_RECOVERY, DATA_PAGE_REVOKED, FILE_UNAVAILABLE,
    TOOL_INACTIVE, TOOL_QUARANTINED, TOOL_CAPACITY,
}

internal class PluginPackageException(
    message: String,
    val reason: PluginFailureReason = PluginFailureReason.UNKNOWN,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class PluginRequirements(
    val root: Boolean = false,
    val lsposed: Boolean = false,
    val accessibility: Boolean = false,
    val shizuku: Boolean = false,
) {
    val requiresAny: Boolean
        get() = root || lsposed || accessibility || shizuku
}

internal data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val apiLevel: Int,
    val entry: String,
    val provides: List<String> = emptyList(),
    val declares: List<String> = emptyList(),
    val requires: PluginRequirements = PluginRequirements(),
)

internal fun readPluginManifest(apk: File): PluginManifest {
    val bytes = ZipFile(apk).use { zip ->
        val entries = zip.entries().asSequence().filter { it.name == MANIFEST_PATH }.toList()
        if (entries.isEmpty()) throw PluginPackageException("Missing $MANIFEST_PATH")
        if (entries.size != 1) throw PluginPackageException("Duplicate $MANIFEST_PATH")
        val entry = entries.single()
        if (entry.isDirectory) throw PluginPackageException("Invalid $MANIFEST_PATH")
        if (entry.size > MANIFEST_MAX_BYTES) throw PluginPackageException("Manifest is too large")
        zip.getInputStream(entry).use {
            val raw = it.readNBytes(MANIFEST_MAX_BYTES.toInt() + 1)
            if (raw.size > MANIFEST_MAX_BYTES) throw PluginPackageException("Manifest is too large")
            raw
        }
    }
    return parsePluginManifest(bytes)
}

internal fun parsePluginManifest(bytes: ByteArray): PluginManifest {
    dataRequire(bytes.size <= MANIFEST_MAX_BYTES, "Manifest is too large")
    val json = dataJson(bytes)
    val allowed = setOf(
        "schema", "type", "id", "name", "version", "versionCode",
        "apiLevel", "entry", "provides", "declares", "requires", "hooks",
    )
    dataRequire(json.keys.all { it in allowed }, "Unsupported plugin manifest field")
    dataRequire(dataInteger(json, "schema") == 1L, "Unsupported plugin schema; expected 1")
    dataRequire(dataString(json, "type") == "dex", "Unsupported plugin type; only dex is implemented")
    val id = dataString(json, "id")
    // id 和 versionCode 会变成目录名，这里挡住路径穿越。
    checkId(id)
    val name = dataString(json, "name")
    val version = dataString(json, "version")
    val versionCode = dataInteger(json, "versionCode")
    dataRequire(versionCode >= 1L, "Invalid versionCode")
    val apiLevelLong = dataInteger(json, "apiLevel")
    dataRequire(apiLevelLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "apiLevel exceeds integer range")
    val entry = dataString(json, "entry")
    if ("hooks" in json) {
        val hooks = json["hooks"] as? JsonArray
            ?: throw PluginPackageException("hooks must be an array")
        dataRequire(hooks.isEmpty(), "Dex plugin cannot declare hooks")
    }
    return PluginManifest(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        apiLevel = apiLevelLong.toInt(),
        entry = entry,
        provides = dataStrings(json, "provides"),
        declares = dataStrings(json, "declares"),
        requires = parsePluginRequirements(json),
    )
}

internal fun parsePluginRequirements(json: JsonObject): PluginRequirements {
    if ("requires" !in json) return PluginRequirements()
    val requires = json["requires"] as? JsonObject
        ?: throw PluginPackageException("requires must be an object")
    dataRequire(requires.keys.all { it in REQUIRED_ENVIRONMENT_KEYS }, "Unknown requirement")
    fun flag(key: String): Boolean {
        val element = requires[key] ?: return false
        val primitive = element as? JsonPrimitive
            ?: throw PluginPackageException("$key requirement must be a boolean")
        dataRequire(!primitive.isString && (primitive.content == "true" || primitive.content == "false"),
            "$key requirement must be a boolean")
        return primitive.content == "true"
    }
    return PluginRequirements(
        root = flag("root"),
        lsposed = flag("lsposed"),
        accessibility = flag("accessibility"),
        shizuku = flag("shizuku"),
    )
}
