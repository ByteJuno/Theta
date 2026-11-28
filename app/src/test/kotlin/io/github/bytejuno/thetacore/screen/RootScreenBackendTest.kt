package io.github.bytejuno.thetacore.screen

import android.content.Context
import io.github.bytejuno.thetacore.root.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RootScreenBackendTest {
    private class Finished(override val exitCode: Int = 0) : RootProcess {
        override val rootConfirmed = true
        override val commandStarted = true
        override val actualExit = true
        override val outcome = RootOutcome.COMPLETED
        override val outputTruncated = false
        override fun requestStop() = Unit
        override fun output() = "" to ""
        override fun close() = Unit
    }

    @Test fun inputIsPrivateUtf8NotShellTextAndOnlyPreWriteFailureCanFallBack() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val text = "中文😀\n'\";$(bad)\\"
        var captured: ByteArray? = null; var command = ""; var exitCode = 0
        val backend = RootScreenBackend(context, RootBridge(RootBackend {
            command = it
            captured = File(context.noBackupFilesDir, "screen-calls").listFiles()!!.single().resolve("data").readBytes()
            Finished(exitCode)
        }), authorized = { true })
        val action = ScreenAction("screen_input_text", buildJsonObject { put("text", text) })
        assertEquals(ToolEffect.APPLIED, backend.execute(action).result.effect)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), captured)
        assertFalse(command.contains(text)); assertFalse(command.contains("input text")); assertTrue(command.endsWith("&& input keyevent 279"))
        assertTrue(File(context.noBackupFilesDir, "screen-calls").listFiles()!!.isEmpty())
        exitCode = 78
        assertTrue(backend.execute(action).unavailableBeforeStart)
        exitCode = 79
        val failed = backend.execute(action)
        assertFalse(failed.unavailableBeforeStart); assertEquals(ToolEffect.UNKNOWN, failed.result.effect)
    }

    @Test fun passiveDiscoveryAndUngrantedCallsNeverStartSu() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val backend = RootScreenBackend(context, RootBridge(RootBackend { error("su started without prior grant") }), authorized = { false })
        assertFalse(backend.ready)
        assertTrue(backend.execute(ScreenAction("screen_capture", buildJsonObject {})).unavailableBeforeStart)
        assertTrue(ScreenRuntime.tools(context).any { it.id == "screen_capture" })
    }

    @Test fun treeCommandOutputIsReadOnlyAfterActualExitAndFilesAreBounded() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val backend = RootScreenBackend(context, RootBridge(RootBackend {
            assertTrue(it.contains("uiautomator dump --compressed"))
            File(context.noBackupFilesDir, "screen-calls").listFiles()!!.single().resolve("data").writeText(
                """<hierarchy><node package="app" text="目标" bounds="[0,0][40,80]"/></hierarchy>""")
            Finished()
        }), authorized = { true })
        val reply = backend.execute(ScreenAction("screen_ui_tree", buildJsonObject {}))
        assertFalse(reply.result.isError)
        assertEquals("[20,40]", reply.result.structured!!.jsonObject["nodes"]!!.jsonArray.single().jsonObject["center"].toString())
        val file = File.createTempFile("theta-screen-limit", ".bin", context.cacheDir)
        try {
            file.writeBytes(ByteArray(11)); assertThrows(IllegalArgumentException::class.java) { readBounded(file, 10) }
        } finally { file.delete() }
        Unit
    }
}
