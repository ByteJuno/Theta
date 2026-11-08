package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test orchestration only; all import/publication/selection/recovery is production code. */
internal class StorageFixtureScenario(
    filesDir: File,
    private val processToken: String,
    private val pid: Int,
) {
    private val root = File(filesDir.canonicalFile, "s1-storage-fixture")
    var phase: String = "IDLE"
        private set

    fun execute(command: String, point: PluginPublicationBoundary, enabled: Boolean,
        beforeKill: (Map<String, Any>) -> Unit = {}, killSelf: () -> Unit = {},
    ): Map<String, Any> {
        val name = caseName(point, enabled)
        check(root.isDirectory || root.mkdirs())
        val dir = File(root, name)
        phase = command
        return when (command) {
            "PREPARE" -> {
                deletePluginTree(root, name)
                check(dir.mkdirs())
                val catalog = PluginDataCatalog(dir, 1)
                val bytes = archive(1)
                val stage = catalog.stage(bytes.inputStream())
                val installed = catalog.install(stage, stage.sha256, true)
                if (!enabled) catalog.setEnabled(ID, false, installed)
                val baseline = properties(
                    "runId" to UUID.randomUUID().toString(), "enabled" to enabled.toString(),
                    "baselineSha256" to sha(bytes), "updateSha256" to sha(archive(2)),
                )
                syncProperties(File(dir, "baseline.properties"), baseline)
                phase = "BASELINE_COMMITTED"
                verifyBaseline(dir, baseline, enabled)
                result(command, point, enabled, baseline).apply { putAll(selection(dir)) }
            }
            "MISMATCH" -> {
                val baseline = baseline(dir, enabled)
                check(!File(dir, "cutpoint.properties").exists()) { "Prepare a new baseline first" }
                verifyBaseline(dir, baseline, enabled)
                val bytes = archive(2)
                // Prove exactly these bytes are structurally valid before testing expectedSha256.
                check(validatePluginDataZip(bytes, 1, PluginDataLimits()).manifest.versionCode == 2L)
                val actual = sha(bytes)
                val wrong = (if (actual[0] == '0') "1" else "0") + actual.substring(1)
                val store = PluginDataStore(dir, 1)
                val positive = store.stageData(bytes.inputStream(), actual)
                check(positive.sha256 == actual)
                store.discardData(positive)
                phase = "EXPECTED_SHA_STAGE"
                val error = try {
                    val unexpected = store.stageData(bytes.inputStream(), wrong)
                    store.discardData(unexpected)
                    error("Wrong expected SHA-256 was accepted")
                } catch (e: PluginPackageException) { e }
                check(error.message == "Data ZIP index digest mismatch") { "Unexpected rejection: ${error.message}" }
                check(!File(dir, "plugins/$ID/2").exists())
                check(File(dir, "plugin-data-staging").listFiles()?.isEmpty() == true)
                check(!File(dir, "plugin-data-publication/intent.properties").exists())
                verifyBaseline(dir, baseline, enabled)
                phase = "EXPECTED_SHA_REJECTED"
                result(command, point, enabled, baseline).apply {
                    putAll(selection(dir)); put("validArchive", true); put("matchingDigestAccepted", true)
                    put("actualSha256", actual); put("expectedSha256", wrong)
                    put("rejectionClass", error.javaClass.name); put("rejection", error.message!!)
                    put("updatePublished", false); put("selectionUnchanged", true)
                }
            }
            "CRASH" -> {
                val baseline = baseline(dir, enabled)
                check(!File(dir, "cutpoint.properties").exists()) { "Cutpoint already reached; RECOVER or PREPARE instead" }
                verifyBaseline(dir, baseline, enabled)
                check(!File(dir, "plugins/$ID/2").exists())
                val catalog = PluginDataCatalog(dir, 1, publicationBoundary = { observed ->
                    if (observed == point) {
                        // Exceptions in MOVED cleanup are intentionally swallowed by production.
                        // Convert fixture failures to Error so no false successful activation follows.
                        try {
                            phase = "CUTPOINT_$observed"
                            check(PluginDataStateStore(dir).read(ID)?.version == 1L)
                            val marker = properties("processToken" to processToken, "pid" to pid.toString(),
                                "runId" to baseline.getProperty("runId"), "stage" to observed.name)
                            syncProperties(File(dir, "cutpoint.properties"), marker)
                            val evidence = result(command, point, enabled, baseline).apply {
                                put("status", "CUTPOINT_REACHED"); putAll(selection(dir))
                                put("updatePublished", File(dir, "plugins/$ID/2/plugin.zip").isFile)
                                put("intentPresent", File(dir, "plugin-data-publication/intent.properties").isFile)
                            }
                            beforeKill(evidence) // Android adapter fsyncs JSON evidence before SIGKILL.
                            killSelf() // Only Android Process.killProcess(Process.myPid()). Never a target PID.
                            throw AssertionError("killSelf returned; process-death test did not execute")
                        } catch (failure: Exception) { throw AssertionError("Cutpoint evidence failed", failure) }
                    }
                })
                val stage = catalog.stage(archive(2).inputStream())
                phase = "UPDATE_INSTALL"
                catalog.install(stage, stage.sha256, true)
                error("Publication returned without terminating at $point")
            }
            "RECOVER" -> {
                val baseline = baseline(dir, enabled)
                val marker = loadProperties(File(dir, "cutpoint.properties"))
                check(marker.getProperty("runId") == baseline.getProperty("runId"))
                check(marker.getProperty("stage") == point.name)
                check(marker.getProperty("processToken") != processToken) { "Recovery must run in a fresh process" }
                phase = "COLD_RESTORE"
                val errors = mutableListOf<String>()
                val catalog = PluginDataCatalog(dir, 1)
                val records = catalog.restoreInventory(errors::add)
                check(errors.isEmpty()) { errors.joinToString("; ") }
                val selected = records.single().selection ?: error("Old selection missing")
                check(selected.state.version == 1L && selected.state.enabled == enabled)
                verifyBaseline(dir, baseline, enabled)
                val moved = point == PluginPublicationBoundary.MOVED
                val update = File(dir, "plugins/$ID/2/plugin.zip")
                check(File(dir, "plugins/$ID/2").exists() == moved)
                if (moved) check(sha(update.readBytes()) == baseline.getProperty("updateSha256"))
                check(!File(dir, "plugin-data-publication/intent.properties").exists())
                check(File(dir, "plugin-data-publication").listFiles()?.isEmpty() == true)
                check(!File(dir, "plugin-data-staging").exists())
                if (enabled) check(catalog.content(selected, PAYLOAD).text == payload(1))
                // Do not install v3 or toggle state: preserve the exact recovered evidence.
                phase = "COLD_RESTORE_VERIFIED"
                result(command, point, enabled, baseline).apply {
                    putAll(selection(dir)); put("previousProcessToken", marker.getProperty("processToken"))
                    put("previousPid", marker.getProperty("pid")); put("freshProcess", true)
                    put("baselineBytesIntact", true); put("updatePublished", moved)
                    put("updateSelected", false); put("publicationJournalEmpty", true)
                    put("stagingRemoved", true); put("restoreErrors", errors)
                }
            }
            else -> error("Allowed commands: PREPARE, MISMATCH, CRASH, RECOVER, REPORT")
        }
    }

    private fun baseline(dir: File, enabled: Boolean): Properties =
        loadProperties(File(dir, "baseline.properties")).also {
            check(it.getProperty("enabled") == enabled.toString())
            check(it.getProperty("baselineSha256") == sha(archive(1)))
            check(it.getProperty("updateSha256") == sha(archive(2)))
        }

    private fun verifyBaseline(dir: File, baseline: Properties, enabled: Boolean) {
        val state = PluginDataStateStore(dir).read(ID) ?: error("Missing committed baseline")
        check(state == PluginDataState(1, baseline.getProperty("baselineSha256"),
            baseline.getProperty("baselineSha256"), enabled)) { "Baseline selection changed: $state" }
        val bytes = File(dir, "plugins/$ID/1/plugin.zip").readBytes()
        check(bytes.contentEquals(archive(1))) { "Baseline byte integrity failed" }
        check(sha(bytes) == state.sha256)
        check(validatePluginDataZip(bytes, 1, PluginDataLimits()).manifest.versionCode == 1L)
    }

    private fun selection(dir: File): Map<String, Any> {
        val state = PluginDataStateStore(dir).read(ID) ?: error("Missing selection")
        return mapOf("selectedVersion" to state.version, "enabled" to state.enabled,
            "selectedSha256" to state.sha256, "confirmedSha256" to state.confirmedSha256,
            "pendingDelete" to state.pendingDelete)
    }

    private fun result(command: String, point: PluginPublicationBoundary, enabled: Boolean, baseline: Properties) =
        linkedMapOf<String, Any>("schema" to 1, "scope" to "DATA", "status" to "PASS",
            "command" to command, "stage" to point.name, "phase" to phase,
            "case" to caseName(point, enabled), "runId" to baseline.getProperty("runId"),
            "pid" to pid, "processToken" to processToken)

    companion object {
        const val ID = "test.s1.storagefixture"
        const val PAYLOAD = "skills/local/SKILL.md"
        fun caseName(point: PluginPublicationBoundary, enabled: Boolean) =
            "${point.name}-${if (enabled) "enabled" else "disabled"}"
        private fun payload(version: Long) = "# Local storage fixture\nVersion $version; no executable content or network.\n"
        fun archive(version: Long): ByteArray {
            require(version in 1L..2L)
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val manifest = """{"schema":1,"type":"data","id":"$ID","name":"S1 local fixture","version":"$version","versionCode":$version,"apiLevel":1}"""
                listOf("theta-plugin.json" to manifest, PAYLOAD to payload(version)).forEach { (name, text) ->
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    zip.putNextEntry(ZipEntry(name).apply {
                        // Fixed DOS-representable timestamp, STORED entries: deterministic bytes on this device.
                        time = 946684800000L
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    })
                    zip.write(bytes); zip.closeEntry()
                }
            }
            return out.toByteArray().also { check(it.size < 4096) }
        }
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun properties(vararg pairs: Pair<String, String>) = Properties().apply {
            pairs.forEach { (key, value) -> setProperty(key, value) }
        }
        private fun syncProperties(file: File, value: Properties) {
            FileOutputStream(file).use { value.store(it, null); it.fd.sync() }
        }
        private fun loadProperties(file: File) = Properties().apply {
            check(file.isFile && file.length() <= 4096) { "Run PREPARE/CRASH first: ${file.name}" }
            file.inputStream().use { load(it) }
        }
    }
}
