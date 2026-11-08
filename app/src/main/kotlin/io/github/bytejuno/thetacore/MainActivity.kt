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
import androidx.compose.runtime.DisposableEffect
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
import io.github.bytejuno.thetacore.plugin.ManagedPluginData
import io.github.bytejuno.thetacore.plugin.PluginDataPage
import io.github.bytejuno.thetacore.plugin.PluginImportRequests
import io.github.bytejuno.thetacore.plugin.ManagedDataRecord
import io.github.bytejuno.thetacore.plugin.StagedPluginData
import io.github.bytejuno.thetacore.plugin.ManagedPlugin
import io.github.bytejuno.thetacore.plugin.PluginManager
import io.github.bytejuno.thetacore.plugin.PluginPackageException
import io.github.bytejuno.thetacore.plugin.StagedPlugin
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.THETA_API_LEVEL
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException

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
    val requests = remember(manager) { PluginImportRequests(manager::closeImport) }
    DisposableEffect(requests) { onDispose { requests.cancel() } }
    var dataPage by remember { mutableStateOf<ManagedPluginData?>(null) }
    var stagedData by remember { mutableStateOf<StagedPluginData?>(null) }
    var importKindUri by remember { mutableStateOf<Uri?>(null) }
    var dataUninstall by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf<StagedPlugin?>(null) }
    var uninstalling by remember { mutableStateOf<ManagedPlugin?>(null) }
    var rollingBack by remember { mutableStateOf<ManagedPlugin?>(null) }
    var removingRejected by remember { mutableStateOf<String?>(null) }
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

    fun cancelReading() {
        requests.cancel() // retire UI identity before cancelling old Job/source
        staging = false
        staged = null
        stagedData = null
    }

    fun stage(uri: Uri, data: Boolean = false) {
        if (staging) return
        val request = requests.begin()
        staging = true
        staged = null
        stagedData = null
        error = null
        request.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (data) {
                    val value = manager.stageData(uri, request.session)
                    if (requests.owns(request)) stagedData = value
                } else {
                    val value = manager.stage(uri, request.session)
                    if (requests.owns(request)) staged = value
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (requests.owns(request)) error = e.message ?: "Import failed"
            } finally {
                if (requests.finishReading(request)) staging = false
            }
        }
        request.job?.start()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { stage(it) }
    }
    val dataPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { stage(it, data = true) }
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
            importKindUri = it
        }
    }

    // A revoked extension must never keep rendering merely because the UI retained its object.
    val activePage = openPage?.takeIf { page -> manager.plugins.any { it.pages.any { p -> p === page } } }
    LaunchedEffect(activePage) { if (activePage == null) openPage = null }

    val activeDataPage = dataPage?.takeIf { manager.isDataPageActive(it) }
    LaunchedEffect(activeDataPage) { if (activeDataPage == null) dataPage = null }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                if (activeDataPage != null) {
                    BackHandler { dataPage = null }
                    PluginDataPage(manager, activeDataPage) { dataPage = null }
                } else if (activePage != null) {
                    BackHandler { openPage = null }
                    activePage.Content()
                } else {
                    PluginList(
                        manager = manager,
                        error = error,
                        recovery = recovery,
                        staging = staging,
                        onCancelReading = ::cancelReading,
                        onRetryCleanup = { operate { manager.retryImportCleanup() } },
                        onOpen = { openPage = it },
                        onPick = { picker.launch(arrayOf("*/*")) },
                        onPickData = { dataPicker.launch(arrayOf("*/*")) },
                        onOpenData = { dataPage = it },
                        onToggleData = { plugin -> operate { manager.setDataEnabled(plugin.id, !plugin.state.enabled) } },
                        onUninstallData = { dataUninstall = it.id },
                        onToggle = { plugin -> operate { manager.setEnabled(plugin.manifest.id, !plugin.enabled) } },
                        onUninstall = { plugin -> clearData = false; uninstalling = plugin },
                        onRollback = { rollingBack = it },
                        onRemoveRejected = { removingRejected = it },
                    )
                }
            }
            importKindUri?.let { uri ->
                AlertDialog(
                    onDismissRequest = { importKindUri = null },
                    title = { Text("Choose package kind before import") },
                    text = { Text("APK and data ZIP are separate routes. Failed APK verification never falls back to data.") },
                    confirmButton = { TextButton(enabled = !staging, onClick = { importKindUri = null; stage(uri, data = true) }) { Text("Data ZIP") } },
                    dismissButton = { TextButton(enabled = !staging, onClick = { importKindUri = null; stage(uri) }) { Text("Code APK") } },
                )
            }
            stagedData?.let { plugin ->
                val importSession = checkNotNull(requests.current).session
                var acknowledged by remember(plugin) { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { stagedData = null; operate { manager.discardImport(importSession) } },
                    title = { Text("Confirm data ZIP / unsigned update") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("Name: ${plugin.manifest.name}\nID: ${plugin.manifest.id}\nVersion: ${plugin.manifest.version} (${plugin.manifest.versionCode})\nAPI: ${plugin.manifest.apiLevel}")
                            Text("Provides: ${plugin.manifest.provides.joinToString()}\nDeclares: ${plugin.manifest.declares.joinToString()}")
                            Text(plugin.authorNotice)
                            Text(plugin.digestNotice)
                            Text("SHA-256: ${plugin.sha256}")
                            Text("Destinations (metadata only):")
                            plugin.destinations.forEach { destination ->
                                Text("${destination.path}\nURL: ${destination.url}\nHost: ${destination.host}")
                            }
                            Text("Only a host-owned read-only content page is enabled. No provider engine, MCP connection/auth, Skill execution, persona application or language override. Updates replace the page snapshot, not an agent run. Existing disabled state is preserved.")
                            Row {
                                Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                                Text("I reviewed this exact digest and every full destination; author identity is unverified.")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = acknowledged, onClick = {
                            stagedData = null
                            operate { manager.installData(importSession, plugin, plugin.sha256, acknowledged) }
                        }) { Text("Confirm this ZIP") }
                    },
                    dismissButton = { TextButton(onClick = { stagedData = null; operate { manager.discardImport(importSession) } }) { Text("Cancel") } },
                )
            }
            dataUninstall?.let { id ->
                AlertDialog(
                    onDismissRequest = { dataUninstall = null },
                    title = { Text("Uninstall data $id?") },
                    text = { Text("Revoke its host page and delete archives. ID ownership is retained, including after restart; code reuse and explicit release are not offered. No plugin private data was initialized.") },
                    confirmButton = { TextButton(onClick = { dataUninstall = null; operate { manager.uninstallData(id) } }) { Text("Uninstall data") } },
                    dismissButton = { TextButton(onClick = { dataUninstall = null }) { Text("Cancel") } },
                )
            }
            staged?.let { plugin ->
                val importSession = checkNotNull(requests.current).session
                AlertDialog(
                    onDismissRequest = { staged = null; operate { manager.discardImport(importSession) } },
                    title = { Text(stringResource(R.string.install_title, plugin.manifest.name, plugin.manifest.version)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("${plugin.manifest.id}\nInstall and enable. Updates take effect after restarting the app.")
                            Text("A rollback only restores this plugin's data directory. Other changes it made stay.")
                            Text("Integrity verified; author identity not authenticated.")
                            Text("Current signer certificate SHA-256:")
                            plugin.signers.sha256.forEach { Text(it) }
                            Text("This signer set is pinned until an explicit clear-data uninstall. Plugins execute with the host's full privileges.")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            staged = null
                            operate { manager.install(plugin, importSession) }
                        }) { Text(stringResource(R.string.install)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { staged = null; operate { manager.discardImport(importSession) } }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
            removingRejected?.let { id ->
                AlertDialog(
                    onDismissRequest = { removingRejected = null },
                    title = { Text("Clear-data uninstall $id?") },
                    text = { Text("Delete this rejected package, all its data and its signer binding. This does not trust or run the package.") },
                    confirmButton = {
                        TextButton(onClick = {
                            removingRejected = null
                            operate { manager.uninstallRejected(id) }
                        }) { Text("Delete package and data") }
                    },
                    dismissButton = {
                        TextButton(onClick = { removingRejected = null }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
            rollingBack?.let { plugin ->
                AlertDialog(
                    onDismissRequest = { rollingBack = null },
                    title = { Text("Roll back ${plugin.manifest.name}?") },
                    text = { Text("After a restart the previous version returns with its data from before the update. Anything this plugin saved since then is lost.") },
                    confirmButton = {
                        TextButton(onClick = {
                            rollingBack = null
                            operate { manager.rollback(plugin.manifest.id) }
                        }) { Text("Confirm rollback") }
                    },
                    dismissButton = {
                        TextButton(onClick = { rollingBack = null }) { Text(stringResource(R.string.cancel)) }
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
    onCancelReading: () -> Unit,
    onRetryCleanup: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onPick: () -> Unit,
    onPickData: () -> Unit,
    onOpenData: (ManagedPluginData) -> Unit,
    onToggleData: (ManagedDataRecord) -> Unit,
    onUninstallData: (ManagedDataRecord) -> Unit,
    onToggle: (ManagedPlugin) -> Unit,
    onUninstall: (ManagedPlugin) -> Unit,
    onRollback: (ManagedPlugin) -> Unit,
    onRemoveRejected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.api_level, THETA_API_LEVEL))
        if (recovery) Text("Recovery requested. To skip all plugins, launch recovery after force-stopping the app.")
        Button(onClick = onPick, enabled = !staging) { Text("Import code APK") }
        Button(onClick = onPickData, enabled = !staging) { Text("Import data ZIP") }
        if (staging) TextButton(onClick = onCancelReading) { Text("Cancel reading") }
        TextButton(onClick = onRetryCleanup) { Text("Retry import cleanup") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        manager.failures.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        manager.rejectedIds.forEach { id ->
            TextButton(onClick = { onRemoveRejected(id) }) { Text("Clear-data uninstall $id") }
        }
        Text("Data packages", style = MaterialTheme.typography.titleLarge)
        Text("Uninstall retains data ID ownership; cross-type reuse is unavailable.")
        if (manager.dataInventory.isEmpty()) Text("No selected data packages")
        manager.dataInventory.forEach { plugin ->
            val manifest = plugin.record.selection?.manifest
            Text(manifest?.let { "${it.name} · ${it.version}" }
                ?: "${plugin.id} · recorded version ${plugin.state.version}")
            Text(plugin.id)
            Text(plugin.status)
            if (manifest == null) Text("Recorded SHA-256: ${plugin.state.sha256}")
            Row {
                TextButton(enabled = !plugin.state.pendingDelete && (plugin.state.enabled || plugin.canEnable),
                    onClick = { onToggleData(plugin) }) {
                    Text(if (plugin.state.enabled) "Disable data" else "Enable data")
                }
                TextButton(onClick = { onUninstallData(plugin) }) {
                    Text(if (plugin.state.pendingDelete) "Retry uninstall data" else "Uninstall data")
                }
            }
            plugin.page?.takeIf { it.active }?.let { page ->
                TextButton(onClick = { onOpenData(page) }) { Text("View data content") }
            }
        }
        Text("Code packages", style = MaterialTheme.typography.titleLarge)
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
            if (plugin.canRollback) {
                TextButton(
                    onClick = { onRollback(plugin) },
                    enabled = !plugin.busy && !plugin.pendingDelete,
                ) { Text("Roll back ${plugin.manifest.name}") }
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
