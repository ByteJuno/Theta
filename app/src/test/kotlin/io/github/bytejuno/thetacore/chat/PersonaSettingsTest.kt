package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.plugin.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PersonaSettingsTest {
    @Test fun validLegacyFileAndSupplementaryCharactersRoundTripThroughDataStore() = runBlocking {
        val root = Files.createTempDirectory("theta-persona-store").toFile()
        try {
            val file = root.resolve("chat-persona.json")
            file.writeText("""{"id":"test.prompts/guide","unavailable":false}""")
            val settings = PersonaSettings(root)
            assertEquals("test.prompts/guide", settings.current().id)
            settings.select("test.prompts/guide😀")
            assertEquals("test.prompts/guide😀", PersonaSettings(root).current().id)
            assertEquals("test.prompts/guide😀", file.inputStream().use { PersonaSerializer.readFrom(it) }.id)
            val bytes = ByteArrayOutputStream()
            PersonaSerializer.writeTo(settings.current(), bytes)
            assertEquals(settings.current(), PersonaSerializer.readFrom(bytes.toByteArray().inputStream()))
            try { settings.select("test.prompts/guide\uD800"); fail("Invalid UTF-16") }
            catch (_: PluginPackageException) { }
            assertEquals("test.prompts/guide😀", settings.current().id)
        } finally { root.deleteRecursively() }
    }

    @Test fun damagedPreferencesResetWithPersistentNoticeUntilExplicitSelection() = runBlocking {
        for (bad in listOf("""{"id":"test.prompts/guide?"}""",
            """{"id":"test.prompts/guide\uD800"}""", "{broken", "x".repeat(4097),
            """{"id":42}""", """{"unavailable":"true"}""")) {
            val root = Files.createTempDirectory("theta-persona-recovery").toFile()
            try {
                root.resolve("chat-persona.json").writeText(bad)
                val settings = PersonaSettings(root)
                val snapshot = settings.resolve(PluginPromptData())
                assertNull(snapshot.persona); assertTrue(snapshot.recovered)
                assertTrue(PersonaSettings(root).current().recovered)
                assertTrue(root.resolve("chat-persona.json").inputStream().use { PersonaSerializer.readFrom(it) }.recovered)
                settings.select(null)
                assertFalse(settings.resolve(PluginPromptData()).recovered)
                settings.select("test.prompts/guide")
                val data = PluginPromptData(personas = listOf(DataPersona("test.prompts/guide", "Guide", "Explain")))
                assertEquals("Explain", settings.resolve(data).persona!!.systemPrompt)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun inputOutputFailureDoesNotMasqueradeAsCorruption() = runBlocking {
        val failure = IOException("Storage unavailable")
        try {
            PersonaSerializer.readFrom(object : InputStream() { override fun read(): Int = throw failure })
            fail("Expected IO failure")
        } catch (error: IOException) { assertSame(failure, error) }
    }
}
