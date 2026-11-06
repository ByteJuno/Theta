package io.github.bytejuno.thetacore.sample.probe

import android.util.Log
import androidx.compose.runtime.Composable
import io.github.bytejuno.thetacore.sdk.PluginContext
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.ThetaPlugin

/** S1 的探针：在开了 R8 的宿主里，把插件可能碰到的链接方式都走一遍。不是写插件的范例。 */
class ProbePlugin : ThetaPlugin {
    override suspend fun onEnable(ctx: PluginContext) {
        ctx.register(ProbePage())
        ctx.onCleanup { Log.i("ThetaProbeLifecycle", "cleanup:first") }
        ctx.onCleanup { Log.i("ThetaProbeLifecycle", "cleanup:second") }
    }

    override suspend fun onDisable() {
        Log.i("ThetaProbeLifecycle", "onDisable")
    }
}

private class ProbePage : SettingsPage {
    override val id = "probe"
    override val title = "Probe"

    @Composable
    override fun Content() {
        ProbeScreen()
    }
}
