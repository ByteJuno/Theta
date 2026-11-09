package io.github.bytejuno.thetacore.hook

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

internal data class HookPickerCandidate(val packageName: String, val system: Boolean)

internal fun selectHookDocumentPicker(candidates: List<HookPickerCandidate>): String? =
    listOf("com.android.documentsui", "com.google.android.documentsui").firstOrNull { name ->
        candidates.any { it.packageName == name && it.system }
    }

/** Explicit user-selected alternative; no URI rewriting or storage permission escalation. */
internal class HookDocumentPicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        val candidates = context.packageManager.queryIntentActivities(
            intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        ).mapNotNull { result ->
            result.activityInfo?.let { activity ->
                HookPickerCandidate(activity.packageName,
                    activity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            }
        }
        val name = selectHookDocumentPicker(candidates)
        if (name != null) {
            intent.setPackage(name)
            // Avoid Recent's media index; the user still chooses the actual document.
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
        }
        return intent
    }
}

internal class HookSourceReadException(cause: Exception) : IOException(
    "Cannot read selected document. Try system document picker > phone storage > Download; do not use Recent.", cause,
)

internal fun readHookSource(open: () -> InputStream?): ByteArray = try {
    val input = open() ?: throw IOException("Document provider returned no stream")
    input.use { it.bounded(HOOK_LIMIT) }
} catch (error: CancellationException) {
    throw error
} catch (error: IOException) {
    throw HookSourceReadException(error)
} catch (error: SecurityException) {
    throw HookSourceReadException(error)
}
