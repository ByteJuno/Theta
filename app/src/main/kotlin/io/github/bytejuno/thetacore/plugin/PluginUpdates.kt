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

internal enum class UpdatePhase { OBSERVING, ROLLBACK, RECOVERED }

/** [startedAt] 是新版本第一次启用成功的时间，观察期从这里算。 */
internal data class PluginUpdate(
    val from: Long,
    val to: Long,
    val phase: UpdatePhase = UpdatePhase.OBSERVING,
    val startedAt: Long? = null,
    val dataRestorable: Boolean = true,
)

/** One confirmation for one install attempt, never a persistent per-plugin preference. */
internal data class PluginUpdateInstallIntent internal constructor(
    val id: String,
    val from: Long,
    val to: Long,
    val token: String,
    val dataRestorable: Boolean,
)

private data class PendingPluginUpdate(val intent: PluginUpdateInstallIntent, val committed: Boolean)

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
    private val intents get() = pluginPath(root, "install-intents")
    private fun intentFile(id: String) = pluginPath(intents, "$id.properties")

    /**
     * Call under the manager's serialized IO operation, after validation/confirmation and
     * BEFORE store.install, for EVERY upgrade (including the default false choice).
     * Preparing is not authorization: only commitInstall after successful publication grants it.
     * A crash between publication and commit conservatively uses snapshot-or-cancel.
     */
    fun prepareInstall(id: String, from: Long, to: Long, allowCodeOnlyRollback: Boolean = false): PluginUpdateInstallIntent {
        checkId(id)
        val update = read(id)
        readInstallIntent(id) // Never overwrite a malformed recovery owner.
        val versions = store.versions(id)
        if (from <= 0 || to <= from || versions.lastOrNull() != from ||
            (update == null && versions.size != 1) ||
            (update != null && (update.phase != UpdatePhase.OBSERVING || update.to != from))) {
            throw PluginPackageException("Update confirmation does not match installed version")
        }
        return PluginUpdateInstallIntent(id, from, to, UUID.randomUUID().toString(), !allowCodeOnlyRollback).also {
            writeInstallIntent(PendingPluginUpdate(it, committed = false))
        }
    }

    /** Commit only when store.install returned successfully; do not infer success from a catch. */
    fun commitInstall(intent: PluginUpdateInstallIntent) {
        requireInstallIntent(intent)
        val versions = store.versions(intent.id)
        if (versions.lastOrNull() != intent.to || intent.from !in versions) {
            throw PluginPackageException("Update package does not match confirmation")
        }
        store.verifyVersion(intent.id, intent.to)
        writeInstallIntent(PendingPluginUpdate(intent, committed = true))
    }

    /**
     * Cancel this exact attempt on install/commit failure, not after a successful commit.
     * If this throws, retain recovery quarantine; a PREPARED record grants no permission.
     * A stale handle cannot cancel a later confirmation. Packages are owned by PluginStore.
     */
    fun cancelInstall(intent: PluginUpdateInstallIntent) {
        checkId(intent.id)
        val pending = readInstallIntent(intent.id) ?: return
        if (pending.intent != intent) throw PluginPackageException("Stale update confirmation")
        clearInstallIntent(intent.id)
    }

    private fun requireInstallIntent(intent: PluginUpdateInstallIntent) {
        checkId(intent.id)
        if (readInstallIntent(intent.id)?.intent != intent) throw PluginPackageException("Stale update confirmation")
    }

    private fun clearInstallIntent(id: String) {
        Files.deleteIfExists(intentFile(id).toPath())
    }

    private fun readInstallIntent(id: String): PendingPluginUpdate? {
        checkId(id)
        val file = intentFile(id)
        if (!file.exists()) return null
        val properties = Properties()
        fun value(key: String) = properties.getProperty(key) ?: throw IOException("Incomplete update policy for $id")
        try {
            file.inputStream().use(properties::load)
            if (properties.stringPropertyNames() != setOf("from", "to", "token", "dataRestorable", "committed")) {
                throw IOException("Invalid update policy fields for $id")
            }
            val from = value("from").toLong()
            val to = value("to").toLong()
            val token = value("token")
            require(from > 0 && to > from && UUID.fromString(token).toString() == token)
            return PendingPluginUpdate(
                PluginUpdateInstallIntent(id, from, to, token, value("dataRestorable").toBooleanStrict()),
                value("committed").toBooleanStrict(),
            )
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid update policy for $id", error)
        }
    }

    private fun writeInstallIntent(pending: PendingPluginUpdate) {
        val intent = pending.intent
        writePluginProperties(intents, "${intent.id}.properties", Properties().apply {
            setProperty("from", intent.from.toString())
            setProperty("to", intent.to.toString())
            setProperty("token", intent.token)
            setProperty("dataRestorable", intent.dataRestorable.toString())
            setProperty("committed", pending.committed.toString())
        })
    }

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
                dataRestorable = properties.getProperty("dataRestorable")?.toBooleanStrict() ?: true,
            ).also {
                require(it.from > 0 && it.to > it.from && (it.startedAt == null || it.startedAt >= 0))
            }
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
        val pending = readInstallIntent(id) // Parse before any destructive recovery; corruption retains its owner.
        if (update != null && interrupted && update.phase == UpdatePhase.OBSERVING && update.startedAt == null) {
            update = update.copy(phase = UpdatePhase.ROLLBACK).also { write(id, it) }
        }
        if (update?.phase == UpdatePhase.ROLLBACK) {
            rollback(id, update)
            // Retain a durable receipt until the manager restores desired enabled state.
            write(id, update.copy(phase = UpdatePhase.RECOVERED))
            report("$id: rolled back to version ${update.from}")
            return true
        }
        // Never restore data again while waiting for the state write/acknowledgement.
        if (update?.phase == UpdatePhase.RECOVERED) return true
        val versions = store.versions(id)
        val highest = versions.lastOrNull()
        if (highest == null) {
            clearInstallIntent(id)
            return false
        }
        when {
            update == null && versions.size > 1 -> activate(id, versions[versions.size - 2], highest, pending, report)
            update != null && highest > update.to -> activate(id, update.to, highest, pending, report)
            else -> {
                // No pending upgrade, or replay after the update record was committed.
                // Never let yesterday's confirmation authorize a later reinstall.
                clearInstallIntent(id)
                if (update != null && !safeMode && expired(update)) finish(id, update)
            }
        }
        return false
    }

    private fun expired(update: PluginUpdate): Boolean =
        update.startedAt != null && now() - update.startedAt >= UPDATE_OBSERVATION_MS

    /** 快照失败默认取消更新，留在旧版本。 */
    private fun activate(id: String, from: Long, to: Long, pending: PendingPluginUpdate?, report: (String) -> Unit) {
        val codeOnly = pending?.let {
            it.committed && !it.intent.dataRestorable && it.intent.from == from && it.intent.to == to
        } == true
        try {
            store.verifyVersion(id, to)
            if (!codeOnly) snapshot(id, from)
        } catch (error: Exception) {
            // Before any plugin loads, so only host and JDK exceptions reach this message.
            store.deleteVersion(id, to)
            clearInstallIntent(id)
            report("$id: update to version $to cancelled: ${error.message}")
            return
        }
        // Persist mode before retiring intent/snapshots. Replay reads this record, never a
        // leftover snapshot, even if cleanup or the process fails immediately afterwards.
        write(id, PluginUpdate(from, to, dataRestorable = !codeOnly))
        clearInstallIntent(id)
        // 观察期内又来了新版本：上一轮的旧包和快照到这里作废。
        store.versions(id).filter { it != from && it != to }.forEach { store.deleteVersion(id, it) }
        val dir = pluginPath(snapshots, id)
        dir.list().orEmpty().filter { codeOnly || it != from.toString() }.forEach { deletePluginTree(dir, it) }
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
        if (update.dataRestorable) {
            val snapshot = pluginPath(pluginPath(snapshots, id), update.from.toString())
            // 恢复之后快照就不在了，重入时跳过这一步，不会删掉刚恢复的数据。
            if (snapshot.exists()) {
                deletePluginTree(data, id)
                Files.createDirectories(data.toPath())
                Files.move(snapshot.toPath(), pluginPath(data, id).toPath(), ATOMIC_MOVE)
            }
        }
        store.versions(id).filter { it > update.from }.forEach { store.deleteVersion(id, it) }
    }

    fun acknowledgeRecovery(id: String) {
        check(read(id)?.phase == UpdatePhase.RECOVERED)
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

    private fun eligible(update: PluginUpdate?): Boolean =
        update?.phase == UpdatePhase.OBSERVING && !expired(update)

    /** Read-only notice state, including the original first-success time and rollback mode. */
    fun observation(id: String): PluginUpdate? = read(id)?.takeIf { it.phase == UpdatePhase.OBSERVING }

    fun canRollback(id: String): Boolean = eligible(read(id))

    fun requestRollback(id: String) {
        val update = read(id)
        // Recheck the injected clock at the action, even when the UI flag is stale.
        if (!eligible(update)) throw PluginPackageException("No update within the rollback observation period")
        write(id, requireNotNull(update).copy(phase = UpdatePhase.ROLLBACK))
    }

    /** 删除快照和记录。记录放最后：中断时下次启动还知道要接着做。 */
    fun forget(id: String) {
        checkId(id)
        deletePluginTree(snapshots, id)
        clearInstallIntent(id)
        Files.deleteIfExists(pluginPath(root, "$id.properties").toPath())
    }

    private fun write(id: String, update: PluginUpdate) {
        writePluginProperties(root, "$id.properties", Properties().apply {
            setProperty("from", update.from.toString())
            setProperty("to", update.to.toString())
            setProperty("phase", update.phase.name)
            setProperty("dataRestorable", update.dataRestorable.toString())
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

/** IO only: package/data recovery -> state intent -> receipt acknowledgement.
 * A failed state write leaves RECOVERED for the next cold start; no plugin is loaded
 * before all three succeed. writeState is a cutpoint seam, not a second recovery path.
 */
internal fun recoverPluginUpdate(
    states: PluginStateStore,
    updates: PluginUpdates,
    id: String,
    state: PluginState,
    safeMode: Boolean,
    report: (String) -> Unit,
    writeState: (String, PluginState) -> Unit = states::write,
): Boolean {
    if (!updates.recover(id, state.initializing, safeMode, report)) return false
    writeState(id, state.copy(
        enabled = state.enabled || state.resumeAfterRollback,
        initializing = false, resumeAfterRollback = false,
    ))
    updates.acknowledgeRecovery(id)
    return true
}
