package io.github.bytejuno.thetacore.sdk

/** SDK 的 API 等级，规则见 DESIGN.md 第 8 节。 */
const val THETA_API_LEVEL: Int = 1

/** dex 插件的入口，类名写在清单的 `entry` 字段。 */
interface ThetaPlugin {
    /** 注册扩展。不要做耗时工作。超时判定启用失败，宿主撤销已有注册并标记待重启。 */
    suspend fun onEnable(ctx: PluginContext)

    /** 释放宿主管不到的资源，关闭数据库和文件。超时判定清理失败，宿主继续撤销注册并标记待重启。 */
    suspend fun onDisable() {}
}
