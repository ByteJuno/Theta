package io.github.bytejuno.thetacore.sdk

import androidx.compose.runtime.Composable

/** 插件的设置页，从“扩展”页面进入。 */
interface SettingsPage : Extension {
    val title: String

    @Composable
    fun Content()
}
