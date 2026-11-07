package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.PluginClosedException

/** 登记和关闭共用一把锁；资源清理在锁外执行，避免插件回调重入死锁。 */
internal class PluginRegistrations<T>(
    private val publish: (List<T>) -> Unit = {},
) {
    private class Entry<T>(
        val id: String?,
        var value: T?,
        var cleanup: (() -> Unit)?,
    )

    private val lock = Any()
    private val entries = mutableListOf<Entry<T>>()
    private var closed = false

    fun register(id: String, value: T): () -> Unit {
        val entry = synchronized(lock) {
            checkOpen()
            require(entries.none { it.id == id }) { "Duplicate extension id: $id" }
            Entry(id, value, null).also {
                entries += it
                publishExtensions()
            }
        }
        return { revoke(entry) }
    }

    fun onCleanup(action: () -> Unit): () -> Unit {
        val entry = synchronized(lock) {
            checkOpen()
            Entry<T>(null, null, action).also { entries += it }
        }
        return { revoke(entry) }
    }

    /** 先关闭登记入口并移除扩展，再取消作用域，最后倒序清理资源。 */
    fun beginClose(): List<() -> Unit>? = synchronized(lock) {
        if (closed) return null
        closed = true
        val pending = entries.asReversed().mapNotNull { entry ->
            val action = entry.cleanup
            entry.cleanup = null
            entry.value = null
            action
        }
        entries.clear()
        publishExtensions()
        pending
    }

    fun close(beforeCleanup: () -> Unit = {}): List<Throwable> {
        val actions = beginClose() ?: return emptyList()
        val failures = mutableListOf<Throwable>()
        runCatching(beforeCleanup).exceptionOrNull()?.let(failures::add)
        actions.forEach { action ->
            runCatching(action).exceptionOrNull()?.let(failures::add)
        }
        return failures
    }

    private fun revoke(entry: Entry<T>) {
        val action = synchronized(lock) {
            if (!entries.remove(entry)) return
            val cleanup = entry.cleanup
            entry.cleanup = null
            entry.value = null
            if (entry.id != null) publishExtensions()
            cleanup
        }
        action?.invoke()
    }

    private fun checkOpen() {
        if (closed) throw PluginClosedException()
    }

    // publish 是宿主内部的状态更新，不执行插件代码；在同一把锁下保持快照顺序。
    private fun publishExtensions() {
        publish(entries.mapNotNull { it.value })
    }
}
