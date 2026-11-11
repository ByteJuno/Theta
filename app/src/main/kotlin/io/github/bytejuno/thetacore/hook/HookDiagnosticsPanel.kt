package io.github.bytejuno.thetacore.hook

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Embed in host UI. No publication or scope request occurs just by displaying this panel. */
@Composable
fun HookDiagnosticsPanel() {
    val strings = uiStrings()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val status by HookHost.status.collectAsState()
    var reviewed by remember { mutableStateOf<HookImport?>(null) }
    var busy by remember { mutableStateOf(false) }
    val inspectSelection: (Uri?) -> Unit = { uri ->
        if (uri != null && !busy) {
            busy = true
            reviewed = null
            scope.launch {
                try { reviewed = withContext(Dispatchers.IO) { HookHost.inspect(context, uri) } }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Throwable) { HookHost.fail(error) }
                finally { busy = false }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), inspectSelection)
    val systemPicker = rememberLauncherForActivityResult(HookDocumentPicker(), inspectSelection)
    LaunchedEffect(Unit) {
        try { HookHost.connect() }
        catch (error: Throwable) { HookHost.fail(error) }
    }
    Column {
        Text(strings.get(R.string.hook_title))
        Text(strings.get(status))
        Text(strings.get(R.string.hook_trust_notice))
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) {
            Text(strings.get(R.string.inspect_hook_apk))
        }
        Button(enabled = !busy, onClick = {
            try { systemPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
            catch (error: Exception) { HookHost.fail(error) }
        }) { Text(strings.get(R.string.system_document_picker)) }
        reviewed?.let { value ->
            Text(strings.get(R.string.hook_package_metadata, value.descriptor.id, value.descriptor.version, value.descriptor.target, value.signers))
            Text(strings.get(R.string.hook_publish_notice))
            Button(enabled = !busy, onClick = {
                busy = true
                val action = HookHost.beginAction()
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val grant = HookHost.requestScope(value.descriptor.target, action)
                            currentCoroutineContext().ensureActive()
                            HookHost.publish(context, value, grant)
                        }
                    } catch (error: Throwable) { HookHost.fail(error) }
                    finally { busy = false }
                }
            }) { Text(strings.get(R.string.publish_hook_apk)) }
        }
        // Disable remains available during scope wait/publication. Capture intent on Main
        // without taking the remote-IO lock; an older approval cannot re-enable afterward.
        Button(onClick = {
            val action = HookHost.beginAction()
            scope.launch {
                try { withContext(Dispatchers.IO) { HookHost.disable(action) } }
                catch (error: Throwable) { HookHost.fail(error) }
            }
        }) { Text(strings.get(R.string.disable_hook)) }
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$HOOK_TARGET")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(strings.get(R.string.target_settings)) }
        Text(strings.get(R.string.hook_recovery_notice))
    }
}
