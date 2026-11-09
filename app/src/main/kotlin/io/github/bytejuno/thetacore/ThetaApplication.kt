package io.github.bytejuno.thetacore

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.github.bytejuno.thetacore.plugin.FilePluginRecoveryStorage
import io.github.bytejuno.thetacore.plugin.PLUGIN_STABLE_WINDOW_MS
import io.github.bytejuno.thetacore.plugin.PluginCrashRecovery
import io.github.bytejuno.thetacore.plugin.PluginExitHistory
import io.github.bytejuno.thetacore.plugin.PluginExitReason
import io.github.bytejuno.thetacore.plugin.PluginManager
import io.github.bytejuno.thetacore.plugin.PluginProcessExit
import io.github.bytejuno.thetacore.plugin.PluginRecoveryClock
import io.github.bytejuno.thetacore.plugin.PluginRecoveryUncaughtHandler
import java.io.File
import android.util.Log
import io.github.bytejuno.thetacore.plugin.HostToolExecutionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class ThetaApplication : Application() {
    internal val plugins by lazy { PluginManager(this) }
    private val hostToolsOwner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var hostToolsStartup: Deferred<HostToolExecutionService>

    /** Consumers share startup and its failure; never substitute an in-memory ledger. */
    internal suspend fun hostTools(): HostToolExecutionService = hostToolsStartup.await()

    private val hostToolBoundary = io.github.bytejuno.thetacore.plugin.HostToolRequestBoundary { request ->
        io.github.bytejuno.thetacore.plugin.HostToolRuntimeApi(hostTools()).request(request)
    }

    /** Suspending, trusted in-process contract, not IPC. No UI/model consumer exists yet. */
    suspend fun hostToolRequest(request: String): String = hostToolBoundary.request(request)

    /** Only Android's dedicated Instrumentation.onStart thread may block. Never coroutine pools. */
    fun hostToolRequestForInstrumentation(request: String): String =
        hostToolBoundary.requestForInstrumentation(request)
    internal lateinit var crashRecovery: PluginCrashRecovery
        private set

    override fun onCreate() {
        super.onCreate()
        crashRecovery = PluginCrashRecovery(
            FilePluginRecoveryStorage(File(noBackupFilesDir, "plugin-crash-recovery")),
            object : PluginRecoveryClock {
                override fun wallMillis() = System.currentTimeMillis()
                override fun elapsedMillis() = SystemClock.elapsedRealtime()
            },
            PluginExitHistory {
                if (Build.VERSION.SDK_INT >= 30) {
                    getSystemService(ActivityManager::class.java)
                        ?.getHistoricalProcessExitReasons(packageName, 0, 32).orEmpty()
                        .filter { it.processName == Application.getProcessName() }
                        .map {
                            PluginProcessExit(it.pid, it.timestamp, when (it.reason) {
                                ApplicationExitInfo.REASON_CRASH -> PluginExitReason.CRASH
                                ApplicationExitInfo.REASON_CRASH_NATIVE -> PluginExitReason.NATIVE_CRASH
                                else -> PluginExitReason.OTHER
                            })
                        }
                } else emptyList()
            },
            Process.myPid(),
        )
        Thread.setDefaultUncaughtExceptionHandler(PluginRecoveryUncaughtHandler(
            crashRecovery, Thread.getDefaultUncaughtExceptionHandler(),
        ) {
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        })
        // One process-wide timer, not an Activity timer. Stable does not leave safe mode.
        Handler(Looper.getMainLooper()).postDelayed({ crashRecovery.markStableIfDue() }, PLUGIN_STABLE_WINDOW_MS)
        // Process-owned and eager, but never perform ledger IO/schema initialization on Main.
        // A failed Deferred stays failed for this process; there is no in-memory fallback.
        hostToolsStartup = hostToolsOwner.async {
            try {
                plugins.initializeHostTools().also {
                    Log.i("ThetaHostTools", "Host tools initialized")
                }
            }
            catch (error: Exception) {
                Log.e("ThetaHostTools", "Host tools unavailable; restart required", error)
                throw error
            }
        }
    }
}
