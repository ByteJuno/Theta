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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.ui.plugins.PluginManifestEnvironmentSummary
import io.github.bytejuno.thetacore.hook.HookHost
import io.github.bytejuno.thetacore.plugin.AndroidHostEnvironmentDetector
import io.github.bytejuno.thetacore.plugin.HostEnvironmentStatus
import io.github.bytejuno.thetacore.plugin.ManagedPluginData
import io.github.bytejuno.thetacore.plugin.PluginDataPage
import io.github.bytejuno.thetacore.plugin.PluginImportRequests
import io.github.bytejuno.thetacore.plugin.StagedPluginData
import io.github.bytejuno.thetacore.plugin.ManagedPlugin
import io.github.bytejuno.thetacore.plugin.PluginManager
import io.github.bytejuno.thetacore.plugin.PluginCrashRecovery
import io.github.bytejuno.thetacore.plugin.PluginPackageException
import io.github.bytejuno.thetacore.plugin.StagedPlugin
import io.github.bytejuno.thetacore.plugin.detectHostEnvironmentSafely
import io.github.bytejuno.thetacore.plugin.openAccessibilitySettings
import io.github.bytejuno.thetacore.sdk.SettingsPage
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    private var resumed by mutableStateOf(false)
    private lateinit var pageLease: HostPageLease

    private companion object {
        val pageAttribution = HostPageAttribution()
    }

    override fun onResume() {
        pageLease.resume()
        resumed = true
        super.onResume()
    }

    override fun onPause() {
        resumed = false
        pageLease.pause()
        super.onPause()
    }

    override fun onDestroy() {
        pageLease.pause() // Idempotent, and cannot clear another Activity's attribution.
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        val host = application as ThetaApplication
        val manager = host.plugins
        val recovery = intent.getBooleanExtra("skip_plugins", false)
        pageLease = HostPageLease(pageAttribution, host.crashRecovery::enterPluginPage, host.crashRecovery::leavePluginPage)
        setContent {
            ThetaCoreApp(manager, incoming, recovery, host.crashRecovery, pageLease, resumed, onIncomingTaken = { incoming = null })
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
    crashRecovery: PluginCrashRecovery,
    pageLease: HostPageLease,
    resumed: Boolean,
    onIncomingTaken: () -> Unit,
) {
    var showPlugins by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(recovery || incoming != null) }
    LaunchedEffect(incoming) { if (incoming != null) showPlugins = true }
    val strings = uiStrings()
    val context = LocalContext.current
    val application = context.applicationContext as ThetaApplication
    // The plugin route replaces ChatScreen within this same Activity.
    val drafts = io.github.bytejuno.thetacore.chat.rememberChatComposerDrafts(application.chat)
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
    var toolSelection by remember { mutableStateOf<HostToolSelection?>(null) }
    var removingRejected by remember { mutableStateOf<String?>(null) }
    var clearData by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var staging by remember { mutableStateOf(false) }
    var restorePolicy by remember { mutableStateOf(crashRecovery.policy) }
    var resumeRequested by remember { mutableStateOf(crashRecovery.resumeRequested) }
    val environmentDetector = remember(context) { AndroidHostEnvironmentDetector(context) }
    var hostEnvironment by remember { mutableStateOf(HostEnvironmentStatus()) }
    var environmentRefreshSeq by remember { mutableIntStateOf(0) }
    val hookStatus by HookHost.status.collectAsState()
    LaunchedEffect(showPlugins, staged != null, resumed, hookStatus, environmentRefreshSeq) {
        if (showPlugins || staged != null) {
            hostEnvironment = detectHostEnvironmentSafely(environmentDetector)
        }
    }
    val launchAccessibilitySettings: () -> Unit = {
        if (!openAccessibilitySettings(context)) {
            error = uiText(R.string.plugin_env_settings_unavailable)
        }
    }

    fun leavePage() {
        pageLease.beforeRender(null)
        openPage = null
        dataPage = null
    }

    fun operate(action: suspend () -> Unit) {
        scope.launch {
            try {
                error = null
                action()
                restorePolicy = crashRecovery.policy
            } catch (e: PluginPackageException) {
                error = uiFailure(e)
            } catch (e: IOException) {
                error = uiFailure(e)
            } catch (e: SecurityException) {
                error = uiFailure(e)
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
        if (data && (manager.dataRecoveryBlocked || restorePolicy.skipData)) {
            error = uiText(R.string.data_import_recovery_blocked)
            return
        }
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
                if (requests.owns(request)) error = uiFailure(e)
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
            restorePolicy = crashRecovery.policy
        } catch (e: IOException) {
            error = uiFailure(e)
        } catch (e: PluginPackageException) {
            error = uiFailure(e)
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
    val selectedOwner = activeDataPage?.selection?.manifest?.id
        ?: activePage?.let { page -> manager.plugins.firstOrNull { it.pages.any { candidate -> candidate === page } }?.manifest?.id }
    // Journal BEFORE plugin Content, including a retained Activity returning from another
    // Activity. A stopped/non-owner host list must never clear the foreground journal.
    val pageAdmitted = resumed && pageLease.beforeRender(selectedOwner)

    io.github.bytejuno.thetacore.ui.theme.ThetaTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                if ((activeDataPage != null || activePage != null) && !pageAdmitted) {
                    Text(if (!resumed || !pageLease.ownsPage) strings.get(R.string.plugin_page_paused)
                        else strings.get(R.string.plugin_page_journal_blocked))
                    TextButton(onClick = { leavePage() }) { Text(strings.get(R.string.back_to_plugins)) }
                } else if (activeDataPage != null) {
                    BackHandler { leavePage() }
                    PluginDataPage(manager, activeDataPage) { leavePage() }
                } else if (activePage != null) {
                    BackHandler { leavePage() }
                    activePage.Content()
                } else if (!showPlugins) {
                    io.github.bytejuno.thetacore.chat.ChatScreen(application, drafts, resumed) { showPlugins = true }
                } else {
                    BackHandler { showPlugins = false }
                    io.github.bytejuno.thetacore.ui.plugins.PluginsScreen(
                        plugins = manager.plugins,
                        dataInventory = manager.dataInventory,
                        failures = manager.failures,
                        rejectedIds = manager.rejectedIds,
                        restorePolicy = restorePolicy,
                        resumeRequested = resumeRequested,
                        onBack = { showPlugins = false },
                        onResume = {
                            if (crashRecovery.requestResumeNextColdStart()) resumeRequested = true
                            else error = uiText(R.string.recovery_save_failed)
                        },
                        error = error,
                        recovery = recovery,
                        staging = staging,
                        dataRecoveryBlocked = manager.dataRecoveryBlocked || restorePolicy.skipData,
                        hostEnvironment = hostEnvironment,
                        onRefreshEnvironment = { environmentRefreshSeq++ },
                        onOpenAccessibility = launchAccessibilitySettings,
                        onTool = { toolSelection = it },
                        onCancelReading = ::cancelReading,
                        onRetryCleanup = { operate { manager.retryImportCleanup() } },
                        onOpen = { page ->
                            val owner = manager.plugins.firstOrNull { it.pages.any { candidate -> candidate === page } }
                            if (owner != null && pageLease.beforeRender(owner.manifest.id)) openPage = page
                            else error = uiText(R.string.page_journal_failed)
                        },
                        onPick = { picker.launch(arrayOf("*/*")) },
                        onPickData = { dataPicker.launch(arrayOf("*/*")) },
                        onOpenData = { page ->
                            if (pageLease.beforeRender(page.selection.manifest.id)) dataPage = page
                            else error = uiText(R.string.page_journal_failed)
                        },
                        onToggleData = { plugin -> operate { manager.setDataEnabled(plugin.id, !plugin.state.enabled) } },
                        onUninstallData = { dataUninstall = it.id },
                        onToggle = { plugin -> operate { manager.setEnabled(plugin.manifest.id, !plugin.enabled) } },
                        onUninstall = { plugin -> clearData = false; uninstalling = plugin },
                        onRollback = { rollingBack = it },
                        onRemoveRejected = { removingRejected = it },
                    )
                }
            }
            toolSelection?.let { selection ->
                HostToolDialog(manager, selection, onClose = { toolSelection = null })
            }
            importKindUri?.let { uri ->
                AlertDialog(
                    onDismissRequest = { importKindUri = null },
                    title = { Text(strings.get(R.string.import_kind_title)) },
                    text = { Text(strings.get(R.string.import_kind_notice)) },
                    confirmButton = { TextButton(enabled = !staging && !manager.dataRecoveryBlocked && !restorePolicy.skipData, onClick = { importKindUri = null; stage(uri, data = true) }) { Text(strings.get(R.string.data_zip)) } },
                    dismissButton = { TextButton(enabled = !staging, onClick = { importKindUri = null; stage(uri) }) { Text(strings.get(R.string.code_apk)) } },
                )
            }
            stagedData?.let { plugin ->
                val importSession = checkNotNull(requests.current).session
                var acknowledged by remember(plugin) { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { stagedData = null; operate { manager.discardImport(importSession) } },
                    title = { Text(strings.get(R.string.data_confirm_title)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(strings.get(R.string.package_metadata, plugin.manifest.name, plugin.manifest.id, plugin.manifest.version, plugin.manifest.versionCode, plugin.manifest.apiLevel))
                            Text(strings.get(
                                R.string.package_capabilities,
                                plugin.manifest.provides.takeIf { it.isNotEmpty() }?.joinToString() ?: strings.get(R.string.none),
                                plugin.manifest.declares.takeIf { it.isNotEmpty() }?.joinToString() ?: strings.get(R.string.none),
                            ))
                            Text(strings.get(R.string.plugin_env_none_required))
                            Text(strings.get(R.string.author_unverified))
                            Text(strings.get(R.string.digest_notice))
                            Text(strings.get(R.string.package_digest, plugin.sha256))
                            Text(strings.get(R.string.destinations_title))
                            plugin.destinations.forEach { destination ->
                                Text(strings.get(R.string.destination_metadata, destination.path, destination.url, destination.host))
                            }
                            Text(strings.get(R.string.data_capability_notice))
                            Row {
                                Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                                Text(strings.get(R.string.data_acknowledgement))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = acknowledged && !manager.dataRecoveryBlocked && !restorePolicy.skipData, onClick = {
                            stagedData = null
                            operate { manager.installData(importSession, plugin, plugin.sha256, acknowledged) }
                        }) { Text(strings.get(R.string.confirm_zip)) }
                    },
                    dismissButton = { TextButton(onClick = { stagedData = null; operate { manager.discardImport(importSession) } }) { Text(strings.get(R.string.cancel)) } },
                )
            }
            dataUninstall?.let { id ->
                AlertDialog(
                    onDismissRequest = { dataUninstall = null },
                    title = { Text(strings.get(R.string.uninstall_data_title, id)) },
                    text = { Text(strings.get(R.string.uninstall_data_notice)) },
                    confirmButton = { TextButton(onClick = { dataUninstall = null; operate { manager.uninstallData(id) } }) { Text(strings.get(R.string.uninstall_data)) } },
                    dismissButton = { TextButton(onClick = { dataUninstall = null }) { Text(strings.get(R.string.cancel)) } },
                )
            }
            staged?.let { plugin ->
                val importSession = checkNotNull(requests.current).session
                val installed = manager.plugins.firstOrNull { it.manifest.id == plugin.manifest.id }
                val isUpdate = installed != null && plugin.manifest.versionCode > installed.manifest.versionCode
                // This consent belongs to this exact staged confirmation, never to the ID.
                var allowCodeOnlyRollback by remember(plugin, importSession) { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { staged = null; operate { manager.discardImport(importSession) } },
                    title = { Text(stringResource(R.string.install_title, plugin.manifest.name, plugin.manifest.version)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(strings.get(R.string.install_activation_notice, plugin.manifest.id))
                            PluginManifestEnvironmentSummary(
                                manifest = plugin.manifest,
                                environment = hostEnvironment,
                                onOpenAccessibility = launchAccessibilitySettings,
                            )
                            Text(strings.get(R.string.rollback_data_boundary))
                            if (isUpdate) {
                                Text(strings.get(R.string.observation_start_notice))
                                Row {
                                    Checkbox(checked = allowCodeOnlyRollback, onCheckedChange = { allowCodeOnlyRollback = it })
                                    Text(strings.get(R.string.allow_code_only_rollback))
                                }
                                if (allowCodeOnlyRollback) Text(strings.get(R.string.code_only_warning), color = MaterialTheme.colorScheme.error)
                            }
                            Text(strings.get(R.string.integrity_notice))
                            Text(strings.get(R.string.signer_digest_title))
                            plugin.signers.sha256.forEach { Text(it) }
                            Text(strings.get(R.string.signer_binding_notice))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val codeOnlyConsent = isUpdate && allowCodeOnlyRollback
                            staged = null
                            operate { manager.install(plugin, importSession, allowCodeOnlyRollback = codeOnlyConsent) }
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
                    title = { Text(strings.get(R.string.rejected_uninstall_title, id)) },
                    text = { Text(strings.get(R.string.rejected_uninstall_notice)) },
                    confirmButton = {
                        TextButton(onClick = {
                            removingRejected = null
                            operate { manager.uninstallRejected(id) }
                        }) { Text(strings.get(R.string.delete_package_data)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { removingRejected = null }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
            rollingBack?.let { plugin ->
                AlertDialog(
                    onDismissRequest = { rollingBack = null },
                    title = { Text(strings.get(R.string.rollback_title, plugin.manifest.name)) },
                    text = {
                        Column {
                            val observation = plugin.updateObservation
                            Text(when (observation?.dataRestorable) {
                                false -> strings.get(R.string.rollback_code_only_detail)
                                true -> strings.get(R.string.rollback_normal_detail)
                                null -> strings.get(R.string.rollback_unavailable)
                            })
                            observation?.let { Text(strings.get(R.string.rollback_versions, it.to, it.from)) }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = plugin.canRollback && plugin.updateObservation != null && !plugin.busy && !plugin.pendingDelete, onClick = {
                            rollingBack = null
                            operate { manager.rollback(plugin.manifest.id) }
                        }) { Text(strings.get(R.string.confirm_rollback)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { rollingBack = null }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
            uninstalling?.let { plugin ->
                AlertDialog(
                    onDismissRequest = { uninstalling = null },
                    title = { Text(strings.get(R.string.uninstall_title, plugin.manifest.name)) },
                    text = {
                        Column {
                            Text(strings.get(R.string.uninstall_notice))
                            Row {
                                Checkbox(checked = clearData, onCheckedChange = { clearData = it })
                                Text(strings.get(R.string.delete_plugin_data))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val deleteData = clearData
                            uninstalling = null
                            operate { manager.uninstall(plugin.manifest.id, deleteData) }
                        }) { Text(strings.get(R.string.confirm_uninstall)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { uninstalling = null }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
        }
    }
}
