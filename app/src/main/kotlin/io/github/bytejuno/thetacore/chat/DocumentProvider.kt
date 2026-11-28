package io.github.bytejuno.thetacore.chat

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.bytejuno.thetacore.agent.ledger.ComposerDocument
import io.github.bytejuno.thetacore.agent.ledger.DocumentLimits
import java.io.File

/** Called only by the disposable parser process; every provider operation may block. */
internal fun readProviderDocument(resolver: ContentResolver, uri: Uri, scratch: File): ComposerDocument {
    var name = "document"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) {
            val size = it.getColumnIndex(OpenableColumns.SIZE)
            if (size >= 0 && !it.isNull(size) && it.getLong(size) > DocumentLimits.BYTES)
                throw DocumentException(DocumentFailure.TOO_LARGE)
            val label = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (label >= 0 && !it.isNull(label)) name = documentFilename(it.getString(label))
        }
    }
    val mime = resolver.getType(uri)
    val input = resolver.openFileDescriptor(uri, "r") ?: throw DocumentException(DocumentFailure.INVALID)
    val bytes = ParcelFileDescriptor.AutoCloseInputStream(input).use { DocumentExtraction.readBounded(it) }
    return DocumentExtraction.extract(name, mime, bytes, scratch)
}
