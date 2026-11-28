package io.github.bytejuno.thetacore.chat

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import io.github.bytejuno.thetacore.agent.ledger.ComposerDocument
import io.github.bytejuno.thetacore.agent.ledger.DocumentLimits
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream

internal object DocumentImport {
    private val gate = Mutex()

    suspend fun load(context: Context, uri: Uri): ComposerDocument = gate.withLock {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name = "document"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val size = it.getColumnIndex(OpenableColumns.SIZE)
                    if (size >= 0 && !it.isNull(size) && it.getLong(size) > DocumentLimits.BYTES)
                        throw DocumentException(DocumentFailure.TOO_LARGE)
                    val label = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (label >= 0 && !it.isNull(label)) name = it.getString(label).take(160)
                }
            }
            val input = resolver.openFileDescriptor(uri, "r") ?: throw DocumentException(DocumentFailure.INVALID)
            val pipe = ParcelFileDescriptor.createPipe()
            val connected = CompletableDeferred<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(component: ComponentName, service: IBinder) { connected.complete(service) }
                override fun onServiceDisconnected(component: ComponentName) { connected.completeExceptionally(DocumentException(DocumentFailure.INVALID)) }
                override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(DocumentException(DocumentFailure.INVALID)) }
            }
            var bound = false
            val exited = CompletableDeferred<Unit>()
            var sent = false
            try {
                bound = context.bindService(Intent(context, DocumentParserService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) throw DocumentException(DocumentFailure.INVALID)
                val binder = withTimeout(10_000) { connected.await() }
                binder.linkToDeath({ exited.complete(Unit) }, 0)
                Messenger(binder).send(Message.obtain().apply { data = Bundle().apply {
                    putParcelable("source", input); putParcelable("destination", pipe[1])
                    putString("name", name); putString("mime", resolver.getType(uri))
                } })
                sent = true
                pipe[1].close()
                DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe[0])).use { stream ->
                    readDocumentParserResult(stream)
                }
            } finally {
                input.close(); pipe.forEach { runCatching { it.close() } }
                try {
                    if (sent) withContext(NonCancellable) {
                        // Do not hand the serialized parser lease to another import until actual exit.
                        withTimeout(35_000) { exited.await() }
                    }
                } finally { if (bound) context.unbindService(connection) }
            }
        }
    }
}
