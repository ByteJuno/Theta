package io.github.bytejuno.thetacore.chat

import android.app.Service
import android.content.Intent
import android.os.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal fun isDocumentParserProcess() = android.app.Application.getProcessName().endsWith(":document_parser")

/** Private one-shot parser process: hostile PDF allocation cannot take down the chat process. */
internal class DocumentParserService : Service() {
    private val started = AtomicBoolean()
    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!started.compareAndSet(false, true)) return@Handler true
        val data = message.data
        val source = data.getParcelable("source", ParcelFileDescriptor::class.java)
        val destination = data.getParcelable("destination", ParcelFileDescriptor::class.java)
        if (source == null || destination == null) { stopSelf(); return@Handler true }
        // This process owns only this import. Includes blocked/decompression work and pipe I/O.
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 30_000)
        Thread({
            try {
                val scratch = java.io.File(cacheDir, "document-parser-work")
                scratch.deleteRecursively(); check(scratch.mkdirs())
                PDFBoxResourceLoader.init(applicationContext)
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(destination)).use { output ->
                    val document = try {
                        val bytes = ParcelFileDescriptor.AutoCloseInputStream(source).use { DocumentExtraction.readBounded(it) }
                        DocumentExtraction.extract(data.getString("name").orEmpty(), data.getString("mime"), bytes, scratch)
                    } catch (error: Exception) {
                        output.writeUTF((error as? DocumentException)?.reason?.name ?: DocumentFailure.INVALID.name)
                        return@use
                    }
                    output.writeUTF("OK"); output.writeUTF(document.name); output.writeUTF(document.mime)
                    val text = document.text.toByteArray(Charsets.UTF_8)
                    output.writeLong(document.size); output.writeInt(text.size); output.write(text)
                }
            } finally {
                runCatching { source.close() }; runCatching { destination.close() }
                Process.killProcess(Process.myPid())
            }
        }, "document-import").start()
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
}
