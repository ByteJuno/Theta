package io.github.bytejuno.thetacore.sample.hello

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.bytejuno.thetacore.sdk.PluginContext
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.ThetaPlugin

class HelloPlugin : ThetaPlugin {
    override suspend fun onEnable(ctx: PluginContext) {
        ctx.register(HelloPage())
    }
}

private class HelloPage : SettingsPage {
    override val id = "hello"
    override val title = "Hello"

    @Composable
    override fun Content() {
        Text("Hello from a plugin")
    }
}
