package io.github.bytejuno.thetacore

import android.app.Application
import io.github.bytejuno.thetacore.plugin.PluginManager

class ThetaApplication : Application() {
    internal val plugins by lazy { PluginManager(this) }
}
