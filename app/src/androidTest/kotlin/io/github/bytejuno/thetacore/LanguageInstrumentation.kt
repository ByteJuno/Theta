package io.github.bytejuno.thetacore

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.bytejuno.thetacore.plugin.PluginImportSession
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Real Activity/LocaleManager acceptance, offline synthetic fixtures, separate test-only runner. */
class LanguageInstrumentation : Instrumentation() {
    private lateinit var evidence: File
    private lateinit var localeManager: LocaleManager
    private var created = 0
    private var destroyed = 0
    @Volatile private var activity: Activity? = null
    private val steps = mutableListOf<String>()
    private var phase = "start"
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, state: Bundle?) { if (a is MainActivity) { activity = a; created++ } }
        override fun onActivityDestroyed(a: Activity) { if (a is MainActivity) destroyed++ }
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
    }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        check(arguments?.getString("deviceReceipt") == "theta_issue17_api36_issue82")
        start()
    }
    override fun onStart() {
        evidence = File(targetContext.filesDir, "issue82-language-evidence").apply { mkdirs() }
        val app = targetContext.applicationContext as ThetaApplication
        localeManager = app.getSystemService(LocaleManager::class.java)
        try {
            check(Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File("/data/local/tmp/theta-issue82-owner").readText().trim() == "theta_issue17_api36_issue82")
            app.registerActivityLifecycleCallbacks(callbacks)
            check(localeManager.applicationLocales.isEmpty) { "Test requires default follow-system state" }
            startMain()
            awaitText("Follow system")
            shot("01-follow-system-en")
            click("English")
            await { localeManager.applicationLocales.toLanguageTags() == "en" }
            click("中文")
            awaitText("应用语言")
            await { localeManager.applicationLocales.toLanguageTags() == "zh" && created >= 2 && destroyed >= 1 }
            shot("02-immediate-zh")
            checkLocalizedResources("zh")
            click("English")
            awaitText("App language")
            checkLocalizedResources("en")
            shot("03-immediate-en")
            click("Follow system")
            await { localeManager.applicationLocales.isEmpty }
            awaitText("Follow system")
            steps += "App selector English -> Chinese -> English -> follow system; automatic Activity recreation"

            // External system command invokes the same service as Android App Languages.
            systemLocale("zh")
            awaitText("应用语言")
            shot("04-system-api-zh")
            systemLocale("en")
            awaitText("App language")
            shot("05-system-api-en")
            val before = created
            runOnMainSync { activity!!.recreate() }
            await { created > before }
            awaitText("App language")
            check(localeManager.applicationLocales.toLanguageTags() == "en")
            steps += "System locale API both directions; explicit Activity recreation retains English"

            // Real package import exercises signed fixture publication and retained runtime.
            startMain(Uri.parse("content://io.github.bytejuno.thetacore.s1.test.payloads/v1"))
            click("Code APK")
            awaitText("Install Fixture 1.0?")
            shot("06-plugin-confirm-en")
            click("Install")
            await { main { app.plugins.plugins.any { it.manifest.id == FIXTURE && it.status.key == R.string.status_running } } }
            click("Fixture page", scroll = true)
            awaitText("Fixture runtime normalV1")
            click("Increment counter")
            awaitText("Counter 1")
            shot("07-plugin-runtime-en")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            top()
            click("中文")
            awaitText("应用语言")
            click("Fixture page", scroll = true)
            awaitText("Counter 1")
            shot("08-plugin-runtime-zh-host")
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            steps += "Signed fixture install, page invocation and counter survive language recreation; plugin-owned strings remain plugin data"
            val runtimeContext = main { app.plugins.plugins.single { it.manifest.id == FIXTURE }.runtime!!.context }
            runtimeContext.register(object : io.github.bytejuno.thetacore.sdk.Tool {
                override val id = "issue82.offline"
                override val spec = io.github.bytejuno.thetacore.sdk.ToolSpec(
                    "Offline diagnostic fixture", "Synthetic metadata", kotlinx.serialization.json.JsonObject(emptyMap()),
                    idempotent = true, parallelSafe = true,
                )
                override suspend fun call(args: kotlinx.serialization.json.JsonObject, env: io.github.bytejuno.thetacore.sdk.ToolEnv) =
                    io.github.bytejuno.thetacore.sdk.ToolResult("Synthetic offline result", effect = io.github.bytejuno.thetacore.sdk.ToolEffect.NONE)
            })
            runtimeContext.register(object : io.github.bytejuno.thetacore.sdk.EntryAdapter {
                override val id = "issue82.entry"
                override val displayName = "Offline entry fixture"
                override fun isAvailable(device: io.github.bytejuno.thetacore.sdk.DeviceInfo) = true
                override suspend fun attach(host: io.github.bytejuno.thetacore.sdk.EntryHost) {
                    host.ready(); kotlinx.coroutines.awaitCancellation()
                }
            })
            top(); click("调用工具 1", scroll = true)
            awaitText("工具 1 诊断"); shot("08a-tool-dialog-zh")
            click("调用一次")
            awaitText("Synthetic offline result", scroll = true)
            awaitText("实际执行已退出；工作线程容量已释放。", scroll = true)
            shot("08b-tool-result-zh"); click("关闭")
            top(); click("English"); awaitText("App language")
            awaitText("Ready", scroll = true); shot("08c-entry-en")
            click("Invoke tool 1", scroll = true)
            awaitText("Tool 1 diagnostics"); shot("08d-tool-dialog-en")
            click("Invoke once")
            awaitText("Synthetic offline result", scroll = true)
            awaitText("Actual execution exited; worker capacity has been released.", scroll = true)
            shot("08e-tool-result-en"); click("Close")
            top(); click("中文"); awaitText("应用语言")
            awaitText("就绪", scroll = true); shot("08f-entry-zh")
            steps += "Synthetic Tool and Entry registered through actual plugin context: both localized dialogs, successful invocation/actual exit and ready listener labels"

            // Synthetic unsigned ZIP through the actual manager import/confirmation contract.
            val session = PluginImportSession()
            runBlocking {
                val staged = checkNotNull(app.plugins.stageData(Uri.parse("content://io.github.bytejuno.thetacore.s1.test.payloads/language-data"), session))
                app.plugins.installData(session, staged, staged.sha256, true)
            }
            top()
            click("查看数据内容", scroll = true)
            awaitText("不可信的纯文本 / JSON。不打开链接、不执行、不认证、不发起网络请求。")
            shot("09-data-page-zh")
            click("skills/example/SKILL.md", scroll = true)
            awaitText("Synthetic offline language fixture. No credentials or network.")
            shot("10-data-content-zh")
            click("返回")
            top()
            click("English")
            awaitText("App language")
            click("View data content", scroll = true)
            awaitText("Untrusted plain text / JSON. No links, execution, authentication or network requests.")
            shot("11-data-page-en")
            click("Back")
            steps += "Real data package page and content in both locales; untrusted content unchanged"
            reviewRegressions(app)
            finalBoundaryRegressions(app)
            awaitText("S1 Modern Hook — dedicated ordinary target only", scroll = true)
            awaitText("Framework unavailable; open LSPosed and enable the module", scroll = true)
            shot("12-hook-en")
            top(); click("中文"); awaitText("应用语言")
            awaitText("S1 Modern Hook — 仅限专用普通目标", scroll = true)
            awaitText("框架不可用；请打开 LSPosed 并启用模块", scroll = true)
            shot("13-hook-zh")
            steps += "Hook diagnostic panel and process-retained unavailable status re-resolve in both locales; no framework publication claimed"
            top(); click("跟随系统"); awaitText("Follow system")
            check(localeManager.applicationLocales.isEmpty)
            shot("14-final-follow-system")
            record("PASS", null)
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "ISSUE82_PASS") })
        } catch (failure: Throwable) {
            runCatching { shot("failure") }
            record("FAIL", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", failure.stackTraceToString()) })
        } finally { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }
    private fun reviewRegressions(app: ThetaApplication) {
        // A failed real cleanup ticket drives PluginManager.importSource's admission guard.
        val cleanup = app.plugins.javaClass.getDeclaredField("stageCleanup").run {
            isAccessible = true; get(app.plugins) as io.github.bytejuno.thetacore.plugin.PluginStageCleanup
        }
        var blocked = true
        main {
            val ticket = cleanup.reserve { if (blocked) throw java.io.IOException("issue82 synthetic cleanup failure") }
            check(runCatching { cleanup.discard(ticket) }.exceptionOrNull() is java.io.IOException)
        }
        var retainedFailure: UiText? = null
        for ((tag, name) in listOf("en" to "15-cleanup-en", "zh" to "16-cleanup-zh")) {
            top(); systemLocale(tag)
            await { main { activity!!.resources.configuration.locales[0].language == tag } }
            startMain(Uri.parse("content://io.github.bytejuno.thetacore.s1.test.payloads/v2"))
            click(if (tag == "en") "Code APK" else "代码 APK")
            val strings = main { UiStrings(activity!!.resources) }
            awaitText(strings.get(R.string.failure_import_cleanup), scroll = true)
            shot(name)
            // Same semantic error is re-resolved, not cached English.
            if (retainedFailure == null) retainedFailure = failureText(io.github.bytejuno.thetacore.plugin.PluginFailureReason.IMPORT_CLEANUP_PENDING)
            check(strings.get(retainedFailure!!) == strings.get(R.string.failure_import_cleanup))
            io.github.bytejuno.thetacore.plugin.PluginFailureReason.entries.forEach { reason ->
                val text = strings.get(failureText(reason))
                check(text.isNotBlank() && !text.contains("PluginPackageException"))
            }
        }
        blocked = false
        top(); click("重试导入清理")
        await { !cleanup.hasPending }
        steps += "Actual failed cleanup ticket -> manager import admission -> actionable localized retry-import error en/zh; retry cleared ticket"

        // Durable deletion intent plus actual filesystem delete failure, then restart-style inventory.
        val catalog = io.github.bytejuno.thetacore.plugin.PluginDataCatalog(targetContext.filesDir, 1,
            deletePackages = { _, _ -> throw java.io.IOException("issue82 synthetic deletion failure") })
        catalog.beginDelete("test.issue82.data")
        val diagnostics = mutableListOf<String>()
        val record = catalog.restoreInventory(diagnostics::add).single { it.id == "test.issue82.data" }
        check(record.state.pendingDelete && diagnostics.any { it.contains("issue82 synthetic deletion failure") })
        main { io.github.bytejuno.thetacore.plugin.replaceDataInventory(app.plugins.dataInventory, app.plugins.dataPlugins, record, false) }
        for ((tag, name) in listOf("zh" to "17-pending-delete-zh", "en" to "18-pending-delete-en")) {
            top(); systemLocale(tag)
            await { main { activity!!.resources.configuration.locales[0].language == tag } }
            val strings = main { UiStrings(activity!!.resources) }
            awaitText(strings.get(R.string.status_data_delete_pending), scroll = true)
            awaitText(strings.get(R.string.retry_data_uninstall), scroll = true)
            check(nodes().none { it.text?.toString() == strings.get(R.string.data_quarantine_notice) })
            shot(name)
        }
        click("Retry uninstall data", scroll = true)
        click("Uninstall data")
        await { main { app.plugins.dataInventory.none { it.id == "test.issue82.data" } } }
        check(main { app.plugins.plugins.single { it.manifest.id == FIXTURE }.enabled })
        check(targetContext.filesDir.resolve("plugin-data/$FIXTURE/counter.txt").readText() == "1")
        steps += "Actual data deletion failure and durable pending-delete record -> localized retry-uninstall (not validation failure) en/zh; UI retry removed package; code plugin enabled/counter unchanged"
        top()
    }

    private fun finalBoundaryRegressions(app: ThetaApplication) {
        val installed = targetContext.filesDir.resolve("plugins/$FIXTURE/1/plugin.apk")
        val before = installed.readBytes()
        val store = io.github.bytejuno.thetacore.plugin.PluginStore(targetContext.filesDir, targetContext.cacheDir,
            io.github.bytejuno.thetacore.plugin.PlatformPluginSignatureVerifier(targetContext.packageManager))
        val updates = io.github.bytejuno.thetacore.plugin.PluginUpdates(targetContext.filesDir, store)
        var retained: UiText? = null
        for (tag in listOf("en", "zh")) {
            top(); systemLocale(tag)
            await { main { activity!!.resources.configuration.locales[0].language == tag } }
            val strings = main { UiStrings(activity!!.resources) }
            for (version in listOf(1L, 0L)) {
                val error = runCatching { updates.prepareInstall(FIXTURE, 1, version) }.exceptionOrNull()
                check(error is io.github.bytejuno.thetacore.plugin.PluginPackageException &&
                    error.reason == io.github.bytejuno.thetacore.plugin.PluginFailureReason.HIGHER_VERSION_REQUIRED)
                if (retained == null) retained = uiFailure(error)
                check(strings.get(retained!!) == strings.get(R.string.failure_higher_version))
                check(strings.get(retained!!) != strings.get(R.string.operation_failed))
            }
            // Actual manager install entry, same signed v1: no downgrade or publication.
            startMain(Uri.parse("content://io.github.bytejuno.thetacore.s1.test.payloads/v1"))
            click(if (tag == "en") "Code APK" else "代码 APK")
            click(if (tag == "en") "Install" else "安装")
            awaitText(strings.get(R.string.failure_higher_version), scroll = true)
            shot("19-version-$tag")
            for (path in listOf("language-deleted", "language-denied")) {
                val uri = Uri.parse("content://io.github.bytejuno.thetacore.s1.test.payloads/$path")
                val session = PluginImportSession()
                val error = runCatching { runBlocking { app.plugins.stage(uri, session) } }.exceptionOrNull()
                check(error is io.github.bytejuno.thetacore.plugin.PluginPackageException &&
                    error.reason == io.github.bytejuno.thetacore.plugin.PluginFailureReason.FILE_UNAVAILABLE)
                check(if (path == "language-deleted") error.cause is java.io.FileNotFoundException else error.cause is SecurityException)
                check(uiFailure(error).key == R.string.failure_file_unavailable)
                runBlocking { app.plugins.discardImport(session) }
                top(); startMain(uri)
                click(if (tag == "en") "Code APK" else "代码 APK")
                awaitText(strings.get(R.string.failure_file_unavailable), scroll = true)
                shot("20-$path-$tag")
            }
            check(installed.readBytes().contentEquals(before))
            check(store.versions(FIXTURE) == listOf(1L))
            check(updates.read(FIXTURE) == null)
            check(!targetContext.filesDir.resolve("plugin-updates/install-intents/$FIXTURE.properties").exists())
            steps += "v3 $tag: actual prepareInstall v1->1/0 precise higher-version reason; real same-v1 UI install rejected; unchanged v1 bytes/no intent/publication; actual ContentResolver provider missing-file and synthetic revoked-permission exceptions retain causes and display localized file-unavailable UI"
        }
        top(); systemLocale("en"); awaitText("App language")
    }

    private fun checkLocalizedResources(language: String) {
        check(main { activity!!.resources.configuration.locales[0].language } == language)
        steps += "Activity resource locale = $language"
    }
    private fun systemLocale(tag: String) {
        val command = "cmd locale set-app-locales ${targetContext.packageName} --user 0 --locales $tag"
        uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        steps += command
    }
    private fun startMain(uri: Uri? = null) {
        val intent = Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (uri != null) { intent.action = Intent.ACTION_VIEW; intent.data = uri }
        runOnMainSync { targetContext.startActivity(intent) }; waitForIdleSync()
    }
    private fun <T> main(block: () -> T): T { var result: T? = null; runOnMainSync { result = block() }; @Suppress("UNCHECKED_CAST") return result as T }
    private fun await(block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (SystemClock.elapsedRealtime() < end) { if (block()) return; SystemClock.sleep(150) }
        error("Timeout: $phase")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != targetContext.packageName) return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { out += n; repeat(n.childCount) { n.getChild(it)?.let(::visit) } }
        visit(root); return out
    }
    private fun awaitText(text: String, scroll: Boolean = false) {
        phase = text
        await {
            val all = nodes(); val present = all.any { it.text?.toString() == text }
            if (!present && scroll) all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            present
        }
    }
    private fun click(text: String, scroll: Boolean = false) {
        awaitText(text, scroll)
        var node: AccessibilityNodeInfo? = nodes().single { it.text?.toString() == text && it.isEnabled }
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        SystemClock.sleep(300)
    }
    private fun top() { repeat(20) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); SystemClock.sleep(60) } }
    private fun shot(name: String) {
        waitForIdleSync()
        val image = checkNotNull(uiAutomation.takeScreenshot())
        File(evidence, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        File(evidence, "$name.txt").writeText(nodes().mapNotNull { it.text?.toString() }.joinToString("\n"))
    }
    private fun record(status: String, error: String?) {
        val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
        val apk = File(targetContext.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        File(evidence, "device-result.json").writeText(JSONObject(mapOf(
            "status" to status, "error" to error, "phase" to phase, "steps" to steps,
            "installedApkSha256" to digest, "versionName" to info.versionName, "versionCode" to info.longVersionCode,
            "api" to Build.VERSION.SDK_INT, "hardware" to Build.HARDWARE, "fingerprint" to Build.FINGERPRINT,
            "activityCreated" to created, "activityDestroyed" to destroyed,
            "finalApplicationLocales" to localeManager.applicationLocales.toLanguageTags(),
            "timestamp" to java.time.Instant.now().toString(),
        )).toString(2))
    }
    companion object { private const val FIXTURE = "io.github.bytejuno.thetacore.sample.fixture" }
}
