package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.uiFailure
import io.github.bytejuno.thetacore.UiText

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/** Only filenames and one bounded entry live in Compose; all validation/IO runs in the manager. */
@Composable
internal fun PluginDataPage(manager: PluginManager, page: ManagedPluginData, onBack: () -> Unit) {
    val strings = uiStrings()
    var path by remember(page) { mutableStateOf<String?>(null) }
    var content by remember(page) { mutableStateOf<PluginDataContent?>(null) }
    var error by remember(page) { mutableStateOf<UiText?>(null) }
    LaunchedEffect(page, path) {
        content = null
        try { content = manager.readDataPage(page, path) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = uiFailure(e) }
    }
    // Revocation hides retained text in the same composition; an old page never switches version.
    if (!manager.isDataPageActive(page)) return
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        TextButton(onClick = onBack) { Text(strings.get(R.string.back)) }
        Text(strings.get(R.string.package_name_version, page.selection.manifest.name, page.selection.manifest.version))
        Text(strings.get(R.string.data_plain_text_notice))
        error?.let { Text(strings.get(it)) }
        if (path == null) {
            LazyColumn {
                items(content?.paths.orEmpty()) { name ->
                    TextButton(onClick = { path = name }) { Text(name) }
                }
            }
        } else {
            TextButton(onClick = { path = null }) { Text(strings.get(R.string.files)) }
            Text(path.orEmpty())
            // Chunk long lines too, so a 1 MiB entry is not a single text layout.
            val chunks = remember(content) { content?.text?.chunked(4096).orEmpty() }
            LazyColumn { items(chunks) { Text(it) } }
        }
    }
}
