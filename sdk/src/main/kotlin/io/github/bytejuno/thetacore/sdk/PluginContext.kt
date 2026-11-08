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

    /** 宿主管理的普通设置，与 dataDir 一同快照和回滚。 */
    val prefs: PluginPrefs

    /** 加密落盘的设置；完整信任同进程插件之间不构成保密边界。 */
    val secrets: PluginSecrets

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
