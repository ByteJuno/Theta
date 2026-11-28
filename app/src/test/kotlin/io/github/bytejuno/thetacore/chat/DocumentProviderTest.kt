package io.github.bytejuno.thetacore.chat

import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DocumentProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private val uri = Uri.parse("content://document-test/file")

    private open class Provider(private val file: java.io.File) : ContentProvider() {
        var name = "sample.txt"
        var mime = "text/plain"
        open fun enter(stage: String) {}
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            enter("query")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(name, file.length())) }
        }
        override fun getType(uri: Uri): String { enter("type"); return mime }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            enter("open"); return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun providerAndExtractionPreserveUnicodeAndOriginalText() {
        val file = temp.newFile().apply { writeText("hello 文档") }
        val provider = Provider(file).apply { name = "a".repeat(159) + "😀.txt" }
        provider.attachInfo(RuntimeEnvironment.getApplication(), android.content.pm.ProviderInfo().apply { authority = "document-test"; exported = true })
        ShadowContentResolver.registerProviderInternal("document-test", provider)
        val result = readProviderDocument(RuntimeEnvironment.getApplication().contentResolver, uri, temp.root)
        assertEquals("a".repeat(159), result.name)
        assertEquals("hello 文档", result.text)
        assertTrue(Charsets.UTF_8.newEncoder().canEncode(result.name))
    }

    @Test fun recognizesCodeExtensionBeforeBoundingLongFilename() {
        val file = temp.newFile().apply { writeText("val answer = 42") }
        val provider = Provider(file).apply {
            name = "a".repeat(159) + "😀.kt"
            mime = "application/octet-stream"
        }
        provider.attachInfo(RuntimeEnvironment.getApplication(), android.content.pm.ProviderInfo().apply { authority = "document-test"; exported = true })
        ShadowContentResolver.registerProviderInternal("document-test", provider)
        val result = readProviderDocument(RuntimeEnvironment.getApplication().contentResolver, uri, temp.root)
        assertEquals("val answer = 42", result.text)
        assertEquals("text/plain", result.mime)
        assertEquals("a".repeat(159), result.name)
        assertTrue(Charsets.UTF_8.newEncoder().canEncode(result.name))
    }

    @Test fun watchdogRunsWhileEachProviderOperationIgnoresInterruption() {
        for (blocked in listOf("query", "type", "open")) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val termination = CountDownLatch(1)
            val file = temp.newFile().apply { writeText("hello") }
            val provider = object : Provider(file) {
                override fun enter(stage: String) {
                    if (stage == blocked) {
                        entered.countDown()
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    }
                }
            }
            provider.attachInfo(RuntimeEnvironment.getApplication(), android.content.pm.ProviderInfo().apply { authority = "document-test"; exported = true })
        ShadowContentResolver.registerProviderInternal("document-test", provider)
            val worker = Thread { readProviderDocument(RuntimeEnvironment.getApplication().contentResolver, uri, temp.root) }
                .apply { isDaemon = true; start() }
            assertTrue(blocked, entered.await(2, TimeUnit.SECONDS))
            val watchdog = DocumentProcessWatchdog(25) { termination.countDown() }
            try {
                worker.interrupt()
                assertTrue(blocked, termination.await(2, TimeUnit.SECONDS))
                assertTrue(worker.isAlive)
            } finally { watchdog.finish(); release.countDown(); worker.join(2000) }
        }
    }
}
