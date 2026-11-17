package io.github.bytejuno.thetacore.plugin

import android.content.ContextWrapper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.AgentDatabase
import io.github.bytejuno.thetacore.agent.ledger.StoreException
import io.github.bytejuno.thetacore.agent.protocol.Role
import io.github.bytejuno.thetacore.agent.protocol.message
import io.github.bytejuno.thetacore.agent.protocol.textPart
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class AndroidInvocationOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(root: File) = object : ContextWrapper(null) {
        override fun getNoBackupFilesDir() = root
        override fun getFilesDir(): File = throw AssertionError("Must use no-backup storage")
    }
    private fun link(path: Path, target: Path) {
        try { Files.createSymbolicLink(path, target) }
        catch (e: java.io.IOException) { assumeNoException("Host cannot create symbolic links", e) }
        catch (e: UnsupportedOperationException) { assumeNoException(e) }
    }
    // Cross-module test access to core's internal lifecycle hooks, not production API.
    private fun internalCall(target: Any, name: String): Any? = target.javaClass.methods.single {
        (it.name == name || it.name.startsWith(name + "$")) && it.parameterCount == 0
    }.invoke(target)

    @Test fun trustedContextAliasResolvesButInjectedAliasAndDuplicateWriterAreRejected() {
        val real = temporary.newFolder("real")
        val noBackup = File(real, "no_backup").apply { mkdir() }
        val alias = temporary.root.toPath().resolve("system-alias")
        link(alias, real.toPath())
        try {
            val aliased = alias.resolve("no_backup").toFile()
            AndroidInvocationStore.open(context(aliased), BundledSQLiteDriver()).use { store ->
                store.checkAdmission()
                assertTrue(File(noBackup, AgentDatabase.FILE_NAME).isFile)
                assertThrows(StoreException::class.java) { AndroidInvocationStore.open(context(noBackup), BundledSQLiteDriver()) }
                assertThrows(StoreException::class.java) { AndroidInvocationStore.open(aliased, BundledSQLiteDriver()) }
            }
            AndroidInvocationStore.open(context(noBackup), BundledSQLiteDriver()).close()
            AndroidInvocationStore.open(context(aliased), BundledSQLiteDriver()).close()
            val raw = File(noBackup, "../no_backup")
            assertThrows(IllegalArgumentException::class.java) { AndroidInvocationStore.open(context(raw), BundledSQLiteDriver()) }
            assertThrows(StoreException::class.java) { AndroidInvocationStore.open(raw, BundledSQLiteDriver()) }
        } finally { Files.deleteIfExists(alias) }
    }

    @Test fun contextResolutionDoesNotFollowDatabaseSidecarOrSummaryDescendantLinks() {
        for (suffix in listOf("", ".writer.lock", "-wal", "-shm", "-journal", ".summaries", ".summaries/" + "a".repeat(64),
            ".summaries/" + "a".repeat(64) + "/summary-tmp")) {
            val root = temporary.newFolder()
            val outside = temporary.newFolder()
            val target = if (suffix.startsWith(".summaries")) outside.toPath()
                else File(outside, "retained").apply { writeText("external evidence") }.toPath()
            val path = File(root, AgentDatabase.FILE_NAME + suffix).toPath()
            Files.createDirectories(path.parent)
            link(path, target)
            try {
                assertThrows(StoreException::class.java) { AndroidInvocationStore.open(context(root), BundledSQLiteDriver()) }
                assertTrue(Files.isSymbolicLink(path))
                if (Files.isRegularFile(target)) assertEquals("external evidence", String(Files.readAllBytes(target)))
                else assertEquals(0, outside.listFiles()!!.size)
                if (suffix != ".writer.lock") assertFalse(File(root, "agent.db.writer.lock").exists())
            } finally { Files.deleteIfExists(path) }
        }
    }

    @Test fun rejectedRunAndSpoolCloseLeaveLedgerAndOwnerUsable() {
        val root = temporary.newFolder()
        AndroidInvocationStore.open(root, BundledSQLiteDriver()).use { store ->
            store.createHistory("session").use { history ->
                internalCall(history, "beginRun")
                try {
                    assertThrows(IllegalStateException::class.java) { store.close() }
                    store.checkAdmission(); assertTrue(store.records().isEmpty())
                    history.append(0, listOf(message(Role.USER, textPart("still live"))))
                    assertThrows(StoreException::class.java) { AndroidInvocationStore.open(root, BundledSQLiteDriver()) }
                } finally { internalCall(history, "endRun") }
                val spool = internalCall(history, "createSummarySpool") as AutoCloseable
                try {
                    assertThrows(IllegalStateException::class.java) { store.close() }
                    store.checkAdmission(); assertTrue(store.records().isEmpty())
                    assertEquals(1L, history.revision())
                } finally { spool.close() }
            }
            store.close(); store.close()
        }
        AndroidInvocationStore.open(root, BundledSQLiteDriver()).use {
            it.openHistory("session").use { history -> assertEquals(1L, history.revision()) }
        }
    }

    @Test fun poisonedOwnerCanStillReleaseRunAndSpoolPinsAndClose() {
        val root = temporary.newFolder()
        AndroidInvocationStore.open(root, BundledSQLiteDriver()).use { store ->
            store.createHistory("session").use { history ->
                internalCall(history, "beginRun")
                val spool = internalCall(history, "createSummarySpool") as AutoCloseable
                val database = AndroidInvocationStore::class.java.getDeclaredField("database")
                    .apply { isAccessible = true }.get(store)
                internalCall(database, "poison")
                try {
                    assertThrows(IllegalStateException::class.java) { store.close() }
                    assertTrue(store.records().isEmpty())
                    assertThrows(StoreException::class.java) { store.checkAdmission() }
                } finally { spool.close(); internalCall(history, "endRun") }
            }
            store.close(); store.close()
        }
        AndroidInvocationStore.open(root, BundledSQLiteDriver()).close()
    }
}
