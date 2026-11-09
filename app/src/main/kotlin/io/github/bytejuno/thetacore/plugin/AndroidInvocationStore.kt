package io.github.bytejuno.thetacore.plugin

import android.system.Os
import android.system.OsConstants
import io.github.bytejuno.thetacore.agent.ledger.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

internal enum class InitializationStage { AFTER_STAGE_CREATE, AFTER_MARKER, AFTER_STAGE_SYNC, AFTER_RENAME, AFTER_PARENT_SYNC }

/** App-private, excluded from backup: never restore invocation ownership on a different device.
 * JsonInvocationStore forces the temp file before atomic replacement. AFTER_REPLACE runs before
 * its in-memory acknowledgement, so an Android directory fsync failure poisons that writer.
 * This guarantees the fsync protocol, not survival of broken storage hardware or restored data.
 */
internal class AndroidInvocationStore private constructor(private val delegate: InvocationStore) : InvocationStore {
    private var failed = false
    override fun bindCoordinator() = delegate.bindCoordinator()
    override fun records() = delegate.records()
    @Synchronized override fun save(record: InvocationRecord, expectedRevision: Long?) {
        if (failed) throw StoreException("Android ledger storage failed; restart required", true)
        try { delegate.save(record, expectedRevision) }
        catch (error: StoreException) { failed = true; throw error }
    }
    override fun close() = delegate.close()

    companion object {
        const val DIRECTORY = "host-invocations"
        fun open(noBackupFilesDir: File): AndroidInvocationStore =
            open(noBackupFilesDir, ::syncAndroidDirectory)

        /** Initialization cutpoints only simulate process loss; never expose a staged writer. */
        internal fun open(root: File, syncDirectory: (File) -> Unit): AndroidInvocationStore =
            open(root, {}, syncDirectory)

        internal fun open(root: File, cutpoint: (InitializationStage) -> Unit,
            syncDirectory: (File) -> Unit): AndroidInvocationStore {
            val parent = root.canonicalFile
            val directory = File(parent, DIRECTORY)
            var opened: JsonInvocationStore? = null
            try {
                check(parent.isDirectory) { "No private no-backup directory" }
                // Serialize first-install publication too, before JsonInvocationStore owns its lock.
                FileChannel.open(File(parent, "$DIRECTORY.lock").toPath(), CREATE, WRITE).use { channel ->
                    val lock = channel.tryLock() ?: throw StoreException("Android ledger initialization already active")
                    lock.use {
                        val path = directory.toPath()
                        check(!Files.isSymbolicLink(path)) { "Ledger directory must not be a symlink" }
                        if (!Files.exists(path, NOFOLLOW_LINKS)) {
                            // Only this reserved, unpublished directory may be discarded. Never repair
                            // an existing formal directory, even when its marker is absent/corrupt.
                            val staging = File(parent, ".$DIRECTORY.initializing").toPath()
                            removeStaging(staging)
                            Files.createDirectory(staging)
                            cutpoint(InitializationStage.AFTER_STAGE_CREATE)
                            JsonInvocationStore.create(staging, Durability.PROCESS_CRASH_ONLY).use {
                                cutpoint(InitializationStage.AFTER_MARKER)
                            }
                            syncDirectory(staging.toFile())
                            cutpoint(InitializationStage.AFTER_STAGE_SYNC)
                            Files.move(staging, path, ATOMIC_MOVE)
                            cutpoint(InitializationStage.AFTER_RENAME)
                        }
                        // Also sync on retry after an unacknowledged rename. No tool records exist
                        // until publication is durable and the final-path writer has been reopened.
                        syncDirectory(parent)
                        cutpoint(InitializationStage.AFTER_PARENT_SYNC)
                        val afterReplace: (WriteStage) -> Unit = { stage ->
                            if (stage == WriteStage.AFTER_REPLACE) syncDirectory(directory)
                        }
                        opened = JsonInvocationStore.open(path, Durability.PROCESS_CRASH_ONLY, afterReplace)
                        syncDirectory(directory)
                        return AndroidInvocationStore(checkNotNull(opened))
                    }
                }
            } catch (error: Exception) {
                try { opened?.close() } catch (close: Exception) { error.addSuppressed(close) }
                throw StoreException("Cannot initialize Android invocation ledger", true, error)
            }
        }
    }
}

/** walkFileTree does not follow links: an abandoned stage cannot redirect deletion. */
private fun removeStaging(path: Path) {
    if (!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    })
}

private fun syncAndroidDirectory(directory: File) {
    // O_DIRECTORY is not in Android's public OsConstants API. Check the opened fd instead.
    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
    try {
        check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Not a ledger directory" }
        Os.fsync(descriptor)
    } finally { Os.close(descriptor) }
}
