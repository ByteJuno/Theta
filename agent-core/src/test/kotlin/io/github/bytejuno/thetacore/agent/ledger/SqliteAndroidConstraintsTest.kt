package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

/** JVM instrumentation, NOT Android/device verification. Checks the whole returned row,
 * including UTF-16 strings, column slots and row overhead, before exposing it to callers.
 * Delegation forwards every other method (including inTransaction and hasConnectionPool).
 */
internal class WindowBoundDriver(private val delegate: SQLiteDriver = BundledSQLiteDriver(), private val windowBytes: Long = 2L * 1024 * 1024) : SQLiteDriver by delegate {
    var largestRow = 0L
    var rows = 0
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        val result = statement.step()
                        if (result) {
                            var size = 32L + statement.getColumnCount() * 16L
                            for (i in 0 until statement.getColumnCount()) size += when (statement.getColumnType(i)) {
                                3 -> statement.getText(i).length * 2L + 2L
                                4 -> statement.getBlob(i).size.toLong()
                                else -> 8L
                            }
                            largestRow = maxOf(largestRow, size); rows++
                            check(size <= windowBytes) { "CursorWindow-like oversized read row: $size bytes SQL=$sql" }
                        }
                        return result
                    }
                }
            }
        }
    }
}

class SqliteAndroidConstraintsTest {
    @Test fun escapedMultiMegabyteRecordReopensThroughBoundedRows() = Fixture().use { f ->
        val args = "{\"text\":\"" + "\\n\\\"\\\\".repeat(550_000) + "\"}"
        assertTrue(args.toByteArray().size > 3 * 1024 * 1024)
        val record = f.record(f.input(args = args))
        f.store.close()
        val driver = WindowBoundDriver()
        SqliteInvocationStore.open(f.directory.resolve(AgentDatabase.FILE_NAME), driver).use {
            assertEquals(record, it.records().single())
            val next = record.copy(revision = record.revision + 1)
            it.save(next, record.revision)
        }
        SqliteInvocationStore.open(f.directory.resolve(AgentDatabase.FILE_NAME), driver).use {
            assertEquals(record.copy(revision = record.revision + 1), it.records().single())
        }
        TestInvocationStore.open(f.directory).use {
            assertEquals(record.copy(revision = record.revision + 1), it.records().single())
        }
        assertTrue(driver.rows > 0)
        assertTrue(driver.largestRow < 16 * 1024)
        println("SQLITE_WINDOW_PROOF argsBytes=${args.toByteArray().size} maxRow=${driver.largestRow} rows=${driver.rows}")
    }

    @Test fun missingExtraReorderedTamperedAndOversizedChunksFailClosed() {
        val damages = listOf(
            "DELETE FROM record_chunks WHERE ordinal=1",
            "UPDATE record_chunks SET ordinal=ordinal+10000",
            "UPDATE record_chunks SET bytes=zeroblob(length(bytes)) WHERE ordinal=0",
            "UPDATE record_chunks SET bytes=zeroblob(3000000) WHERE ordinal=0",
            "INSERT INTO record_chunks SELECT call_id,99999,x'01' FROM invocations",
            "UPDATE invocations SET size=size+1",
            "UPDATE invocations SET hash='${"0".repeat(64)}'",
            "INSERT INTO record_chunks VALUES('orphan',0,x'01')",
        )
        for (damage in damages) Fixture().use { f ->
            f.record(f.input(args = "{\"v\":\"${"x".repeat(30_000)}\"}"))
            f.store.close()
            sql(f.directory) { it.execute(damage) }
            val driver = WindowBoundDriver()
            assertFailsWith<StoreException>(damage) {
                SqliteInvocationStore.open(f.directory.resolve(AgentDatabase.FILE_NAME), driver)
            }
            assertTrue(driver.largestRow < 2 * 1024 * 1024, damage)
        }
    }

    @Test fun shorterRevisionDeletesOldChunksAtomically() = Fixture().use { f ->
        val record = f.record(f.input(args = "{\"v\":\"${"x".repeat(50_000)}\"}"))
        val smaller = record.copy(revision = record.revision + 1, input = record.input.copy(argsJson = "{}"))
        val before = sql(f.directory) { it.longValue("SELECT count(*) FROM record_chunks") }
        f.store.save(smaller, record.revision)
        assertTrue(sql(f.directory) { it.longValue("SELECT count(*) FROM record_chunks") } < before)
        f.store.close()
        TestInvocationStore.open(f.directory).use { assertEquals(smaller, it.records().single()) }
    }

    @Test fun boundedWrapperEnforcesRowsAndForwardsTransactionMethods() {
        val root = Files.createTempDirectory("window-wrapper-")
        try {
            val driver = WindowBoundDriver()
            assertFalse(driver.hasConnectionPool)
            driver.open(root.resolve("test.db").toString()).use { db ->
                db.execute("BEGIN IMMEDIATE"); assertTrue(db.inTransaction())
                db.execute("ROLLBACK"); assertFalse(db.inTransaction())
                db.prepare("SELECT zeroblob(2100000), 'column-overhead'").use {
                    assertFailsWith<IllegalStateException> { it.step() }
                }
                db.prepare("SELECT ?, ?").use {
                    it.bindInt(1, 7); it.bindBoolean(2, true); assertTrue(it.step())
                    assertEquals(7, it.getInt(0)); assertTrue(it.getBoolean(1))
                    it.reset(); it.clearBindings(); assertTrue(it.step()); assertTrue(it.isNull(0))
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun readerFullCannotHideNormalWriter() {
        val root = Files.createTempDirectory("sqlite-pool-")
        val file = root.resolve(AgentDatabase.FILE_NAME)
        try {
            SqliteInvocationStore.open(file, BundledSQLiteDriver()).close()
            val driver = object : SQLiteDriver {
                override val hasConnectionPool = true
                override fun open(fileName: String): SQLiteConnection {
                    val writer = BundledSQLiteDriver().open(fileName)
                    val reader = BundledSQLiteDriver().open(fileName)
                    writer.execute("PRAGMA synchronous=NORMAL")
                    reader.execute("PRAGMA synchronous=FULL")
                    var transaction = false
                    return object : SQLiteConnection {
                        override fun inTransaction() = transaction
                        override fun prepare(sql: String): SQLiteStatement {
                            val begin = sql.startsWith("BEGIN")
                            val end = sql == "ROLLBACK" || sql == "COMMIT"
                            val selected = if (transaction || begin) writer else reader
                            val statement = selected.prepare(sql)
                            return object : SQLiteStatement by statement {
                                override fun step(): Boolean {
                                    val result = statement.step()
                                    if (begin) transaction = true
                                    if (end) transaction = false
                                    return result
                                }
                            }
                        }
                        override fun close() { try { reader.close() } finally { writer.close() } }
                    }
                }
            }
            assertFailsWith<StoreException> { SqliteInvocationStore.open(file, driver).use { } }
            // Failed verification releases both the SQL transaction and the lifetime lock.
            SqliteInvocationStore.open(file, BundledSQLiteDriver()).close()
        } finally { root.toFile().deleteRecursively() }
    }
}
