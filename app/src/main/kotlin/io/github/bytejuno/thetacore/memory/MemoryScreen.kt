package io.github.bytejuno.thetacore.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.agent.memory.*
import io.github.bytejuno.thetacore.settings.SettingsPage
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.*

@Composable
internal fun MemoryScreen(host: ThetaApplication, back: () -> Unit) {
    var store by remember { mutableStateOf<MemoryStore?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(host) {
        try { store = requireNotNull(host.hostTools().sessionStore).memories }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val current = store
    if (current != null) MemoryContent(current, back)
    else SettingsPage(uiStrings().get(R.string.memory_title), back) {
        if (failed) Text(uiStrings().get(R.string.memory_storage_error)) else CircularProgressIndicator()
    }
}

private data class MemoryPage(val enabled: Boolean, val usage: MemoryUsage, val items: List<MemoryEntry>, val more: Boolean)

@Composable
internal fun MemoryContent(store: MemoryStore, back: () -> Unit) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<MemoryPage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var content by rememberSaveable { mutableStateOf("") }
    var editCategory by rememberSaveable { mutableStateOf(MemoryUiKeys.GENERAL) }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val revision by store.changes.collectAsState()

    LaunchedEffect(store, query, category, offset, revision, reload) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                val entries = store.search(query, category.ifBlank { null }, 21, offset)
                MemoryPage(store.enabled(), store.usage(), entries.take(20), entries.size > 20)
            }
            if (result.items.isEmpty() && offset > 0) offset = (offset - 20).coerceAtLeast(0)
            else page = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { page = null; error = R.string.memory_storage_error }
        finally { loading = false }
    }

    fun mutate(success: Int = R.string.memory_saved, action: () -> Unit) {
        if (busy) return
        busy = true; error = null; feedback = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                feedback = success; editing = false; deleteId = null; clear = false; reload++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: MemoryCapacityException) { error = R.string.memory_capacity_error }
            catch (_: IllegalArgumentException) { error = R.string.memory_input_error }
            catch (_: Exception) { error = R.string.memory_storage_error }
            finally { busy = false }
        }
    }
    if (editing) {
        SettingsPage(strings.get(if (editingId == null) R.string.memory_add else R.string.memory_edit),
            { editing = false; error = null }, canGoBack = !busy) {
            OutlinedTextField(content, { if (it.length <= MemoryStore.MAX_CONTENT_CHARS) content = it },
                Modifier.fillMaxWidth().testTag(MemoryUiKeys.CONTENT), enabled = !busy,
                label = { Text(strings.get(R.string.memory_content)) }, minLines = 3,
                supportingText = { Text(strings.get(R.string.memory_length, content.length, MemoryStore.MAX_CONTENT_CHARS)) })
            OutlinedTextField(editCategory, { if (it.length <= 64) editCategory = it },
                Modifier.fillMaxWidth().testTag(MemoryUiKeys.CATEGORY), enabled = !busy, singleLine = true,
                label = { Text(strings.get(R.string.memory_category)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pinned, { pinned = it }, enabled = !busy)
                Text(strings.get(R.string.memory_pinned), Modifier.weight(1f))
            }
            error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { mutate { store.save(content, editCategory, pinned, id = editingId) } },
                enabled = !busy && content.isNotBlank() && editCategory.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(strings.get(R.string.memory_save))
            }
        }
        return
    }
    SettingsPage(strings.get(R.string.memory_title), back, canGoBack = !busy) {
        Text(strings.get(R.string.memory_description))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.get(R.string.memory_enabled), Modifier.weight(1f))
            Switch(page?.enabled ?: false, { value -> mutate { store.setEnabled(value) } },
                enabled = page != null && !busy && !loading, modifier = Modifier.testTag(MemoryUiKeys.ENABLED))
        }
        page?.let { Text(strings.get(R.string.memory_usage, it.usage.count, MemoryStore.MAX_ENTRIES,
            (it.usage.bytes + 1023) / 1024), style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(query, { if (it.length <= MemoryStore.MAX_QUERY_CHARS) { query = it; offset = 0 } },
            Modifier.fillMaxWidth().testTag(MemoryUiKeys.SEARCH), singleLine = true,
            label = { Text(strings.get(R.string.memory_search)) })
        OutlinedTextField(category, { if (it.length <= 64) { category = it; offset = 0 } },
            Modifier.fillMaxWidth(), singleLine = true, label = { Text(strings.get(R.string.memory_filter)) })
        Button(onClick = { editingId = null; content = ""; editCategory = MemoryUiKeys.GENERAL; pinned = false; error = null; editing = true },
            enabled = page != null && !busy, modifier = Modifier.fillMaxWidth()) { Text(strings.get(R.string.memory_add)) }
        error?.let {
            Text(strings.get(it), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { error = null; reload++ }, enabled = !busy) { Text(strings.get(R.string.memory_retry)) }
        }
        feedback?.let { Text(strings.get(it), Modifier.testTag(MemoryUiKeys.FEEDBACK)) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (page?.items?.isEmpty() == true) Text(strings.get(R.string.memory_empty))
        if (!loading) page?.items?.forEach { item ->
            key(item.id) {
                HorizontalDivider()
                Text(item.category, style = MaterialTheme.typography.labelLarge)
                Text(item.content, Modifier.fillMaxWidth().testTag(MemoryUiKeys.ENTRY))
                Text(strings.get(R.string.memory_source, item.sourceSessionId ?: strings.get(R.string.memory_manual)),
                    style = MaterialTheme.typography.bodySmall)
                // Vertical actions stay readable on small screens and at large font scales.
                TextButton(onClick = { editingId = item.id; content = item.content; editCategory = item.category
                    pinned = item.pinned; error = null; editing = true }, enabled = !busy) { Text(strings.get(R.string.memory_edit)) }
                TextButton(onClick = { mutate { store.pin(item.id, !item.pinned) } }, enabled = !busy) {
                    Text(strings.get(if (item.pinned) R.string.memory_unpin else R.string.memory_pin))
                }
                TextButton(onClick = { deleteId = item.id }, enabled = !busy) { Text(strings.get(R.string.memory_delete)) }
            }
        }
        if (offset > 0) OutlinedButton(onClick = { offset -= 20 }, enabled = !loading && !busy) { Text(strings.get(R.string.memory_previous)) }
        if (page?.more == true) OutlinedButton(onClick = { offset += 20 }, enabled = !loading && !busy) { Text(strings.get(R.string.memory_next)) }
        OutlinedButton(onClick = { clear = true }, enabled = !busy && (page?.usage?.count ?: 0) > 0,
            modifier = Modifier.fillMaxWidth()) { Text(strings.get(R.string.memory_clear)) }
    }
    if (clear || deleteId != null) AlertDialog(onDismissRequest = { if (!busy) { clear = false; deleteId = null } },
        title = { Text(strings.get(if (clear) R.string.memory_clear else R.string.memory_delete)) },
        text = { Column { Text(strings.get(if (clear) R.string.memory_clear_confirm else R.string.memory_delete_confirm))
            error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val all = clear; val id = deleteId
            mutate(R.string.memory_deleted) { if (all) store.clear() else store.delete(requireNotNull(id)) }
        }) { Text(strings.get(R.string.memory_confirm)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { clear = false; deleteId = null }) { Text(strings.get(R.string.memory_cancel)) } })
}
