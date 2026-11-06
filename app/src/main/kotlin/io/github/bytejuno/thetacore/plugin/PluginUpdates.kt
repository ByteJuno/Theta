package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID

internal const val UPDATE_OBSERVATION_MS = 24L * 60 * 60 * 1000

internal enum class UpdatePhase { OBSERVING, ROLLBACK }

/** [startedAt] 是新版本第一次启用成功的时间，观察期从这里算。 */
internal data class PluginUpdate(
    val from: Long,
    val to: Long,
    val phase: UpdatePhase = UpdatePhase.OBSERVING,
    val startedAt: Long? = null,
)

/**
 * 更新的切换和回滚。只在冷启动、加载任何插件之前动数据：旧代码是否已停，别的时机确认不了。
 *
 * 待激活的版本不单独记录，磁盘上比当前版本高的包就是。每一步都能重入，中断后下次启动接着做。
 * 快照位置：`plugin-snapshots/<id>/<from>/`。
 */
internal class PluginUpdates(
    private val filesDir: File,
    private val store: PluginStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val copy: (File, File) -> Unit = ::copyPluginTree,
) {
    private val root get() = pluginPath(filesDir.canonicalFile, "plugin-updates")
    private val snapshots get() = pluginPath(filesDir.canonicalFile, "plugin-snapshots")
    private val data get() = pluginPath(filesDir.canonicalFile, "plugin-data")

    fun read(id: String): PluginUpdate? {
        checkId(id)
        val file = pluginPath(root, "$id.properties")
        if (!file.exists()) return null
        val properties = Properties()
        fun value(key: String) = properties.getProperty(key) ?: throw IOException("Incomplete update record for $id")
        try {
            file.inputStream().use(properties::load)
            return PluginUpdate(
                from = value("from").toLong(),
                to = value("to").toLong(),
                phase = UpdatePhase.valueOf(value("phase")),
                startedAt = properties.getProperty("startedAt")?.toLong(),
            )
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid update record for $id", e)
        }
    }

    /**
     * 返回 true 表示这次回滚到了旧版本。
     *
     * [interrupted] 是上次初始化没有结束。只有新版本还没成功启动过才自动回滚：
     * 成功跑过之后回滚会丢掉用户写入的数据，要由用户决定。
     */
    fun recover(id: String, interrupted: Boolean, safeMode: Boolean, report: (String) -> Unit): Boolean {
        var update = read(id)
        if (update != null && interrupted && update.phase == UpdatePhase.OBSERVING && update.startedAt == null) {
            update = update.copy(phase = UpdatePhase.ROLLBACK).also { write(id, it) }
        }
        if (update?.phase == UpdatePhase.ROLLBACK) {
            rollback(id, update)
            report("$id: rolled back to version ${update.from}")
            return true
        }
        val versions = store.versions(id)
        val highest = versions.lastOrNull() ?: return false
        when {
            update == null -> if (versions.size > 1) activate(id, versions[versions.size - 2], highest, report)
            highest > update.to -> activate(id, update.to, highest, report)
            !safeMode && expired(update) -> finish(id, update)
        }
        return false
    }

    private fun expired(update: PluginUpdate): Boolean =
        update.startedAt != null && now() - update.startedAt >= UPDATE_OBSERVATION_MS

    /** 快照失败默认取消更新，留在旧版本。 */
    private fun activate(id: String, from: Long, to: Long, report: (String) -> Unit) {
        try {
            store.verifyVersion(id, to)
            snapshot(id, from)
        } catch (error: Exception) {
            store.deleteVersion(id, to)
            report("$id: update to version $to cancelled: ${error.message}")
            return
        }
        write(id, PluginUpdate(from, to))
        // 观察期内又来了新版本：上一轮的旧包和快照到这里作废。
        store.versions(id).filter { it != from && it != to }.forEach { store.deleteVersion(id, it) }
        val dir = pluginPath(snapshots, id)
        dir.list().orEmpty().filter { it != from.toString() }.forEach { deletePluginTree(dir, it) }
    }

    private fun snapshot(id: String, version: Long) {
        val dir = pluginPath(snapshots, id)
        Files.createDirectories(dir.toPath())
        val temp = pluginPath(dir, "${UUID.randomUUID()}.tmp")
        val source = pluginPath(data, id)
        withPluginCleanup(cleanup = { deletePluginTree(dir, temp.name) }) {
            if (source.exists()) copy(source, temp) else Files.createDirectories(temp.toPath())
            deletePluginTree(dir, version.toString())
            Files.move(temp.toPath(), pluginPath(dir, version.toString()).toPath(), ATOMIC_MOVE)
        }
    }

    private fun rollback(id: String, update: PluginUpdate) {
        store.verifyVersion(id, update.from)
        val snapshot = pluginPath(pluginPath(snapshots, id), update.from.toString())
        // 恢复之后快照就不在了，重入时跳过这一步，不会删掉刚恢复的数据。
        if (snapshot.exists()) {
            deletePluginTree(data, id)
            Files.createDirectories(data.toPath())
            Files.move(snapshot.toPath(), pluginPath(data, id).toPath(), ATOMIC_MOVE)
        }
        store.versions(id).filter { it > update.from }.forEach { store.deleteVersion(id, it) }
        forget(id)
    }

    private fun finish(id: String, update: PluginUpdate) {
        store.versions(id).filter { it < update.to }.forEach { store.deleteVersion(id, it) }
        forget(id)
    }

    /** 新版本第一次启用成功，观察期开始。 */
    fun started(id: String, version: Long) {
        val update = read(id) ?: return
        if (update.isFirstStartOf(version)) write(id, update.copy(startedAt = now()))
    }

    /** 新版本第一次启用失败：这个进程不能再实例化旧版本，安排到下次冷启动回滚。 */
    fun failed(id: String, version: Long): Boolean {
        val update = read(id) ?: return false
        if (!update.isFirstStartOf(version)) return false
        write(id, update.copy(phase = UpdatePhase.ROLLBACK))
        return true
    }

    private fun PluginUpdate.isFirstStartOf(version: Long) =
        phase == UpdatePhase.OBSERVING && to == version && startedAt == null

    fun requestRollback(id: String) {
        val update = read(id)
        if (update?.phase != UpdatePhase.OBSERVING) throw PluginPackageException("No update to roll back")
        write(id, update.copy(phase = UpdatePhase.ROLLBACK))
    }

    /** 删除快照和记录。记录放最后：中断时下次启动还知道要接着做。 */
    fun forget(id: String) {
        checkId(id)
        deletePluginTree(snapshots, id)
        Files.deleteIfExists(pluginPath(root, "$id.properties").toPath())
    }

    private fun write(id: String, update: PluginUpdate) {
        writePluginProperties(root, "$id.properties", Properties().apply {
            setProperty("from", update.from.toString())
            setProperty("to", update.to.toString())
            setProperty("phase", update.phase.name)
            update.startedAt?.let { setProperty("startedAt", it.toString()) }
        })
    }
}

/** 不跟随链接：符号链接原样复制，不会把数据目录外的文件拷进来。可执行位等属性保留。 */
internal fun copyPluginTree(source: File, target: File) {
    val from = source.toPath()
    val to = target.toPath()
    Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.createDirectories(to.resolve(from.relativize(dir)))
            return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val copied = to.resolve(from.relativize(file))
            Files.copy(file, copied, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
            if (attrs.isRegularFile) FileChannel.open(copied, READ).use { it.force(true) }
            return FileVisitResult.CONTINUE
        }
    })
}
