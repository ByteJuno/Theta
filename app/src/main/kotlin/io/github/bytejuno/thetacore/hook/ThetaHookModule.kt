package io.github.bytejuno.thetacore.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import dalvik.system.InMemoryDexClassLoader
import io.github.bytejuno.thetacore.sdk.HookEnvironment
import io.github.bytejuno.thetacore.sdk.HookPlugin
import io.github.bytejuno.thetacore.sdk.HookStringCallback
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Static bridge: dynamically loaded plugins never invoke libxposed API themselves. */
class ThetaHookModule : XposedModule() {
    private val attempted = AtomicBoolean()
    private val allowed = AtomicBoolean(false)
    private val faults = AtomicInteger()
    private var preferences: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var selectionText: String? = null
    private var processName = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = if (param.isSystemServer) "" else param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // Exact package AND main process; system/multipackage/secondary processes are out of scope.
        if (param.packageName != HOOK_TARGET || processName != HOOK_TARGET) return
        if (apiVersion < 102 || frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed() // Never swallow/retry an original exception.
            val context = chain.getArg(0) as? Context
            if (context != null && context.packageName == HOOK_TARGET && attempted.compareAndSet(false, true)) {
                try { load(context, param.classLoader) } catch (_: Throwable) { allowed.set(false) }
            }
            result
        }
    }

    private fun enabled(): Boolean = try {
        allowed.get() && preferences?.getBoolean("enabled", false) == true &&
            preferences?.getString("selection", null) == selectionText
    } catch (_: Throwable) { false }

    private fun load(context: Context, targetLoader: ClassLoader) {
        HookTargetPolicy.check(context, HOOK_TARGET)
        val prefs = getRemotePreferences(HOOK_GROUP)
        if (!prefs.getBoolean("enabled", false)) return
        val text = checkNotNull(prefs.getString("selection", null))
        require(text.length <= 16384)
        val selected = JSONObject(text)
        require(selected.getString("target") == HOOK_TARGET)
        val name = selected.getString("file")
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,240}\\.apk")) && !name.contains(".."))
        val expected = selected.getString("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val size = selected.getInt("size")
        require(size in 1..HOOK_LIMIT)
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(openRemoteFile(name)).use { it.bounded(HOOK_LIMIT) }
        require(bytes.size == size && digest(bytes) == expected)
        val descriptor = HookDescriptor.read(bytes)
        require(descriptor.id == selected.getString("id") && descriptor.version == selected.getLong("version"))
        val version = context.packageManager.getPackageInfo(HOOK_TARGET, 0).longVersionCode
        val de = context.createDeviceProtectedStorageContext()
        val journal = HookStartupJournal(java.io.File(de.filesDir, "theta-hook-s1"),
            digest("${descriptor.id}:${descriptor.version}:$version".toByteArray()))
        if (!journal.begin()) return
        val startup = HookStartupLifecycle(journal)
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val installing = AtomicBoolean(true)
        fun observeSelection() {
            try {
                // A changed selection never loads a replacement in this process.
                if (prefs.getString("selection", null) != text || !prefs.getBoolean("enabled", false)) {
                    allowed.set(false)
                    startup.disable()
                }
            } catch (_: Throwable) {
                allowed.set(false)
                runCatching { startup.failure() }
            }
        }
        try {
            preferences = prefs
            selectionText = text
            // Publish before registering: a concurrent disable must never be undone
            // by the install path setting allowed back to true.
            allowed.set(true)
            listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                observeSelection()
            }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
            observeSelection()
            val dex = mutableListOf<ByteBuffer>()
            var total = 0
            val names = mutableSetOf<String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entries = 0
                while (true) {
                    val item = zip.nextEntry ?: break
                    require(++entries <= 4096)
                    val part = zip.bounded(2 * HOOK_LIMIT - total)
                    total += part.size
                    if (item.name.matches(Regex("classes([2-9]|[1-9][0-9]+)?\\.dex"))) {
                        require(names.add(item.name) && names.size <= 8)
                        require(part.size >= 8 && part.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)))
                        dex += ByteBuffer.wrap(part)
                    }
                }
            }
            require(dex.isNotEmpty())
            val loader = InMemoryDexClassLoader(dex.toTypedArray(), HookPlugin::class.java.classLoader)
            val plugin = loader.loadClass(descriptor.entry).getDeclaredConstructor().newInstance() as HookPlugin
            plugin.install(object : HookEnvironment {
                override fun afterString(className: String, methodName: String, callback: HookStringCallback) {
                    check(installing.get() && handles.size < 8)
                    // S1 bridge only hooks the sample's own class namespace, never framework classes.
                    require(className.startsWith("$HOOK_TARGET."))
                    val method = targetLoader.loadClass(className).getDeclaredMethod(methodName)
                    require(method.returnType == String::class.java)
                    handles += hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val original = chain.proceed()
                        if (!enabled() || original !is String) original
                        else try { callback.transform(original) } catch (_: Throwable) {
                            if (faults.incrementAndGet() >= 3) {
                                allowed.set(false)
                                runCatching { startup.failure() }
                            }
                            original
                        }
                    }
                }
            })
            check(handles.isNotEmpty())
            startup.installed()
            observeSelection()
        } catch (error: Throwable) {
            allowed.set(false)
            runCatching { startup.failure() }
            handles.forEach { runCatching { it.unhook() } }
            throw error
        } finally { installing.set(false) }
        // A successful deliberate disable acknowledges immediately. Otherwise, an
        // abnormal exit before this timer still counts conservatively as incomplete.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { startup.stable() }.onFailure { allowed.set(false) }
        }, 30_000)
    }
}
