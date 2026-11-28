package io.github.bytejuno.thetacore.chat

import android.content.*
import android.net.Uri
import android.os.*
import io.github.bytejuno.thetacore.agent.ledger.ComposerDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

internal object DocumentImport {
    private val gate = Mutex()
    private var pendingExit: Deferred<Unit>? = null

    suspend fun load(context: Context, uri: Uri): ComposerDocument = gate.withLock {
        // A timeout is not proof of process exit. Keep its lease, but fail new UI requests
        // promptly until the death notification arrives instead of stranding their state.
        if (pendingExit?.isCompleted == false) throw DocumentException(DocumentFailure.INVALID)
        val request = UUID.randomUUID().toString()
        val result = File(context.cacheDir, "document-result-$request")
        val connected = CompletableDeferred<IBinder>()
        val exited = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(component: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(component: ComponentName) { exited.complete(Unit) }
            override fun onBindingDied(component: ComponentName) { exited.complete(Unit) }
            override fun onNullBinding(component: ComponentName) {
                connected.completeExceptionally(DocumentException(DocumentFailure.INVALID))
            }
        }
        var bound = false
        var binder: IBinder? = null
        var sent = false
        var deadline = 0L
        val death = IBinder.DeathRecipient { exited.complete(Unit) }
        try {
            // No provider calls or pipe reads in the host. The one-shot process owns them all.
            bound = context.bindService(Intent(context, DocumentParserService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw DocumentException(DocumentFailure.INVALID)
            binder = withTimeout(10_000) { connected.await() }
            binder.linkToDeath(death, 0)
            pendingExit = exited
            deadline = SystemClock.elapsedRealtime() + 35_000
            sent = true
            Messenger(binder).send(Message.obtain().apply { data = Bundle().apply {
                putParcelable("uri", uri); putString("request", request)
            } })
            // Actual death, not a logical result or coroutine cancellation, ends the lease.
            withTimeout(35_000) { exited.await() }
            withContext(Dispatchers.IO) {
                result.inputStream().buffered().use { readDocumentParserResult(java.io.DataInputStream(it)) }
            }
        } finally {
            if (sent && !exited.isCompleted) withContext(NonCancellable) {
                withTimeoutOrNull((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) { exited.await() }
            }
            // Retain the death recipient if the OS has not yet confirmed exit.
            if (exited.isCompleted) binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
            if (bound) context.unbindService(connection)
            result.delete()
        }
    }
}
