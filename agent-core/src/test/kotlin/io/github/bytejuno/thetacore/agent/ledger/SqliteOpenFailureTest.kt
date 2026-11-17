package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SqliteOpenFailureTest {
    private class OpenError : Error("synthetic open failure")
    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("sqlite-open-error")
        try { block(root.resolve(AgentDatabase.FILE_NAME)) }
        finally { root.toFile().deleteRecursively() }
    }

    @Test fun errorDuringOpenPropagatesAndReleasesTheWriterSlot() = temporary { file ->
        val fatal = OpenError()
        val driver = object : SQLiteDriver by BundledSQLiteDriver() {
            override fun open(fileName: String): SQLiteConnection = throw fatal
        }
        assertSame(fatal, assertFailsWith<OpenError> { AgentDatabase.open(file, driver) })
        assertEquals("P", Files.readString(file.resolveSibling("agent.db.writer.lock")))
        SqliteInvocationStore.open(file, BundledSQLiteDriver()).use { assertTrue(it.records().isEmpty()) }
    }

    @Test fun everyInitializationErrorClosesConnectionAndPreservesProvenance() {
        for (established in listOf(false, true)) for (stage in SqliteInitStage.entries) temporary { file ->
            if (established) AgentDatabase.open(file, BundledSQLiteDriver()).close()
            val fatal = OpenError()
            var opened = 0
            var closed = 0
            val driver = object : SQLiteDriver by BundledSQLiteDriver() {
                override fun open(fileName: String): SQLiteConnection {
                    val delegate = BundledSQLiteDriver().open(fileName)
                    opened++
                    return object : SQLiteConnection by delegate {
                        override fun close() { closed++; delegate.close() }
                    }
                }
            }
            assertSame(fatal, assertFailsWith<OpenError> {
                AgentDatabase.openForInitialization(file, driver) { if (it == stage) throw fatal }
            })
            assertEquals(opened, closed, "$established/$stage")
            val marker = file.resolveSibling("agent.db.writer.lock")
            assertEquals(if (established || stage == SqliteInitStage.AFTER_ESTABLISHED) "PE" else "P",
                Files.readString(marker), "$established/$stage")
            AgentDatabase.open(file, BundledSQLiteDriver()).use {
                assertEquals(AgentDatabase.SCHEMA_VERSION.toLong(), it.connection.longValue("PRAGMA user_version"))
            }
            assertEquals("PE", Files.readString(marker))
            // Releasing the slot must not weaken established-evidence checks.
            Files.write(file, byteArrayOf())
            assertFailsWith<StoreException> { AgentDatabase.open(file, BundledSQLiteDriver()) }
            assertEquals(0L, Files.size(file))
        }
    }

    @Test fun rollbackAndCloseFailuresCannotMaskFatalInitializationError() = temporary { file ->
        val fatal = OpenError()
        var rollbackAttempted = false
        var closeAttempted = false
        val driver = object : SQLiteDriver by BundledSQLiteDriver() {
            override fun open(fileName: String): SQLiteConnection {
                val delegate = BundledSQLiteDriver().open(fileName)
                return object : SQLiteConnection by delegate {
                    override fun prepare(sql: String): SQLiteStatement {
                        if (sql == "ROLLBACK") {
                            rollbackAttempted = true
                            throw Error("synthetic rollback cleanup failure")
                        }
                        return delegate.prepare(sql)
                    }
                    override fun close() {
                        closeAttempted = true
                        delegate.close()
                        throw Error("synthetic close cleanup failure")
                    }
                }
            }
        }
        assertSame(fatal, assertFailsWith<OpenError> {
            AgentDatabase.openForInitialization(file, driver) {
                if (it == SqliteInitStage.BEFORE_SCHEMA_COMMIT) throw fatal
            }
        })
        assertTrue(rollbackAttempted); assertTrue(closeAttempted)
        AgentDatabase.open(file, BundledSQLiteDriver()).close()
    }

    @Test fun fatalLedgerLoadReleasesOwnedDatabaseDespiteCloseFailure() = temporary { file ->
        val fatal = OpenError()
        var closed = false
        val driver = object : SQLiteDriver by BundledSQLiteDriver() {
            override fun open(fileName: String): SQLiteConnection {
                val delegate = BundledSQLiteDriver().open(fileName)
                return object : SQLiteConnection by delegate {
                    override fun prepare(sql: String): SQLiteStatement {
                        if (sql.endsWith("FROM invocations ORDER BY rowid")) throw fatal
                        return delegate.prepare(sql)
                    }
                    override fun close() {
                        closed = true
                        delegate.close()
                        throw Error("synthetic cleanup failure")
                    }
                }
            }
        }
        assertSame(fatal, assertFailsWith<OpenError> { SqliteInvocationStore.open(file, driver) })
        assertTrue(closed)
        assertEquals("PE", Files.readString(file.resolveSibling("agent.db.writer.lock")))
        SqliteInvocationStore.open(file, BundledSQLiteDriver()).use { assertTrue(it.records().isEmpty()) }
    }

    @Test fun fatalBorrowedLedgerLoadUnbindsWithoutClosingOwner() = temporary { file ->
        val fatal = OpenError()
        var fail = true
        val driver = object : SQLiteDriver by BundledSQLiteDriver() {
            override fun open(fileName: String): SQLiteConnection {
                val delegate = BundledSQLiteDriver().open(fileName)
                return object : SQLiteConnection by delegate {
                    override fun prepare(sql: String): SQLiteStatement {
                        if (fail && sql.endsWith("FROM invocations ORDER BY rowid")) throw fatal
                        return delegate.prepare(sql)
                    }
                }
            }
        }
        AgentDatabase.open(file, driver).use { db ->
            assertSame(fatal, assertFailsWith<OpenError> { SqliteInvocationStore.open(db) })
            fail = false
            SqliteInvocationStore.open(db).use { assertTrue(it.records().isEmpty()) }
            db.ready()
        }
    }
}
