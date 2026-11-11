package io.github.bytejuno.thetacore

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.bytejuno.thetacore.plugin.PluginManager
import io.github.bytejuno.thetacore.plugin.PluginPublicationBoundary
import io.github.bytejuno.thetacore.plugin.PluginStateStore
import io.github.bytejuno.thetacore.plugin.PluginStore
import io.github.bytejuno.thetacore.plugin.PluginUpdates
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Installed only as androidTest; no fault switch or additional component in release. */
class S1PublicationInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var manager: PluginManager
    private lateinit var evidence: File
    private lateinit var request: String
    private lateinit var command: String
    private lateinit var point: String
    private lateinit var runId: String
    private var enabled = false
    private val token = UUID.randomUUID().toString()
    private var phase = "START"
    private var observer: FileObserver? = null

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        try {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
            check(File("/data/local/tmp/theta-s1-code-owner").readText().trim() == "theta_s1_code_20261109_owned" &&
                args.getString("deviceReceipt") == "theta_s1_code_20261109_owned") { "Missing dedicated-device receipt" }
            command = args.getString("command") ?: error("command required")
            point = args.getString("stage") ?: "INTENT"
            request = args.getString("requestId") ?: error("requestId required")
            runId = args.getString("runId") ?: error("runId required")
            enabled = args.getString("enabled") == "true"
            check(command in setOf("PREPARE", "DIGEST", "CRASH", "RECOVER", "CONTROL"))
            check(point in PluginPublicationBoundary.entries.map { it.name } + "PARTIAL_WRITE")
            check(request.matches(Regex("[a-f0-9]{32}")) && runId.matches(Regex("[a-f0-9]{32}")))
            evidence = File(targetContext.filesDir, "s1-code-evidence").apply { mkdirs() }
            manager = (targetContext.applicationContext as ThetaApplication).plugins
            when (command) {
                "PREPARE" -> prepare()
                "DIGEST" -> digestMismatch()
                "CRASH" -> crash()
                "RECOVER" -> recover()
                "CONTROL" -> control()
            }
            observer?.stopWatching()
        } catch (failure: Throwable) {
            if (::evidence.isInitialized) record("FAIL", mapOf("error" to failure.toString(), "stack" to failure.stackTraceToString()))
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", failure.stackTraceToString()) })
        }
    }

    private fun prepare() {
        startMain()
        importPayload(1)
        click("Install")
        awaitModel(1, true)
        click("Fixture page", scroll = true)
        awaitText("Fixture runtime normalV1")
        click("Increment counter")
        awaitText("Counter 1")
        snapshot("baseline-runtime")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        if (!enabled) { click("Disable Fixture", scroll = true); awaitModel(1, false) }
        check(counter().readText() == "1")
        complete(mapOf("selectedVersion" to 1, "baselineSha256" to sha(packageFile(1)), "counter" to 1) + model())
    }

    private fun digestMismatch() {
        startMain(); awaitModel(1, enabled)
        importPayload(2)
        val stage = targetContext.cacheDir.resolve("plugin-staging").listFiles()!!.single()
        val original = sha(stage)
        stage.appendBytes(byteArrayOf(0x53))
        val changed = sha(stage)
        check(original != changed)
        click("Install")
        val expected = targetContext.getString(R.string.failure_digest_mismatch)
        awaitText(expected, scroll = true)
        // Capture the actual rendered rejection, not a constant unrelated to the result.
        val observed = nodes().mapNotNull { it.text?.toString() }.single { it == expected }
        check(!isDigestRejection(targetContext.getString(R.string.operation_failed)))
        check(!isDigestRejection(targetContext.getString(R.string.failure_signature_invalid)))
        check(isDigestRejection(observed))
        check(store().versions(ID) == listOf(1L))
        check(!packageFile(2).exists() && counter().readText() == "1")
        complete(mapOf("originalStageSha256" to original, "changedStageSha256" to changed,
            "rejection" to observed, "rejectionResource" to "failure_digest_mismatch",
            "unrelatedFailureRejectedByControl" to true, "updatePublished" to false) + model())
    }

    private fun crash() {
        startMain(); awaitModel(1, enabled)
        val publication = field(store(), "publication")
        val fired = AtomicBoolean()
        val callback: (PluginPublicationBoundary) -> Unit = { observed ->
            if (point == "PARTIAL_WRITE" && observed == PluginPublicationBoundary.TEMP_CREATED) {
                val temporary = targetContext.filesDir.resolve("plugin-code-publication").listFiles()!!.single { it.isDirectory }
                val total = context.assets.open("code-v2.apk").use { it.available().toLong() }
                observer = object : FileObserver(temporary, CREATE or MODIFY or CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == "plugin.apk") {
                            val file = File(temporary, path)
                            val size = file.length()
                            if (size in 1 until total && fired.compareAndSet(false, true)) {
                                phase = "PARTIAL_DESTINATION_WRITE"
                                record("CUTPOINT_REACHED", mapOf("destinationBytesObserved" to size,
                                    "sourceBytes" to total, "temporary" to temporary.name))
                                Process.killProcess(Process.myPid())
                            }
                        }
                    }
                }.also { it.startWatching() }
            } else if (point == observed.name && fired.compareAndSet(false, true)) {
                phase = "CUTPOINT_$point"
                record("CUTPOINT_REACHED", mapOf("versions" to store().versions(ID), "counter" to counter().readText()))
                Process.killProcess(Process.myPid())
                error("killProcess returned")
            }
        }
        val hook = publication.javaClass.getDeclaredField("boundary").apply { isAccessible = true }
        hook.set(publication, callback)
        check(hook.get(publication) === callback)
        importPayload(2)
        click("Install")
        SystemClock.sleep(12000)
        error("No process death at $point; a completed install is not an interruption pass")
    }

    private fun recover() {
        val prior = JSONObject(File(evidence, "${args.getString("previousRequestId")}.json").readText())
        check(prior.getString("runId") == runId && prior.getString("processToken") != token)
        val expected = if (point == "MOVED" || args.getString("control") == "true") 2L else 1L
        startMain(); awaitModel(expected, enabled)
        check(counter().readText() == "1")
        check(PluginStateStore(targetContext.filesDir).read(ID).enabled == enabled)
        check(!targetContext.filesDir.resolve("plugin-code-publication/intent.properties").exists())
        check(targetContext.filesDir.resolve("plugin-code-publication").listFiles().orEmpty().isEmpty())
        check(!targetContext.cacheDir.resolve("plugin-staging").exists())
        store().verifyVersion(ID, expected)
        if (expected == 2L) {
            val update = PluginUpdates(targetContext.filesDir, store()).read(ID) ?: error("No cold update record")
            check(update.from == 1L && update.to == 2L && update.dataRestorable)
        }
        if (enabled) {
            click("Fixture page", scroll = true)
            awaitText("Fixture runtime normalV${expected}")
            awaitText("Counter 1")
            snapshot("recovered-runtime")
        }
        complete(mapOf("selectedVersion" to expected, "previousProcessToken" to prior.getString("processToken"),
            "previousPid" to prior.getInt("pid"), "freshProcess" to true, "counter" to 1,
            "publicationJournalEmpty" to true, "stagingRemoved" to true,
            "publishedVersions" to store().versions(ID)) + model())
    }

    private fun control() {
        startMain(); awaitModel(1, enabled)
        importPayload(2); click("Install")
        waitUntil { main { manager.plugins.singleOrNull { it.manifest.id == ID }?.manifest?.versionCode == 2L } }
        check(store().versions(ID) == listOf(1L, 2L))
        complete(mapOf("updatePublished" to true, "counter" to counter().readText()) + model())
    }

    private fun startMain(uri: Uri? = null) {
        phase = "HOST_UI"
        val intent = Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (uri != null) { intent.action = Intent.ACTION_VIEW; intent.data = uri }
        // An existing SINGLE_TOP Activity receives onNewIntent, not onCreate;
        // startActivitySync would wait forever for a new Activity monitor event.
        runOnMainSync { targetContext.startActivity(intent) }
        waitForIdleSync()
    }

    private fun importPayload(version: Int) {
        startMain(Uri.parse("content://io.github.bytejuno.thetacore.s1.test.payloads/v$version"))
        click("Code APK")
        awaitText("Install Fixture ${if (version == 1) "1.0" else "2.0"}?", contains = true)
        snapshot("review-v$version")
    }

    private fun awaitModel(version: Long, wanted: Boolean) = waitUntil {
        main { manager.plugins.singleOrNull { it.manifest.id == ID }?.let {
            it.manifest.versionCode == version && it.enabled == wanted &&
                (if (wanted) it.status.key == R.string.status_running else it.status.key in setOf(
                    R.string.status_disabled, R.string.status_disabled_restart, R.string.status_cleanup_failed))
        } == true }
    }

    private fun isDigestRejection(observed: String): Boolean =
        observed == targetContext.getString(R.string.failure_digest_mismatch)

    private fun model(): Map<String, Any> = main {
        val plugin = manager.plugins.single { it.manifest.id == ID }
        val status = targetContext.resources.getResourceEntryName(plugin.status.key)
        val values = mapOf("modelVersion" to plugin.manifest.versionCode, "modelEnabled" to plugin.enabled,
            "modelStatus" to status, "runtimeVersion" to (plugin.runtime?.version ?: -1))
        // Android JSONObject(Map) silently turns arbitrary UiText objects into null.
        // Assert the serialized value itself, not just our in-memory map.
        val json = JSONObject(JSONObject(values).toString())
        check(!json.isNull("modelStatus") && json.get("modelStatus") is String)
        check(json.getString("modelStatus") == status && status.startsWith("status_"))
        values
    }

    private fun packageFile(version: Int) = targetContext.filesDir.resolve("plugins/$ID/$version/plugin.apk")
    private fun counter() = targetContext.filesDir.resolve("plugin-data/$ID/counter.txt")
    private fun store() = field(manager, "store") as PluginStore
    private fun field(instance: Any, name: String) = instance.javaClass.getDeclaredField(name).run { isAccessible = true; get(instance)!! }

    private fun <T> main(action: () -> T): T {
        var result: T? = null
        runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun waitUntil(action: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (action()) return
            SystemClock.sleep(150)
        }
        snapshot("timeout")
        error("Timed out in $phase")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = uiAutomation.rootInActiveWindow ?: return emptyList()
        check(root.packageName?.toString() == targetContext.packageName) { "Unexpected foreground ${root.packageName}" }
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        visit(root)
        return result
    }

    private fun awaitText(text: String, contains: Boolean = false, scroll: Boolean = false) = waitUntil {
        val all = nodes()
        val found = all.any { if (contains) it.text?.contains(text) == true else it.text?.toString() == text }
        if (!found && scroll) all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        found
    }

    private fun click(text: String, scroll: Boolean = false) {
        phase = "CLICK_$text"
        awaitText(text, scroll = scroll)
        val matches = nodes().filter { it.text?.toString() == text && it.isEnabled }
        check(matches.size == 1) { "Nonunique/disabled control $text" }
        var target: AccessibilityNodeInfo? = matches.single()
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        SystemClock.sleep(200)
    }

    private fun snapshot(label: String) {
        val filename = "$request-$label"
        uiAutomation.takeScreenshot()?.let { image ->
            FileOutputStream(File(evidence, "$filename.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val lines = nodes().map { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            JSONObject(mapOf("text" to node.text?.toString().orEmpty(), "bounds" to bounds.toShortString(),
                "enabled" to node.isEnabled, "clickable" to node.isClickable)).toString()
        }
        File(evidence, "$filename-ui.jsonl").writeText(lines.joinToString("\n"))
    }

    private fun complete(values: Map<String, Any>) {
        snapshot("complete")
        record("PASS", values)
        finish(Activity.RESULT_OK, Bundle().apply { putString("result", "PASS:$request") })
    }

    private fun record(status: String, values: Map<String, Any>) {
        val json = JSONObject(mapOf("schema" to 1, "status" to status, "command" to command, "stage" to point,
            "enabled" to enabled, "runId" to runId, "requestId" to request, "pid" to Process.myPid(),
            "processToken" to token, "phase" to phase) + values).toString()
        FileOutputStream(File(evidence, "$request.json")).use { it.write(json.toByteArray()); it.fd.sync() }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private const val ID = "io.github.bytejuno.thetacore.sample.fixture" }
}
