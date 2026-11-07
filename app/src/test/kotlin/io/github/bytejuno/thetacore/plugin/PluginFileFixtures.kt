package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView

/** Android/Linux unlinks a read-only APK using directory permissions. Windows instead denies
 * deletion of a DOS-readonly file. Model unlink ONLY at injected deletion boundaries, and only
 * under the current test's owned root. Production still creates/loads read-only APKs unchanged.
 */
internal fun fixturePluginStore(
    filesDir: File,
    cacheDir: File,
    verifier: PluginSignatureVerifier,
    cleanup: PluginStageCleanup = PluginStageCleanup(),
    deleteStage: (File) -> Unit = { deleteFixtureFile(filesDir, it) },
    publicationBoundary: (PluginPublicationBoundary) -> Unit = {},
    deletePublicationIntent: (File) -> Unit = { Files.delete(it.toPath()) },
): PluginStore = PluginStore(filesDir, cacheDir, verifier, cleanup, deleteStage, publicationBoundary,
    deletePublicationIntent, deletePackages = { parent, name -> deleteFixtureTree(filesDir, parent, name) })

internal fun deleteFixtureFile(owner: File, file: File) {
    val owned = owner.canonicalFile.toPath()
    val target = file.toPath().toAbsolutePath().normalize()
    check(target.startsWith(owned)) { "Not an owned test fixture: $target" }
    target.parent?.let { pluginPath(it.toFile(), ".") } // reject linked ancestors
    if (System.getProperty("os.name").startsWith("Windows") && Files.isRegularFile(target, NOFOLLOW_LINKS)) {
        val view = Files.getFileAttributeView(target, DosFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (view.readAttributes().isReadOnly) view.setReadOnly(false)
    }
    Files.deleteIfExists(target)
}

internal fun deleteFixtureTree(owner: File, parent: File, name: String) {
    val safeParent = pluginPath(parent, ".").toPath()
    val target = safeParent.resolve(name).normalize()
    check(target.parent == safeParent && target.startsWith(owner.canonicalFile.toPath())) {
        "Not an owned test fixture tree: $target"
    }
    if (!Files.exists(target, NOFOLLOW_LINKS)) return
    Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deleteFixtureFile(owner, file.toFile())
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

internal fun deleteFixtureRoot(root: File) = deleteFixtureTree(root, root.parentFile, root.name)
