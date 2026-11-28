package io.github.bytejuno.thetacore.ui.theme

import androidx.datastore.core.CorruptionException
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ThemePreferenceStoreTest {
    @Test fun modesAndPaletteRoundTripThroughDiskAndWrapperRecreation() = runBlocking {
        val root = Files.createTempDirectory("theta-theme").toFile()
        val store = ThemePreferenceStore(root)
        assertEquals(ThemePreference(), store.current())
        for (mode in ThemeMode.entries) for (dynamic in listOf(false, true)) {
            store.setMode(mode); store.setDynamic(dynamic)
            val expected = ThemePreference(mode, dynamic)
            assertEquals(expected, ThemePreferenceStore(root).current())
            assertEquals(expected, ThemePreferenceSerializer().readFrom(Buffer().write(root.resolve(ThemePreferenceStore.FILE_NAME).readBytes())))
        }
    }

    @Test fun corruptFileIsAtomicallyReplacedWithSafeDefaults() = runBlocking {
        for (invalid in listOf("broken", "x".repeat(4097), """{"mode":"MIDNIGHT","dynamic":true}""",
            """{"mode":"DARK","dynamic":"true"}""", """{"mode":"DARK"}""")) {
            val root = Files.createTempDirectory("theta-theme-corruption").toFile()
            val file = root.resolve(ThemePreferenceStore.FILE_NAME)
            file.writeText(invalid)
            assertEquals(ThemePreference(), ThemePreferenceStore(root, legacyDynamic = false).current())
            assertEquals(ThemePreference(), ThemePreferenceSerializer().readFrom(Buffer().write(file.readBytes())))
        }
    }

    @Test fun legacyDynamicChoiceIsUsedOnlyBeforeANewFileExists() = runBlocking {
        val root = Files.createTempDirectory("theta-theme-legacy").toFile()
        val store = ThemePreferenceStore(root, legacyDynamic = false)
        assertFalse(store.current().dynamic)
        store.setMode(ThemeMode.DARK)
        assertEquals(ThemePreference(ThemeMode.DARK, false), ThemePreferenceStore(root, true).current())
        val persisted = ThemePreferenceSerializer(true).readFrom(Buffer().write(root.resolve(ThemePreferenceStore.FILE_NAME).readBytes()))
        assertEquals(ThemePreference(ThemeMode.DARK, false), persisted)
    }

    @Test fun concurrentUpdatesReplaceCompleteFilesWithoutLosingOtherFields() = runBlocking {
        val root = Files.createTempDirectory("theta-theme-atomic").toFile()
        var replacements = 0
        val fileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                // Observe at the actual replacement boundary with no competing Windows handle.
                val candidate = runBlocking { ThemePreferenceSerializer().readFrom(Buffer().write(Files.readAllBytes(source.toNioPath()))) }
                if (Files.exists(target.toNioPath())) runBlocking {
                    ThemePreferenceSerializer().readFrom(Buffer().write(Files.readAllBytes(target.toNioPath())))
                }
                super.atomicMove(source, target)
                val replaced = runBlocking { ThemePreferenceSerializer().readFrom(Buffer().write(Files.readAllBytes(target.toNioPath()))) }
                assertEquals(candidate, replaced)
                replacements++
            }
        }
        val store = ThemePreferenceStore(root, fileSystem = fileSystem)
        store.setMode(ThemeMode.LIGHT)
        coroutineScope {
            launch(Dispatchers.IO) { repeat(10) { store.setMode(ThemeMode.DARK) } }
            launch(Dispatchers.IO) { repeat(10) { store.setDynamic(false) } }
        }
        assertTrue(replacements >= 3)
        assertEquals(ThemePreference(ThemeMode.DARK, false), store.current())
    }

    @Test fun failedAtomicReplacementKeepsPreviousDiskAndMemoryValueAndCanRetry() = runBlocking {
        val root = Files.createTempDirectory("theta-theme-write-failure").toFile()
        var failReplace = false
        val failure = IOException("replacement unavailable")
        val fileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                if (failReplace) throw failure
                super.atomicMove(source, target)
            }
        }
        val store = ThemePreferenceStore(root, fileSystem = fileSystem)
        store.setMode(ThemeMode.LIGHT)
        failReplace = true
        try { store.setMode(ThemeMode.DARK); fail("Expected write failure") }
        catch (actual: IOException) {
            assertEquals(failure.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }
        assertEquals(ThemeMode.LIGHT, store.current().mode)
        val file = root.resolve(ThemePreferenceStore.FILE_NAME)
        assertEquals(ThemeMode.LIGHT, ThemePreferenceSerializer().readFrom(Buffer().write(file.readBytes())).mode)
        failReplace = false
        store.setMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemePreferenceSerializer().readFrom(Buffer().write(file.readBytes())).mode)
    }

    @Test fun ioFailureIsNotClassifiedAsCorruption() = runBlocking {
        val failure = IOException("unavailable")
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        try { ThemePreferenceSerializer().readFrom(source); fail("Expected read failure") }
        catch (actual: IOException) { assertSame(failure, actual); assertFalse(actual is CorruptionException) }
    }

    @Test fun explicitModeOverridesSystemWhileSystemModeTracksIt() {
        assertTrue(ThemePreference().isDark(true))
        assertFalse(ThemePreference().isDark(false))
        assertTrue(ThemePreference(ThemeMode.DARK).isDark(false))
        assertFalse(ThemePreference(ThemeMode.LIGHT).isDark(true))
    }
}
