package io.github.bytejuno.thetacore.root

import android.content.Context
import java.io.File

/** Advisory last explicit execution observation, never an authorization grant. */
internal object RootRuntime {
    @Volatile var rootReady = false
        private set
    fun tool(context: Context) = HostRootTool(RootBridge(
        SuRootBackend(File(context.cacheDir, "root-calls")), onRootConfirmed = { rootReady = it },
    ))
}
