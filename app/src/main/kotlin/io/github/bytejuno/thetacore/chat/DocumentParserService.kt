package io.github.bytejuno.thetacore.chat

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal fun isDocumentParserProcess() = android.app.Application.getProcessName().endsWith(":document_parser")

/** All external I/O is in this one-shot process, including provider metadata and open calls. */
internal class DocumentParserService : Service() {
    private val started = AtomicBoolean()
    private lateinit var watchdog: DocumentProcessWatchdog

    override fun onCreate() {
        super.onCreate()
        // Independent thread: a blocked worker or main looper cannot suppress the deadline.
        watchdog = DocumentProcessWatchdog(30_000) { Process.killProcess(Process.myPid()) }
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!started.compareAndSet(false, true)) return@Handler true
        val data = message.data
        val uri = data.getParcelable("uri", Uri::class.java)
        val request = data.getString("request")
        if (uri == null || request == null || runCatching { UUID.fromString(request).toString() }.getOrNull() != request) {
            watchdog.finish(); return@Handler true
        }
        Thread({
            try {
                val scratch = File(cacheDir, "document-parser-work")
                scratch.deleteRecursively(); check(scratch.mkdirs())
                // Previous host death may leave a bounded result. Never remove other caches.
                cacheDir.listFiles { _, name -> name.startsWith("document-result-") }?.forEach { it.delete() }
                PDFBoxResourceLoader.init(applicationContext)
                DataOutputStream(File(cacheDir, "document-result-$request").outputStream().buffered()).use { output ->
                    val document = try {
                        readProviderDocument(contentResolver, uri, scratch)
                    } catch (error: Exception) {
                        output.writeUTF((error as? DocumentException)?.reason?.name ?: DocumentFailure.INVALID.name)
                        return@use
                    }
                    output.writeUTF("OK"); output.writeUTF(document.name); output.writeUTF(document.mime)
                    val text = document.text.toByteArray(Charsets.UTF_8)
                    output.writeLong(document.size); output.writeInt(text.size); output.write(text)
                }
            } finally { watchdog.finish() }
        }, "document-import").start()
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
}
