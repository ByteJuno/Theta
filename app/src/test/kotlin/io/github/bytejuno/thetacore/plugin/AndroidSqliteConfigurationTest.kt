package io.github.bytejuno.thetacore.plugin

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.bytejuno.thetacore.agent.ledger.StoreException
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Exercises the exact production onConfigure route with a contract double, not a
 * framework database. Actual Android pool/flash behavior still requires device tests.
 */
class AndroidSqliteConfigurationTest {
    private class Pool(val supported: Boolean = true, val transaction: Boolean = false) {
        val connections = mutableListOf(1, 1) // writer and reader initially NORMAL
        var futureSync = 1
        var foreignKeys = false
        val operations = mutableListOf<String>()
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            when (method.name) {
                "isExecPerConnectionSQLSupported" -> supported
                "inTransaction" -> transaction
                "setForeignKeyConstraintsEnabled" -> { foreignKeys = args!![0] as Boolean; operations += "foreignKeys"; null }
                "execPerConnectionSQL" -> {
                    check(!transaction && supported)
                    check(args!![0] == "PRAGMA synchronous=FULL" && args[1] == null)
                    operations += "allConnections"
                    connections.indices.forEach { connections[it] = 2 }; futureSync = 2
                    null
                }
                else -> error("Unexpected/unforwarded API: ${method.name}")
            }
        } as SupportSQLiteDatabase
    }

    @Test fun productionConfigurationSetsExistingAndFutureConnections() {
        val pool = Pool()
        AndroidInvocationStore.configureConnections(pool.db)
        pool.connections += pool.futureSync
        assertEquals(listOf(2, 2, 2), pool.connections)
        assertTrue(pool.foreignKeys)
        assertEquals(listOf("foreignKeys", "allConnections"), pool.operations)
    }

    @Test fun unsupportedPoolFailsClosedWithoutSingleConnectionFallback() {
        val pool = Pool(supported = false)
        assertThrows(StoreException::class.java) { AndroidInvocationStore.configureConnections(pool.db) }
        assertTrue(pool.operations.isEmpty())
        assertEquals(listOf(1, 1), pool.connections)
    }

    @Test fun configurationIsRejectedInsideTransaction() {
        val pool = Pool(transaction = true)
        assertThrows(IllegalStateException::class.java) { AndroidInvocationStore.configureConnections(pool.db) }
        assertTrue(pool.operations.isEmpty())
    }
}
