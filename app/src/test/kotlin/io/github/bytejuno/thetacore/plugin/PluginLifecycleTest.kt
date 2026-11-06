package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.PluginClosedException
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginLifecycleTest {
    private val id = "test.lifecycle"

    @Test fun disableAndPendingDeleteSurviveStoreRecreation() {
        val root = Files.createTempDirectory("theta-state").toFile()
        try {
            val store = PluginStateStore(root)
            assertTrue(store.read(id).enabled)
            store.write(id, PluginState(enabled = false))
            assertFalse(PluginStateStore(root).read(id).enabled)
            val tombstone = PluginState(enabled = false, pendingDelete = true, clearData = true)
            store.write(id, tombstone)
            assertEquals(tombstone, PluginStateStore(root).read(id))
            assertEquals(listOf(id), PluginStateStore(root).ids())
            store.remove(id)
            assertTrue(store.ids().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun initializingSurvivesRestartAndCanBeConservativelyDisabled() {
        val root = Files.createTempDirectory("theta-init").toFile()
        try {
            PluginStateStore(root).write(id, PluginState(initializing = true))
            val restarted = PluginStateStore(root)
            val interrupted = restarted.read(id)
            assertTrue(interrupted.initializing)
            restarted.write(id, interrupted.copy(enabled = false, initializing = false))
            assertEquals(PluginState(enabled = false), PluginStateStore(root).read(id))
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptStateIsNotTreatedAsDefaultEnabled() {
        val root = Files.createTempDirectory("theta-corrupt").toFile()
        try {
            PluginStateStore(root).write(id, PluginState())
            root.resolve("plugin-state/$id.properties").writeText("enabled=garbage")
            assertThrows(IOException::class.java) { PluginStateStore(root).read(id) }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedAttemptAndReenableCannotReloadUntilNewProcessGate() {
        val gate = PluginLoadGate()
        assertTrue(gate.begin(id))
        assertTrue(gate.wasAttempted(id))
        assertFalse(gate.begin(id)) // No release on failure, disable, upgrade or uninstall.
        assertTrue(PluginLoadGate().begin(id))
    }

    @Test fun deletionDoesNotFollowLinksAndWorksWithoutValidManifest() {
        val root = Files.createTempDirectory("theta-delete").toFile()
        val outside = Files.createTempDirectory("theta-outside").toFile()
        try {
            val keep = outside.resolve("keep").apply { writeText("keep") }
            val installed = root.resolve("plugins/$id/1").apply { mkdirs() }
            installed.resolve("plugin.apk").writeText("corrupt apk")
            Files.createSymbolicLink(installed.resolve("external").toPath(), outside.toPath())
            val data = root.resolve("plugin-data/$id").apply { mkdirs() }
            data.resolve("state").writeText("data")
            val store = PluginStore(root, root.resolve("cache"))
            store.delete(id, false)
            assertFalse(root.resolve("plugins/$id").exists())
            assertTrue(keep.exists())
            assertTrue(data.exists())
            store.delete(id, true)
            assertFalse(data.exists())
            assertTrue(keep.exists())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun equalAndLowerVersionsAreRejectedWithoutTouchingPublishedApk() {
        val root = Files.createTempDirectory("theta-version").toFile()
        try {
            val installed = root.resolve("plugins/$id/2").apply { mkdirs() }.resolve("plugin.apk")
            installed.writeText("live bytes")
            val store = PluginStore(root, root.resolve("cache"))
            for (version in listOf(1L, 2L)) {
                val stagedFile = root.resolve("staged-$version.apk").apply { writeText("new bytes") }
                val staged = StagedPlugin(stagedFile, PluginManifest(id, "Test", "v$version", version, 1, "Entry"), "hash")
                assertThrows(PluginPackageException::class.java) { store.install(staged) }
                assertFalse(stagedFile.exists())
                assertEquals("live bytes", installed.readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedPublicationDoesNotLeaveVersionDirectoryOrConsumeStage() {
        val root = Files.createTempDirectory("theta-publish").toFile()
        try {
            val stagedFile = root.resolve("staged.apk").apply { writeText("not an APK") }
            val staged = StagedPlugin(stagedFile, PluginManifest(id, "Test", "v1", 1, 1, "Entry"), "wrong hash")
            assertThrows(PluginPackageException::class.java) { PluginStore(root, root).install(staged) }
            assertFalse(stagedFile.exists())
            assertFalse(root.resolve("plugins/$id/1").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun linkedStorageAncestorIsRejected() {
        val root = Files.createTempDirectory("theta-link").toFile()
        val outside = Files.createTempDirectory("theta-link-outside").toFile()
        try {
            Files.createSymbolicLink(root.resolve("plugins").toPath(), outside.toPath())
            assertThrows(IOException::class.java) { deletePluginTree(root.resolve("plugins"), id) }
            Files.delete(root.resolve("plugins").toPath())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun cleanupIsReverseOrderAfterDisableAndContinuesAfterErrors() = runBlocking {
        val registry = PluginRegistrations<String>()
        val events = mutableListOf<String>()
        registry.onCleanup { events += "first" }
        registry.onCleanup { events += "second"; error("cleanup error") }
        val actions = registry.beginClose()!!
        val result = pluginCleanupWorker("cleanup-test", { events += "cancel" }, {
            events += "disable"
            error("disable error")
        }, actions).await(2_000)!!.getOrThrow()
        assertEquals(listOf("cancel", "disable", "second", "first"), events)
        assertEquals(2, result.size)
    }

    @Test fun noncooperativeEnableDoesNotBlockHostOrRegisterLate() = runBlocking {
        val registry = PluginRegistrations<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = PluginWorker("stuck-enable") {
            entered.countDown()
            release.await() // Deliberately ignores coroutine cancellation.
            assertThrows(PluginClosedException::class.java) { registry.register("late", "late") }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(worker.await(30))
            registry.beginClose()
            worker.cancel()
        } finally { release.countDown() }
        assertNotNull(worker.await(2_000))
    }

    @Test fun cleanupHasOneDeadlineEvenForBlockingCancellation() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val worker = pluginCleanupWorker("stuck-close", {
            entered.countDown()
            release.await()
        }, {}, emptyList())
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(worker.await(30))
        } finally { release.countDown() }
        assertTrue(worker.await(2_000)!!.getOrThrow().isEmpty())
    }
}
