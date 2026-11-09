package io.github.bytejuno.thetacore.hook

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
        Text("S1 Modern Hook — dedicated ordinary target only")
        Text(status)
        Text("Full-trust code runs inside the target. Live disable depends on framework preference delivery; restart the target to exit Hook code. No custom target-to-Theta callback endpoint.")
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) {
            Text("Inspect signed Hook APK")
        }
        Button(enabled = !busy, onClick = {
            try { systemPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
            catch (error: Exception) { HookHost.fail(error) }
        }) { Text("Use system document picker") }
        reviewed?.let { value ->
            Text("${value.descriptor.id} · ${value.descriptor.version}\nTarget: ${value.descriptor.target}\nVerified signer SHA-256: ${value.signers}")
            Text("Confirming requests framework scope and publishes this exact payload. First signer is pinned; a different signer is rejected.")
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
            }) { Text("Request scope and publish reviewed APK") }
        }
        // Disable remains available during scope wait/publication. Capture intent on Main
        // without taking the remote-IO lock; an older approval cannot re-enable afterward.
        Button(onClick = {
            val action = HookHost.beginAction()
            scope.launch {
                try { withContext(Dispatchers.IO) { HookHost.disable(action) } }
                catch (error: Throwable) { HookHost.fail(error) }
            }
        }) { Text("Disable global Hook switch") }
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$HOOK_TARGET")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open target settings — force-stop, then relaunch") }
        Text("If Theta cannot open: disable Theta or remove target scope in the framework manager, then force-stop target. Last resort: disable the framework in root manager or uninstall Theta with adb. Startup quarantine is target/version-local; clearing target data resets it.")
    }
}
