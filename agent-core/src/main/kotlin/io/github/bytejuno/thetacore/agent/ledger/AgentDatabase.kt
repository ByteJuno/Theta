package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardOpenOption.READ

internal enum class SqliteInitStage { AFTER_PROVENANCE, AFTER_OPEN, BEFORE_SCHEMA_COMMIT, AFTER_SCHEMA_COMMIT, AFTER_CHECKPOINT, AFTER_ESTABLISHED }

/** One database for agent persistence. Schema versions belong to the database, not a feature.
 * History tables can be added by a later version; there is deliberately no JSON import path.
 * SQLite's transaction lock is short-lived; the OS lock additionally excludes a second host
 * writer for this entire open lifetime, across both instances and processes.
 */
class AgentDatabase private constructor(
    internal val connection: SQLiteConnection,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val identity: Path,
) : Closeable {
    @Volatile private var closed = false
    @Volatile private var uncertain = false
    private val sessions = mutableSetOf<String>()
    private var ledgerBound = false
    internal fun ready() { if (closed || uncertain) throw StoreException("Database closed or uncertain; reopen and cold recover", uncertain) }
    @Synchronized internal fun bindLedger() { ready(); check(!ledgerBound); ledgerBound = true }
    @Synchronized internal fun releaseLedger() { ledgerBound = false }
    @Synchronized internal fun bindSession(id: String) { ready(); check(sessions.add(id)) { "Session already open" } }
    @Synchronized internal fun releaseSession(id: String) { sessions.remove(id) }
    @Synchronized internal fun <T> access(block: () -> T): T { ready(); return block() }
    @Synchronized internal fun poison() { uncertain = true }
    @Synchronized internal fun <T> transaction(block: () -> T): T {
        ready()
        var begun = false
        try {
            connection.execute("BEGIN IMMEDIATE"); begun = true
            connection.verifyWriteConfiguration()
            val result = block()
            connection.execute("COMMIT"); begun = false
            return result
        } catch (error: Throwable) {
            if (begun) runCatching { connection.execute("ROLLBACK") }
            uncertain = true
            throw error
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { connection.close() } finally {
            try { lock.release() } finally {
                try { channel.close() } finally { synchronized(writers) { writers.remove(identity) } }
            }
        }
    }

    companion object {
        const val FILE_NAME = "agent.db"
        const val SCHEMA_VERSION = 2
        private const val APPLICATION_ID = 0x54485441
        // On POSIX, closing ANY descriptor of a locked file can drop this process's lock.
        // Reject duplicate instances before even opening a second descriptor.
        private val writers = mutableSetOf<Path>()

        /** Called inside the platform helper's onCreate transaction, or our JVM transaction.
         * The platform helper owns its user_version update in that same transaction.
         */
        fun createSchema(connection: SQLiteConnection) {
            connection.verifyWriteConfiguration()
            require(connection.longValue("PRAGMA application_id") == 0L &&
                connection.longValue("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata'") == 0L) {
                "Unrecognized agent database"
            }
            connection.execute("CREATE TABLE invocations (call_id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, size INTEGER NOT NULL, hash TEXT NOT NULL)")
            connection.execute("CREATE TABLE record_chunks (call_id TEXT NOT NULL REFERENCES invocations(call_id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(call_id, ordinal)) WITHOUT ROWID")
            connection.execute("CREATE TABLE contents (hash TEXT PRIMARY KEY NOT NULL, size INTEGER NOT NULL)")
            connection.execute("CREATE TABLE content_manifests (hash TEXT NOT NULL REFERENCES contents(hash) ON DELETE CASCADE, ordinal INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(hash, ordinal)) WITHOUT ROWID")
            connection.execute("CREATE TABLE content_chunks (hash TEXT NOT NULL REFERENCES contents(hash) ON DELETE CASCADE, ordinal INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(hash, ordinal)) WITHOUT ROWID")
            connection.execute("CREATE TABLE invocation_contents (call_id TEXT NOT NULL REFERENCES invocations(call_id) ON DELETE CASCADE, hash TEXT NOT NULL, PRIMARY KEY(call_id, hash)) WITHOUT ROWID")
            connection.execute("CREATE INDEX content_owners ON invocation_contents(hash)")
            createHistorySchema(connection)
            connection.execute("PRAGMA application_id=$APPLICATION_ID")
            connection.execute("PRAGMA user_version=$SCHEMA_VERSION")
        }

        private fun createHistorySchema(connection: SQLiteConnection) {
            connection.execute("CREATE TABLE sessions (session_id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL CHECK(revision>=0)) WITHOUT ROWID")
            connection.execute("CREATE TABLE history_values (session_id TEXT NOT NULL REFERENCES sessions(session_id), name TEXT NOT NULL, PRIMARY KEY(session_id,name)) WITHOUT ROWID")
            connection.execute("CREATE TABLE history_chunks (session_id TEXT NOT NULL, name TEXT NOT NULL, ordinal INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(session_id,name,ordinal), FOREIGN KEY(session_id,name) REFERENCES history_values(session_id,name) ON DELETE CASCADE) WITHOUT ROWID")
        }
        /** Only the recognized SQLite v1 schema is upgraded; legacy files are never imported. */
        fun upgradeSchema(connection: SQLiteConnection, oldVersion: Int, newVersion: Int) {
            connection.verifyWriteConfiguration()
            require(oldVersion == 1 && newVersion == SCHEMA_VERSION &&
                connection.longValue("PRAGMA application_id") == APPLICATION_ID.toLong())
            createHistorySchema(connection)
            connection.execute("PRAGMA user_version=$SCHEMA_VERSION")
        }

        fun open(file: Path, driver: SQLiteDriver): AgentDatabase = openForInitialization(file, driver)

        internal fun openForInitialization(file: Path, driver: SQLiteDriver, fault: (SqliteInitStage) -> Unit = {}): AgentDatabase {
            // Canonical parent means aliases cannot acquire different writer locks.
            val canonical = file.toFile().canonicalFile.toPath()
            require(Files.isDirectory(canonical.parent)) { "Missing database parent" }
            synchronized(writers) {
                if (!writers.add(canonical)) throw StoreException("Agent database already has a writer")
            }
            val channel = try {
                FileChannel.open(canonical.resolveSibling("${canonical.fileName}.writer.lock"), CREATE, READ, WRITE)
            } catch (error: Exception) {
                synchronized(writers) { writers.remove(canonical) }
                throw StoreException("Cannot acquire agent database writer lock", cause = error)
            }
            var lock: FileLock? = null
            var connection: SQLiteConnection? = null
            try {
                lock = channel.tryLock() ?: throw StoreException("Agent database already has a writer")
                val provenance = DatabaseProvenance(canonical, channel)
                provenance.beforeOpen() // Must precede Android helper onConfigure/onCreate.
                fault(SqliteInitStage.AFTER_PROVENANCE)
                connection = driver.open(canonical.toString())
                fault(SqliteInitStage.AFTER_OPEN)
                val version = connection.longValue("PRAGMA user_version")
                require(version in 0..SCHEMA_VERSION.toLong()) { "Unsupported agent database schema $version" }
                if (version == 0L) {
                    require(connection.longValue("PRAGMA application_id") == 0L &&
                        connection.longValue("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") == 0L) {
                        "Unrecognized agent database"
                    }
                } else require(connection.longValue("PRAGMA application_id") == APPLICATION_ID.toLong()) {
                    "Invalid agent database identity"
                }
                require(connection.textValue("PRAGMA journal_mode=WAL").equals("wal", true)) { "WAL unavailable" }
                // Pooled drivers must configure every connection before open returns (Android's
                // onConfigure uses execPerConnectionSQL). A standalone PRAGMA may hit a reader.
                if (!driver.hasConnectionPool) {
                    connection.execute("PRAGMA synchronous=FULL")
                    connection.execute("PRAGMA foreign_keys=ON")
                }
                connection.execute("BEGIN IMMEDIATE")
                try {
                    connection.verifyWriteConfiguration()
                    if (version == 0L) createSchema(connection)
                    else if (version == 1L) upgradeSchema(connection, 1, SCHEMA_VERSION)
                    fault(SqliteInitStage.BEFORE_SCHEMA_COMMIT)
                    connection.execute("COMMIT")
                    fault(SqliteInitStage.AFTER_SCHEMA_COMMIT)
                } catch (error: Exception) {
                    runCatching { connection.execute("ROLLBACK") }
                    throw error
                }
                connection.prepare("PRAGMA wal_checkpoint(FULL)").use {
                    require(it.step() && it.getLong(0) == 0L && it.getLong(1) == it.getLong(2)) { "Schema checkpoint incomplete" }
                }
                fault(SqliteInitStage.AFTER_CHECKPOINT)
                provenance.establish()
                fault(SqliteInitStage.AFTER_ESTABLISHED)
                return AgentDatabase(connection, channel, lock, canonical)
            } catch (error: Exception) {
                runCatching { connection?.close() }
                runCatching { lock?.release() }
                try { channel.close() } finally { synchronized(writers) { writers.remove(canonical) } }
                throw StoreException("Cannot open agent database: ${error.message}", cause = error)
            }
        }
    }
}

/** Read-only checks while BEGIN IMMEDIATE pins this thread to the actual writer.
 * Never SET synchronous inside a transaction: SQLite rejects it.
 */
internal fun SQLiteConnection.verifyWriteConfiguration() {
    require(inTransaction()) { "Write connection is not pinned" }
    require(textValue("PRAGMA journal_mode").equals("wal", true)) { "Writer WAL unavailable" }
    require(longValue("PRAGMA synchronous") == 2L) { "Writer FULL synchronization unavailable" }
    require(longValue("PRAGMA foreign_keys") == 1L) { "Writer foreign keys unavailable" }
}

internal fun SQLiteConnection.execute(sql: String) { prepare(sql).use { it.step() } }
internal fun SQLiteConnection.longValue(sql: String): Long = prepare(sql).use { check(it.step()); it.getLong(0) }
internal fun SQLiteConnection.textValue(sql: String): String = prepare(sql).use { check(it.step()); it.getText(0) }
