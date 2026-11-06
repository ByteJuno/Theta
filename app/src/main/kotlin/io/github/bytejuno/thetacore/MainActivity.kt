package io.github.bytejuno.thetacore

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.plugin.ManagedPlugin
import io.github.bytejuno.thetacore.plugin.PluginManager
import io.github.bytejuno.thetacore.plugin.PluginPackageException
import io.github.bytejuno.thetacore.plugin.StagedPlugin
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.THETA_API_LEVEL
import java.io.IOException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        val manager = (application as ThetaApplication).plugins
        val recovery = intent.getBooleanExtra("skip_plugins", false)
        setContent {
            ThetaCoreApp(manager, incoming, recovery, onIncomingTaken = { incoming = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) incoming = intent.data
    }
}

@Composable
private fun ThetaCoreApp(
    manager: PluginManager,
    incoming: Uri?,
    recovery: Boolean,
    onIncomingTaken: () -> Unit,
) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val scope = rememberCoroutineScope()
    var openPage by remember { mutableStateOf<SettingsPage?>(null) }
    var staged by remember { mutableStateOf<StagedPlugin?>(null) }
    var uninstalling by remember { mutableStateOf<ManagedPlugin?>(null) }
    var clearData by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var staging by remember { mutableStateOf(false) }

    fun operate(action: suspend () -> Unit) {
        scope.launch {
            try {
                error = null
                action()
            } catch (e: PluginPackageException) {
                error = e.message
            } catch (e: IOException) {
                error = e.message
            } catch (e: SecurityException) {
                error = e.message ?: "Cannot access the selected file"
            }
        }
    }

    fun stage(uri: Uri) {
        if (staging) return
        staging = true
        operate {
            try {
                staged?.file?.delete()
                staged = null
                staged = manager.stage(uri)
            } finally {
                staging = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::stage)
    }
    LaunchedEffect(Unit) {
        try {
            manager.restore(skipPlugins = recovery)
        } catch (e: IOException) {
            error = e.message
        } catch (e: PluginPackageException) {
            error = e.message
        }
    }
    LaunchedEffect(incoming) {
        incoming?.let {
            onIncomingTaken()
            stage(it)
        }
    }

    // A revoked extension must never keep rendering merely because the UI retained its object.
    val activePage = openPage?.takeIf { page -> manager.plugins.any { it.pages.any { p -> p === page } } }
    LaunchedEffect(activePage) { if (activePage == null) openPage = null }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                if (activePage != null) {
                    BackHandler { openPage = null }
                    activePage.Content()
                } else {
                    PluginList(
                        manager = manager,
                        error = error,
                        recovery = recovery,
                        staging = staging,
                        onOpen = { openPage = it },
                        onPick = { picker.launch(arrayOf("*/*")) },
                        onToggle = { plugin -> operate { manager.setEnabled(plugin.manifest.id, !plugin.enabled) } },
                        onUninstall = { plugin -> clearData = false; uninstalling = plugin },
                    )
                }
            }
            staged?.let { plugin ->
                AlertDialog(
                    onDismissRequest = { plugin.file.delete(); staged = null },
                    title = { Text(stringResource(R.string.install_title, plugin.manifest.name, plugin.manifest.version)) },
                    text = { Text("${plugin.manifest.id}\nInstall and enable. Updates take effect after restarting the app.") },
                    confirmButton = {
                        TextButton(onClick = {
                            staged = null
                            operate { manager.install(plugin) }
                        }) { Text(stringResource(R.string.install)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { plugin.file.delete(); staged = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
            uninstalling?.let { plugin ->
                AlertDialog(
                    onDismissRequest = { uninstalling = null },
                    title = { Text("Uninstall ${plugin.manifest.name}?") },
                    text = {
                        Column {
                            Text("Host extensions are removed immediately. Loaded code and files may remain until the app restarts.")
                            Row {
                                Checkbox(checked = clearData, onCheckedChange = { clearData = it })
                                Text("Delete plugin data")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val deleteData = clearData
                            uninstalling = null
                            operate { manager.uninstall(plugin.manifest.id, deleteData) }
                        }) { Text("Confirm uninstall") }
                    },
                    dismissButton = {
                        TextButton(onClick = { uninstalling = null }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun PluginList(
    manager: PluginManager,
    error: String?,
    recovery: Boolean,
    staging: Boolean,
    onOpen: (SettingsPage) -> Unit,
    onPick: () -> Unit,
    onToggle: (ManagedPlugin) -> Unit,
    onUninstall: (ManagedPlugin) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.api_level, THETA_API_LEVEL))
        if (recovery) Text("Recovery requested. To skip all plugins, launch recovery after force-stopping the app.")
        Button(onClick = onPick, enabled = !staging) { Text(stringResource(R.string.install_plugin)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        manager.failures.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        if (manager.plugins.isEmpty()) Text(stringResource(R.string.no_plugins))
        manager.plugins.forEach { plugin ->
            Text("${plugin.manifest.name} · ${plugin.manifest.version}", style = MaterialTheme.typography.titleMedium)
            Text(plugin.status)
            Row {
                TextButton(
                    onClick = { onToggle(plugin) },
                    enabled = !plugin.busy && !plugin.pendingDelete,
                ) { Text("${if (plugin.enabled) "Disable" else "Enable"} ${plugin.manifest.name}") }
                TextButton(
                    onClick = { onUninstall(plugin) },
                    enabled = !plugin.busy && !plugin.pendingDelete,
                ) { Text("Uninstall ${plugin.manifest.name}") }
            }
            plugin.pages.forEach { page ->
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(page) }.padding(vertical = 12.dp),
                )
            }
        }
    }
}
