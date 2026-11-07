package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

/** Production helpers, injected actual deletion failures. SOURCE ONLY / NOT RUN. */
class PluginRevisionThreeTest {
    private fun root(action: (File) -> Unit) {
        val root = Files.createTempDirectory("theta-r3").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun bundle(data: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry(if (data) "theta-plugin.json" else "assets/theta-plugin.json"))
            val entry = if (data) "" else ",\"entry\":\"test.Entry\""
            it.write("""{"schema":1,"type":"${if (data) "data" else "dex"}","id":"test.r3","name":"R3","version":"1","versionCode":1,"apiLevel":1$entry}""".toByteArray())
            it.closeEntry()
        }
        return out.toByteArray()
    }
    @Test fun reclaimedCacheRootRecreatedAfterFirstImport() = root { root -> runBlocking {
        val pool = PluginImportSourcePool()
        val directory = File(root, "sources")
        repeat(2) {
            val copy = pool.start(directory, 32, { byteArrayOf(1).inputStream() })
            withTimeout(2000) { copy.await() }
            copy.release()
            withTimeout(2000) { copy.finished.await() }
            Files.delete(directory.toPath())
        }
    } }

    @Test fun dataDiscardRetainsPendingUntilRealDeleteSucceedsAndBlocksRepeatedStages() = root { root ->
        var failDelete = true
        val cleanup = PluginStageCleanup(2)
        val store = PluginDataStore(root, 1, cleanup = cleanup, deleteStage = { dir, name ->
            if (failDelete) throw IOException("delete denied")
            deletePluginTree(dir, name)
        })
        val staged = store.stageData(bundle().inputStream())
        repeat(30) {
            assertThrows(IOException::class.java) { store.discardData(staged) }
            assertThrows(PluginPackageException::class.java) { store.stageData(bundle().inputStream()) }
            assertEquals(1, File(root, "plugin-data-staging").listFiles()!!.size)
        }
        failDelete = false
        store.discardData(staged) // same pending identity must remain retriable
        assertFalse(cleanup.hasPending)
        assertThrows(PluginPackageException::class.java) { store.discardData(staged) }
        store.discardData(store.stageData(bundle().inputStream()))
    }

    @Test fun localCloseFailureRetainsBothKindsThroughProductionStoreRegistry() = root { root ->
        for (data in listOf(true, false)) {
            val dir = File(root, "$data").apply { mkdirs() }
            val cleanup = PluginStageCleanup(2)
            var denied = true
            val deletion = IOException("real deletion denied")
            val dataStore = PluginDataStore(dir, 1, cleanup = cleanup, deleteStage = { parent, name ->
                if (denied) throw deletion
                deletePluginTree(parent, name)
            })
            val code = PluginStore(dir, File(dir, "cache"),
                PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) }, cleanup,
                deleteStage = { if (denied) throw deletion; Files.deleteIfExists(it.toPath()); Unit })
            val original = IOException("local close")
            val failure = assertThrows(IOException::class.java) {
                stagePluginInput<Any>(
                    { object : ByteArrayInputStream(bundle(data)) { override fun close() { throw original } } },
                    { if (data) dataStore.stageData(it) else code.stage(it) },
                    { if (it is StagedPluginData) dataStore.discardData(it) else code.discard(it as StagedPlugin) })
            }
            assertSame(original, failure)
            assertTrue(failure.suppressed.contains(deletion))
            repeat(30) {
                assertThrows(IOException::class.java) { cleanup.retry() }
                assertThrows(PluginPackageException::class.java) {
                    if (data) dataStore.stageData(bundle().inputStream()) else code.stage(bundle(false).inputStream())
                }
            }
            denied = false
            cleanup.retry()
            assertFalse(cleanup.hasPending)
            val stageRoot = File(dir, if (data) "plugin-data-staging" else "cache/plugin-staging")
            assertTrue(stageRoot.listFiles()!!.isEmpty())
        }
    }

    @Test fun rejectedInstallOwnsStageAndRetryNeverDeletesPublishedPackage() = root { root ->
        var denied = false
        val cleanup = PluginStageCleanup()
        val store = PluginDataStore(root, 1, cleanup = cleanup, deleteStage = { dir, name ->
            if (denied) throw IOException("delete denied")
            deletePluginTree(dir, name)
        })
        val stage = store.stageData(bundle().inputStream())
        denied = true
        val failure = assertThrows(PluginPackageException::class.java) {
            store.publishConfirmedData(stage, "wrong", true)
        }
        assertTrue(failure.suppressed.any { it is IOException })
        denied = false
        cleanup.retry()
        val next = store.stageData(bundle().inputStream())
        val published = store.publishConfirmedData(next, next.sha256, true)
        val bytes = published.zip.readBytes()
        cleanup.retry()
        assertArrayEquals(bytes, published.zip.readBytes())
    }

    @Test fun noStageCancelDoesNotWaitForActualProductionImportGate() = root { root -> runBlocking {
        val gate = Mutex(locked = true)
        val host = SupervisorJob()
        val copy = PluginImportSourcePool().start(File(root, "sources"), 32, { byteArrayOf(1).inputStream() })
        val session = PluginImportSession().also { it.source = copy }
        val waiting = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                stagePluginCopy<Any>(copy, { action ->
                    waiting.complete(Unit)
                    serializedPluginImport(gate, host + Dispatchers.Default, action)
                }, { error("gate held") }, {}, { false })
            } catch (e: CancellationException) {
                transferImportFailure(session, copy, e, { error("empty cancellation enqueued") }, {}, {})
                throw e
            }
        }
        try {
            withTimeout(2000) { waiting.await() }
            job.cancel()
            withTimeout(2000) { job.join(); copy.finished.await() }
            assertTrue(gate.isLocked) // same production gate still held, cancellation completed
        } finally { gate.unlock(); host.cancel() }
    } }

    @Test fun failedReturnTransfersOnlyOldStageAndKeepsCleanupBounded() = root { root -> runBlocking {
        val catalog = PluginDataCatalog(root, 1)
        val old = catalog.stage(bundle().inputStream())
        val session = PluginImportSession().also { it.data = old }
        val copy = PluginImportSourcePool().start(File(root, "sources"), 32, { byteArrayOf(1).inputStream() })
        session.source = copy
        val gate = Mutex(locked = true)
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var cleanupJob: Job? = null
        transferImportFailure(session, copy, CancellationException("old caller"),
            enqueue = { action -> cleanupJob = host.launch { serializedPluginImport(gate, coroutineContext, action) } },
            discard = { owned -> catalog.discard(checkNotNull(owned.data)) }, report = { throw it })
        assertNull(session.data)
        val replacement = catalog.stage(bundle().inputStream())
        session.data = replacement
        gate.unlock()
        withTimeout(2000) { cleanupJob!!.join() }
        assertSame(replacement, session.data)
        catalog.discard(replacement)
        copy.cancel(); copy.release()
        withTimeout(2000) { copy.finished.await() }
        host.cancel()
    } }

    @Test fun corruptReadRetainsManagementAndColdRestoreAllowsDisableUninstall() = root { root ->
        val catalog = PluginDataCatalog(root, 1)
        val stage = catalog.stage(bundle().inputStream())
        val selected = catalog.install(stage, stage.sha256, true)
        val pages = mutableListOf<ManagedPluginData>()
        val inventory = mutableListOf<ManagedDataRecord>()
        replaceDataInventory(inventory, pages, PluginDataRecord("test.r3", selected.state, selected), false)
        val page = pages.single()
        File(root, "plugins/test.r3/1/plugin.zip").writeText("corrupt")
        val failure = assertThrows(Exception::class.java) { catalog.content(selected, null) }
        quarantineDataPage(inventory, pages, page, failure.message ?: "bad ZIP")
        assertFalse(isManagedDataPageActive(pages, page))
        assertEquals("test.r3", inventory.single().id)
        assertTrue(inventory.single().state.enabled)
        assertFalse(inventory.single().canEnable)
        assertNull(inventory.single().record.selection)
        // Production cold restore retains durable identity without synthesizing a manifest.
        val cold = PluginDataCatalog(root, 1)
        val record = cold.restoreInventory {}.single()
        assertEquals("test.r3", record.id)
        assertNull(record.selection)
        cold.setEnabled(record.id, false)
        val disabled = cold.managementRecord(record.id)
        replaceDataInventory(inventory, pages, disabled, false)
        assertFalse(inventory.single().state.enabled)
        assertThrows(Exception::class.java) { cold.setEnabled(record.id, true) }
        cold.beginDelete(record.id); cold.finishDelete(record.id)
        assertFalse(File(root, "plugins/test.r3").exists())
    }

    @Test fun quarantineNeverAuthorizesDeletionOfMixedCodeTree() = root { root ->
        val catalog = PluginDataCatalog(root, 1)
        val stage = catalog.stage(bundle().inputStream())
        catalog.install(stage, stage.sha256, true)
        val apk = File(root, "plugins/test.r3/1/plugin.apk").apply { writeText("code") }
        assertNotNull(catalog.managementRecord("test.r3").error)
        catalog.beginDelete("test.r3")
        assertThrows(PluginPackageException::class.java) { catalog.finishDelete("test.r3") }
        assertEquals("code", apk.readText())
    }

    @Test fun uiCancellationIdentityAndProductionTwoSlotPoolStayBounded() = root { root -> runBlocking {
        val pool = PluginImportSourcePool() // production capacity = two across both kinds
        val allow = CountDownLatch(1)
        val requests = PluginImportRequests { it.closed = true; it.source?.cancel() }
        val copies = mutableListOf<PluginImportCopy>()
        try {
            val first = requests.begin()
            first.job = Job()
            repeat(2) { index ->
                val request = if (index == 0) first else requests.begin()
                val entered = CountDownLatch(1)
                val copy = pool.start(File(root, "sources"), 32, {
                    entered.countDown(); allow.await(); byteArrayOf(1).inputStream()
                })
                copies += copy
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                request.session.source = copy
                if (index == 0) {
                    requests.cancel()
                    assertTrue(first.job!!.isCancelled)
                }
            }
            assertFalse(requests.finishReading(first)) // old finally cannot clear newer request
            requests.cancel()
            repeat(30) {
                requests.begin()
                assertThrows(PluginPackageException::class.java) {
                    pool.start(File(root, "sources"), 32, { error("pool must reject") })
                }
                requests.cancel()
            }
        } finally { allow.countDown(); copies.forEach { it.cancel(); it.release() } }
        withTimeout(2000) { copies.forEach { it.finished.await() } }
    } }
}
