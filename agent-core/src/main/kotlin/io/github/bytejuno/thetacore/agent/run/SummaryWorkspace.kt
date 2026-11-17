package io.github.bytejuno.thetacore.agent.run

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Validate the raw path BEFORE any normalization or writes. Reject dot components rather than
 * guessing whether lexical or link-aware resolution was intended. Missing descendants are allowed
 * only for creation; existing ancestors must all be real directories. Host-private paths are required:
 * this is a static-path check, not protection against concurrent hostile filesystem replacement.
 */
internal fun checkedSessionDirectory(path: Path, allowMissing: Boolean = false): Path {
    require(path.none { it.toString() == "." || it.toString() == ".." }) { "Session path must not contain dot components" }
    val absolute = path.toAbsolutePath()
    require(absolute.none { it.toString() == "." || it.toString() == ".." }) { "Session path must not contain dot components" }
    var current = absolute.root
    fun checkDirectory() {
        try {
            require(Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory) {
                "Session path must not contain links or non-directories"
            }
        } catch (e: NoSuchFileException) {
            if (!allowMissing) throw e
        }
    }
    checkDirectory()
    for (part in absolute) {
        current = current.resolve(part)
        checkDirectory()
    }
    return absolute
}

/** Disposable derived data, separate from history/checkpoints/ledger. The session's existing
 * exclusive writer owns this workspace: construct only AFTER acquiring that ownership, and do
 * not release it while a spool is in use. No global temp scan, age heuristic or recursive delete.
 * The containing session directory must be private to the host, like its durable storage.
 */
internal class SummaryWorkspace(sessionDirectory: Path) {
    private val directory = checkedSessionDirectory(sessionDirectory).resolve("summary-tmp")
    private val names = Regex("theta-summary-[0-9]+\\.data")

    init {
        checkDirectories(directory.parent)
        try { Files.createDirectory(directory) }
        catch (_: java.nio.file.FileAlreadyExistsException) { /* Validate without following links below. */ }
        checkDirectories(directory)
        Files.newDirectoryStream(directory).use { paths ->
            for (path in paths) if (names.matches(path.fileName.toString()) &&
                Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.delete(path)
        }
    }

    fun create(): Path {
        checkDirectories(directory)
        return Files.createTempFile(directory, "theta-summary-", ".data")
    }

    fun delete(path: Path) {
        require(path.parent == directory && names.matches(path.fileName.toString()))
        checkDirectories(directory)
        // Delete only a direct regular scratch file, never a directory or a symlink target.
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.delete(path)
    }

    private fun checkDirectories(path: Path) {
        checkedSessionDirectory(path)
    }
}

/** Pins the history owner's lock until the summarizer's finally block has closed its streams. */
internal class SummarySpool(val path: Path, private val release: () -> Unit) : AutoCloseable {
    private var closed = false
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}
