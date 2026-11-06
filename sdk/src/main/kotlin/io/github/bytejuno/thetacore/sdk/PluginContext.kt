package io.github.bytejuno.thetacore.sdk

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope

/** 宿主交给插件的上下文。 */
interface PluginContext {
    val pluginId: String
    val hostApiLevel: Int
    val appContext: Context

    /** 停用时取消。 */
    val scope: CoroutineScope

    /** 插件私有目录。 */
    val dataDir: File

    fun register(extension: Extension): Registration

    /** 登记自己开的线程、广播接收器等。 */
    fun onCleanup(action: () -> Unit): Registration
}

/** 同一插件内 `id` 不能重复。 */
interface Extension {
    val id: String
}

/** 撤销宿主注册。不代表插件的代码已经停止。 */
fun interface Registration {
    fun revoke()
}
