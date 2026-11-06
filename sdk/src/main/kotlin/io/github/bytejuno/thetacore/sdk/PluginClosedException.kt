package io.github.bytejuno.thetacore.sdk

/** Context 开始关闭后，不能再登记扩展或清理动作。 */
class PluginClosedException : IllegalStateException("Plugin context is closed")
