package io.github.bytejuno.thetacore.chat

import android.app.Application
import android.content.*
import android.net.Uri
import android.os.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DocumentImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun hostNeverCallsProviderAndRecoversAfterParserDeath() = runBlocking {
        var success = false
        var binds = 0
        var unbinds = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir() = temp.root
            override fun getContentResolver(): ContentResolver = error("Host must not call provider")
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                binds++
                val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
                    assertEquals("content://blocked/file", message.data.getParcelable("uri", Uri::class.java).toString())
                    assertFalse(message.data.containsKey("source"))
                    if (success) {
                        val file = File(temp.root, "document-result-" + message.data.getString("request"))
                        DataOutputStream(file.outputStream()).use {
                            it.writeUTF("OK"); it.writeUTF("sample.txt"); it.writeUTF("text/plain")
                            it.writeLong(5); it.writeInt(5); it.write("hello".toByteArray())
                        }
                    }
                    connection.onServiceDisconnected(ComponentName(this, DocumentParserService::class.java))
                    true
                })
                connection.onServiceConnected(ComponentName(this, DocumentParserService::class.java), messenger.binder)
                return true
            }
            override fun unbindService(connection: ServiceConnection) { unbinds++ }
        }
        suspend fun attempt(): Result<io.github.bytejuno.thetacore.agent.ledger.ComposerDocument> {
            val job = async(Dispatchers.Default) { runCatching { DocumentImport.load(context, Uri.parse("content://blocked/file")) } }
            withTimeout(5_000) {
                while (!job.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            }
            return job.await()
        }
        assertTrue(attempt().isFailure)
        success = true
        assertEquals("hello", attempt().getOrThrow().text)
        assertEquals(2, binds); assertEquals(2, unbinds)
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }
}
