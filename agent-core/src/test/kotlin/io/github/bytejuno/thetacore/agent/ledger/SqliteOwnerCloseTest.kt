package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.run.SqliteRunHistory
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SqliteOwnerCloseTest {
    // Inspect ownership only in this test; production never exposes its private database.
    private fun owner(store: SqliteInvocationStore) = SqliteInvocationStore::class.java
        .getDeclaredField("database").apply { isAccessible = true }.get(store) as AgentDatabase

    @Test fun owningLedgerRejectsCloseWithoutLosingAdmissionOrBinding() {
        val root = Files.createTempDirectory("sqlite-owner-close")
        try {
            val file = root.resolve(AgentDatabase.FILE_NAME)
            SqliteInvocationStore.open(file, BundledSQLiteDriver()).use { store ->
                val db = owner(store)
                SqliteRunHistory.create(db, "session").use { history ->
                    history.beginRun()
                    try {
                        assertFailsWith<IllegalStateException> { store.close() }
                        store.checkAdmission(); assertTrue(store.records().isEmpty())
                        assertFails { SqliteInvocationStore.open(db) }
                        assertFails { AgentDatabase.open(file, BundledSQLiteDriver()) }
                    } finally { history.endRun() }
                    history.createSummarySpool().use {
                        assertFailsWith<IllegalStateException> { store.close() }
                        store.checkAdmission(); assertEquals(0L, history.revision())
                    }
                }
                store.close(); store.close()
            }
            SqliteInvocationStore.open(file, BundledSQLiteDriver()).close()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun borrowedLedgerCloseDoesNotCloseOrUnpinHistoryOwner() {
        val root = Files.createTempDirectory("sqlite-borrowed-close")
        try {
            AgentDatabase.open(root.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "s").use { history ->
                    history.beginRun()
                    try {
                        val store = SqliteInvocationStore.open(db)
                        store.close(); store.close()
                        assertFails { store.checkAdmission() }
                        assertFails { db.close() }
                        SqliteInvocationStore.open(db).use { it.checkAdmission() }
                        assertEquals(0L, history.revision())
                    } finally { history.endRun() }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun admittedOwnerCloseExcludesConcurrentPinAcquisition() {
        val root = Files.createTempDirectory("sqlite-close-race")
        val closing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pinStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val driver = object : SQLiteDriver by BundledSQLiteDriver() {
                override fun open(fileName: String): SQLiteConnection {
                    val delegate = BundledSQLiteDriver().open(fileName)
                    return object : SQLiteConnection by delegate {
                        override fun close() {
                            closing.countDown()
                            try { check(finish.await(5, TimeUnit.SECONDS)) }
                            finally { delegate.close() }
                        }
                    }
                }
            }
            val store = SqliteInvocationStore.open(root.resolve(AgentDatabase.FILE_NAME), driver)
            val db = owner(store)
            val close = pool.submit { store.close() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            val pin = pool.submit<Boolean> {
                pinStarted.countDown()
                assertFailsWith<StoreException> { db.retainOwner() }
                true
            }
            assertTrue(pinStarted.await(5, TimeUnit.SECONDS))
            assertFalse(pin.isDone)
            finish.countDown()
            close.get(5, TimeUnit.SECONDS)
            assertTrue(pin.get(5, TimeUnit.SECONDS))
            store.close()
        } finally { finish.countDown(); pool.shutdownNow(); root.toFile().deleteRecursively() }
    }
}
