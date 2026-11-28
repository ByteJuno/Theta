package io.github.bytejuno.thetacore.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.*

internal fun ChatComposerDraft.removeDocument(id: String) {
    documents = documents.filterNot { it.id == id }
    error = null
}

internal fun documentError(error: Exception): Int = when ((error as? DocumentException)?.reason) {
    DocumentFailure.UNSUPPORTED -> R.string.document_unsupported
    DocumentFailure.TOO_LARGE -> R.string.document_too_large
    DocumentFailure.TOO_LONG -> R.string.document_too_long
    DocumentFailure.TOO_MANY_PAGES -> R.string.document_too_many_pages
    DocumentFailure.ENCRYPTED -> R.string.document_encrypted
    DocumentFailure.EMPTY -> R.string.document_empty
    else -> R.string.document_invalid
}

private val documentImports = mutableStateMapOf<String, Boolean>()
private val documentImportErrors = mutableStateMapOf<String, Int>()
private val documentImportUpdates = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Long>?>(null)
private val documentImportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

@Composable
internal fun rememberDocumentPicker(drafts: ChatComposerDrafts, session: String?, owner: ChatSessionOwner): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = documentImportScope
    val importing = session?.let { documentImports[it] } == true
    val importError = session?.let { documentImportErrors[it] }
    LaunchedEffect(session, importing, importError) {
        drafts[session].importing = importing
        if (importError != null) drafts[session].error = importError
    }
    LaunchedEffect(drafts) {
        documentImportUpdates.collect { update -> update?.let { drafts.refreshDocuments(it.first) } }
    }
    var pickerSession by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val id = pickerSession
            val target = drafts.pickerTarget(id) { owner.open().join() } ?: return@launch
            if (id != null && documentImports[id] == true) return@launch
            if (target.documents.size >= DocumentLimits.PER_MESSAGE) {
                target.error = R.string.document_limit
                return@launch
            }
            target.importing = true; target.error = null
            if (id != null) { documentImports[id] = true; documentImportErrors.remove(id) }
            try {
                val document = DocumentImport.load(context, uri)
                if (drafts.isLiveDraft(id, target)) {
                    target.documents = (target.documents + document).take(DocumentLimits.PER_MESSAGE)
                    // Persist bytes before releasing the URI; no external permission is needed on restore.
                    drafts.checkpoint()
                    if (id != null) documentImportUpdates.value = id to System.nanoTime()
                }
            } catch (_: TimeoutCancellationException) {
                if (drafts.isLiveDraft(id, target)) target.error = R.string.document_invalid
                if (id != null) documentImportErrors[id] = R.string.document_invalid
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (drafts.isLiveDraft(id, target)) target.error = documentError(error)
                if (id != null) documentImportErrors[id] = documentError(error)
            } finally {
                target.importing = false
                if (id != null) documentImports.remove(id)
            }
        }
    }
    return {
        pickerSession = session
        picker.launch(DocumentExtraction.pickerTypes)
    }
}

@Composable
internal fun DocumentDraftPreview(draft: ChatDraftSnapshot, enabled: Boolean, remove: (String) -> Unit) {
    val strings = uiStrings()
    if (draft.importing) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(strings.get(R.string.document_importing), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
    for (document in draft.documents) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(document.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(strings.get(R.string.document_size, document.size, document.text.codePointCount(0, document.text.length)),
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { remove(document.id) }, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(strings.get(R.string.document_remove))
        }
    }
}

@Composable
internal fun SentDocumentText(text: String) {
    val presentation = remember(text) { documentMessagePresentation(text) }
    val strings = uiStrings()
    if (presentation.text.isNotEmpty()) androidx.compose.foundation.text.selection.SelectionContainer { Text(presentation.text) }
    for (document in presentation.documents) Column {
        Text(document.name, style = MaterialTheme.typography.bodyMedium)
        Text(strings.get(R.string.document_size, document.bytes, document.characters), style = MaterialTheme.typography.bodySmall)
    }
}
