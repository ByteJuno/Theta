package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

object SqliteCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Paths.get(args[0])
        if (args[1] == "lock") {
            try { TestInvocationStore.open(directory).close(); println("OPENED") }
            catch (_: StoreException) { println("BLOCKED") }
            return
        }
        if (args[1].startsWith("init:")) {
            val stage = SqliteInitStage.valueOf(args[1].substringAfter(':'))
            AgentDatabase.openForInitialization(directory.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()) {
                if (it == stage) { println("KILL_READY:$stage"); System.out.flush(); CountDownLatch(1).await() }
            }.close()
            error("Initialization cutpoint not reached")
        }
        val stage = SqliteWriteStage.valueOf(args[1])
        TestInvocationStore.open(directory) { point ->
            if (point == stage) {
                println("KILL_READY:$point"); System.out.flush()
                CountDownLatch(1).await()
            }
        }.use { store ->
            val old = store.records().single()
            val blob = BlobWrite(ByteArray(500_000) { (it % 251).toByte() }, "application/json")
            val next = old.copy(revision = old.revision + 1,
                result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
            store.saveWithBlobs(next, old.revision, listOf(blob))
        }
        error("Kill point not reached")
    }
}

class SqliteProcessTest {
    private val classpath get() = (listOf(SqliteCrashChild::class.java, SqliteInvocationStore::class.java,
        SQLiteDriver::class.java, BundledSQLiteDriver::class.java, kotlin.Unit::class.java,
        kotlinx.serialization.json.Json::class.java, kotlinx.serialization.SerializationException::class.java,
        kotlinx.coroutines.Job::class.java)
        .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath } +
        generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).absolutePath }.toList())
        .distinct().joinToString(File.pathSeparator)
    private fun child(directory: java.nio.file.Path, point: String) = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "").path,
        "-cp", classpath, SqliteCrashChild::class.java.name, directory.toString(), point).redirectErrorStream(true).start()
    private fun lockProbe(directory: java.nio.file.Path): String {
        val p = child(directory, "lock")
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        val output = p.inputStream.bufferedReader().readText().trim()
        assertEquals(0, p.exitValue(), output)
        assertTrue(output.endsWith("OPENED") || output.endsWith("BLOCKED"), output)
        return output.substringAfterLast('\n')
    }
    @Test fun secondInstanceAndProcessFailWithoutDroppingOriginalLock() = Fixture().use { f ->
        assertEquals("BLOCKED", lockProbe(f.directory))
        assertFailsWith<StoreException> { TestInvocationStore.open(f.directory) }
        assertEquals("BLOCKED", lockProbe(f.directory))
        f.store.close()
        assertEquals("OPENED", lockProbe(f.directory))
        println("SQLITE_WRITER_PROOF duplicate-instance=blocked subprocess=blocked reopen=opened")
    }
    @Test fun killedFirstInitializationRetriesEveryStage() {
        for (stage in SqliteInitStage.entries) {
            val directory = Files.createTempDirectory("sqlite-init-kill-")
            val process = child(directory, "init:$stage")
            val pool = Executors.newSingleThreadExecutor()
            try {
                pool.submit<Unit> {
                    val reader = process.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: error("Child exited before initialization cutpoint")
                        if (line == "KILL_READY:$stage") break
                    }
                }.get(30, TimeUnit.SECONDS)
                process.destroyForcibly(); assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                assertNotEquals(0, process.exitValue())
                SqliteInvocationStore.open(directory.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use {
                    assertTrue(it.records().isEmpty())
                }
                assertEquals("PE", Files.readString(directory.resolve("agent.db.writer.lock")))
                println("SQLITE_INIT_KILL_PROOF point=$stage exit=${process.exitValue()} retry=opened")
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(30, TimeUnit.SECONDS) }
                pool.shutdownNow(); directory.toFile().deleteRecursively()
            }
        }
    }
    @Test fun killedTransactionsExposeAllOrNoneOfRecordAndContent() {
        for (stage in SqliteWriteStage.entries) Fixture().use { f ->
            val args = "{\"v\":\"" + "\\n\\\"\\\\".repeat(550_000) + "\"}"
            val call = f.claim(f.ready(f.input(args = args)))
            val old = assertIs<Mutation.Applied>(f.ledger.complete(call.record.callId, call.ticket!!.attemptId, Status.SUCCEEDED, "done")).record
            f.store.close()
            val process = child(f.directory, stage.name)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val reached = pool.submit<String> {
                    val reader = process.inputStream.bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: error("Child exited before cutpoint: $lines")
                        lines += line
                        if (line == "KILL_READY:$stage") return@submit line
                    }
                    @Suppress("UNREACHABLE_CODE") ""
                }.get(30, TimeUnit.SECONDS)
                process.destroyForcibly()
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                assertNotEquals(0, process.exitValue())
                TestInvocationStore.open(f.directory).use { store ->
                    val record = store.records().single()
                    val committed = stage == SqliteWriteStage.AFTER_COMMIT
                    assertEquals(old.revision + if (committed) 1 else 0, record.revision)
                    val count = sql(f.directory) { it.longValue("SELECT count(*) FROM contents") }
                    val chunks = sql(f.directory) { it.longValue("SELECT count(*) FROM content_chunks") }
                    assertEquals(if (committed) 1L else 0L, count)
                    assertEquals(if (committed) 62L else 0L, chunks)
                    assertEquals(if (committed) 1L else 0L, sql(f.directory) { it.longValue("SELECT count(*) FROM content_manifests") })
                    if (committed) {
                        val ref = record.result!!.output!!.raw
                        assertEquals(500_000L, ref.sizeBytes)
                        assertContentEquals(ByteArray(8192) { (it % 251).toByte() }, store.readBlob(ref, 0, 8192))
                    } else assertEquals(old, record)
                    assertEquals(args, record.input.argsJson)
                    assertEquals(args, record.executionArgs)
                    val recordChunks = sql(f.directory) { it.longValue("SELECT count(*) FROM record_chunks") }
                    val recordSize = sql(f.directory) { it.longValue("SELECT size FROM invocations") }
                    assertEquals((recordSize + RECORD_CHUNK_SIZE - 1) / RECORD_CHUNK_SIZE, recordChunks)
                    assertTrue(recordChunks > 700)
                    assertEquals(0L, sql(f.directory) { it.longValue("SELECT count(*) FROM record_chunks c WHERE NOT EXISTS(SELECT 1 FROM invocations i WHERE c.call_id=i.call_id)") })
                    println("SQLITE_KILL_PROOF point=$stage ready=$reached exit=${process.exitValue()} committed=$committed records=1 contents=$count chunks=$chunks recordChunks=$recordChunks recordBytes=$recordSize")
                }
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(30, TimeUnit.SECONDS) }
                pool.shutdownNow()
            }
        }
    }
}
