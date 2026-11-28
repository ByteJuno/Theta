package io.github.bytejuno.thetacore.ui.theme

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import org.junit.Assert.*
import org.junit.Test

class ThemePreferenceStateTest {
    @Test fun liveActivityOwnersObserveOnlyCommittedChangesAndCanRetryFailures() = runBlocking {
        val directory = Files.createTempDirectory("theta-theme-owners").toFile()
        val owners = Job()
        val scope = CoroutineScope(coroutineContext + owners)
        var failMove = false
        val fileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                if (failMove) throw IOException("Replacement unavailable")
                super.atomicMove(source, target)
            }
        }
        suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
            while (!condition()) delay(1)
        }
        try {
            val first = ThemePreferenceState(ThemePreferenceStore(directory), scope)
            val second = ThemePreferenceState(ThemePreferenceStore(directory, fileSystem = fileSystem), scope)
            first.load(); second.load()
            await { !first.loading && !second.loading }
            second.setMode(ThemeMode.DARK)
            await { !second.saving && first.preference.mode == ThemeMode.DARK && second.preference.mode == ThemeMode.DARK }
            assertTrue(first.preference.isDark(false))

            failMove = true
            second.setMode(ThemeMode.LIGHT)
            await { !second.saving }
            assertTrue(second.failed)
            assertEquals(ThemeMode.DARK, first.preference.mode)
            assertEquals(ThemeMode.DARK, second.preference.mode)
            assertEquals(ThemeMode.DARK, ThemePreferenceStore(directory).current().mode)

            failMove = false
            second.setMode(ThemeMode.LIGHT)
            await { !second.saving && first.preference.mode == ThemeMode.LIGHT && second.preference.mode == ThemeMode.LIGHT }
            assertFalse(second.failed)
            first.setDynamic(false)
            await { !first.saving && !second.preference.dynamic }
            val expected = ThemePreference(ThemeMode.LIGHT, false)
            assertEquals(expected, first.preference)
            assertEquals(expected, second.preference)
            assertEquals(expected, ThemePreferenceStore(directory).current())
        } finally {
            owners.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
