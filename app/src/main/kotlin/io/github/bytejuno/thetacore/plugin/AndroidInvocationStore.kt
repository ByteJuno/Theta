package io.github.bytejuno.thetacore.plugin

import android.content.Context
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.SupportSQLiteConnection
import androidx.sqlite.driver.SupportSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.AgentDatabase
import io.github.bytejuno.thetacore.agent.ledger.InvocationStore
import io.github.bytejuno.thetacore.agent.ledger.SqliteInvocationStore
import io.github.bytejuno.thetacore.agent.ledger.SqliteWriteStage
import io.github.bytejuno.thetacore.agent.ledger.StoreException
import java.io.File

/** System SQLite through AndroidX's mature support driver (not Room).
 * Unlike AndroidSQLiteDriver's default corruption handler, this callback NEVER deletes a
 * damaged ledger. Both framework auto-recovery and schema downgrade fail closed.
 */
internal class AndroidInvocationStore private constructor(
    private val database: AgentDatabase, private val delegate: InvocationStore,
) : InvocationStore by delegate {
    fun createHistory(sessionId: String) = io.github.bytejuno.thetacore.agent.run.SqliteRunHistory.create(database, sessionId)
    fun openHistory(sessionId: String) = io.github.bytejuno.thetacore.agent.run.SqliteRunHistory.open(database, sessionId)
    override fun close() { try { delegate.close() } finally { database.close() } }
    companion object {
        fun open(context: Context): AndroidInvocationStore {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(AgentDatabase.FILE_NAME)
                    .noBackupDirectory(true)
                    .allowDataLossOnRecovery(false)
                    .callback(object : SupportSQLiteOpenHelper.Callback(AgentDatabase.SCHEMA_VERSION) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            configureConnections(db)
                        }
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            AgentDatabase.createSchema(SupportSQLiteConnection(db))
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            AgentDatabase.upgradeSchema(SupportSQLiteConnection(db), oldVersion, newVersion)
                        }
                        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            throw StoreException("Unsupported agent database schema $oldVersion")
                        }
                        override fun onCorruption(db: SupportSQLiteDatabase) {
                            throw StoreException("Agent database is corrupt; evidence retained")
                        }
                    }).build())
            try {
                helper.setWriteAheadLoggingEnabled(true)
                return open(context.noBackupFilesDir, SupportSQLiteDriver(helper))
            } catch (error: Throwable) {
                runCatching { helper.close() }
                throw error
            }
        }

        /** WAL is enabled before opening the helper; onConfigure precedes its schema
         * transaction. API 30+ persists this SQL in the framework pool configuration,
         * applying it after default synchronization settings to existing/future connections.
         * No fallback to execSQL: that may configure only a reader. minSdk is 33.
         */
        internal fun configureConnections(db: SupportSQLiteDatabase) {
            if (!db.isExecPerConnectionSQLSupported) {
                throw StoreException("Per-connection SQLite configuration unavailable")
            }
            check(!db.inTransaction()) { "Configure SQLite before starting transactions" }
            db.setForeignKeyConstraintsEnabled(true)
            db.execPerConnectionSQL("PRAGMA synchronous=FULL", null)
        }

        /** Bundled desktop driver is injected only by JVM tests. */
        internal fun open(root: File, driver: SQLiteDriver,
            fault: (SqliteWriteStage) -> Unit = {}): AndroidInvocationStore {
            require(root.isDirectory) { "No private no-backup directory" }
            val database = AgentDatabase.open(File(root, AgentDatabase.FILE_NAME).toPath(), driver)
            try { return AndroidInvocationStore(database, SqliteInvocationStore.open(database, fault)) }
            catch (error: Throwable) { database.close(); throw error }
        }
    }
}
