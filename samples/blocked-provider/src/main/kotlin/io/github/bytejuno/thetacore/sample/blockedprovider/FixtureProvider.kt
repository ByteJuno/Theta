package io.github.bytejuno.thetacore.sample.blockedprovider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

/** Read-only SAF root with no thumbnail/preview/search work and immediate metadata queries. */
class FixtureProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            addValues(mapOf(
                Root.COLUMN_ROOT_ID to ROOT_ID,
                Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                Root.COLUMN_TITLE to "Theta blocked sources",
                Root.COLUMN_SUMMARY to "Disposable S1 data ZIP fixtures",
                Root.COLUMN_FLAGS to Root.FLAG_LOCAL_ONLY,
                Root.COLUMN_MIME_TYPES to "application/zip",
                Root.COLUMN_ICON to android.R.drawable.ic_menu_save,
            ))
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?,
    ): Cursor {
        if (parentDocumentId != ROOT_ID) throw FileNotFoundException("Not a directory")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            FixtureDocument.entries.forEach { addDocument(it.key) }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && FixtureDocument.fromId(documentId) != null

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only fixture")
        val document = FixtureDocument.fromId(documentId) ?: throw FileNotFoundException(documentId)
        signal?.throwIfCanceled()
        val session = FixtureRuntime.open(document, Binder.getCallingUid())
        var descriptor: ParcelFileDescriptor? = null
        try {
            // This signal belongs to openDocument, not subsequent read(2) or Activity disposal.
            signal?.setOnCancelListener {
                FixtureRuntime.event(session, "OPEN_SIGNAL_CANCEL")
                session.gate.abort("OPEN_CANCEL")
            }
            signal?.throwIfCanceled()
            session.thread.start()
            val storage = requireNotNull(context).getSystemService(StorageManager::class.java)
            val opened = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = document.bytes.size.toLong()

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        session.prefixReader?.let { reader ->
                            try { return reader.read(offset, size, data) }
                            catch (_: SourceReadAborted) {
                                throw ErrnoException("fixture source copy aborted", OsConstants.EIO)
                            } catch (_: IllegalArgumentException) {
                                throw ErrnoException("fixture range", OsConstants.EINVAL)
                            }
                        }
                        if (session.firstRead) {
                            session.firstRead = false
                            FixtureRuntime.event(session, "READ_ENTER mode=${document.mode} offset=$offset size=$size")
                            val outcome = session.gate.await()
                            FixtureRuntime.event(session, "READ_GATE_EXIT outcome=$outcome")
                        }
                        if (session.gate.failure() != null) {
                            FixtureRuntime.event(session, "READ_ABORT reason=${session.gate.failure()}")
                            throw ErrnoException("fixture read aborted", OsConstants.EIO)
                        }
                        if (offset < 0 || size < 0) throw ErrnoException("fixture range", OsConstants.EINVAL)
                        if (offset >= document.bytes.size) {
                            FixtureRuntime.event(session, "READ_EOF")
                            return 0
                        }
                        val count = minOf(size, data.size, document.bytes.size - offset.toInt())
                        document.bytes.copyInto(data, 0, offset.toInt(), offset.toInt() + count)
                        FixtureRuntime.event(session, "READ_RETURN offset=$offset bytes=$count")
                        return count
                    }

                    override fun onRelease() {
                        // FUSE queues release on this descriptor's callback Handler. It can be
                        // delayed behind blocked onRead, and is NOT a host-cancel notification.
                        FixtureRuntime.finish(session, "FD_RELEASE")
                    }
                },
                Handler(session.thread.looper),
            )
            descriptor = opened
            signal?.throwIfCanceled()
            FixtureRuntime.event(session, "OPEN_RETURN")
            return opened
        } catch (error: Exception) {
            session.gate.abort("OPEN_FAILED")
            FixtureRuntime.event(session, "OPEN_FAILED type=${error.javaClass.simpleName}")
            val opened = descriptor
            if (opened == null) FixtureRuntime.finish(session, "OPEN_RETIRED")
            else opened.close() // onRelease retains ownership after successful proxy allocation.
            throw error
        } finally {
            // Do not retain a framework open cancellation transport for the descriptor lifetime.
            signal?.setOnCancelListener(null)
        }
    }

    private fun MatrixCursor.addDocument(id: String) {
        if (id == ROOT_ID) {
            addValues(mapOf(
                Document.COLUMN_DOCUMENT_ID to ROOT_ID,
                Document.COLUMN_DISPLAY_NAME to "Theta blocked sources",
                Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
                Document.COLUMN_FLAGS to 0,
            ))
        } else {
            val document = FixtureDocument.fromId(id) ?: throw FileNotFoundException(id)
            addValues(mapOf(
                Document.COLUMN_DOCUMENT_ID to id,
                Document.COLUMN_DISPLAY_NAME to document.displayName,
                Document.COLUMN_MIME_TYPE to "application/zip",
                Document.COLUMN_SIZE to document.bytes.size.toLong(),
                Document.COLUMN_FLAGS to 0,
                Document.COLUMN_LAST_MODIFIED to 1704067200000L,
                Document.COLUMN_SUMMARY to when (document.mode) {
                    ReadMode.IMMEDIATE -> if (document.isPrefixFixture) "Complete prefix-copy control (same padded ZIP)" else "Immediate benign control"
                    ReadMode.PREFIX_ABORT -> "Positive prefix, then wait for Reset / EIO (120 second ceiling)"
                    ReadMode.DELAY -> "First read waits 15 seconds"
                    ReadMode.BLOCK -> "First read waits for local Release (120 second ceiling)"
                },
            ))
        }
    }

    private fun MatrixCursor.addValues(values: Map<String, Any>) {
        addRow(columnNames.map { values[it] }.toTypedArray())
    }

    companion object {
        private const val ROOT_ID = "fixtures"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SUMMARY)
    }
}
