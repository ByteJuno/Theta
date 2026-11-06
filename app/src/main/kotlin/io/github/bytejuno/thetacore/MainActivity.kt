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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
        setContent {
            ThetaCoreApp(manager, incoming, onIncomingTaken = { incoming = null })
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
private fun ThetaCoreApp(manager: PluginManager, incoming: Uri?, onIncomingTaken: () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val scope = rememberCoroutineScope()
    var openPage by remember { mutableStateOf<SettingsPage?>(null) }
    var staged by remember { mutableStateOf<StagedPlugin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun stage(uri: Uri) {
        scope.launch {
            try {
                error = null
                staged = manager.stage(uri)
            } catch (e: PluginPackageException) {
                error = e.message
            } catch (e: IOException) {
                error = e.message
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::stage)
    }

    LaunchedEffect(Unit) { manager.restore() }
    LaunchedEffect(incoming) {
        incoming?.let {
            onIncomingTaken()
            stage(it)
        }
    }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                val page = openPage
                if (page != null) {
                    BackHandler { openPage = null }
                    page.Content()
                } else {
                    PluginList(
                        manager = manager,
                        error = error,
                        onOpen = { openPage = it },
                        onPick = { picker.launch(arrayOf("*/*")) },
                    )
                }
            }
            staged?.let { plugin ->
                AlertDialog(
                    onDismissRequest = {
                        plugin.file.delete()
                        staged = null
                    },
                    title = {
                        Text(stringResource(R.string.install_title, plugin.manifest.name, plugin.manifest.version))
                    },
                    text = { Text(plugin.manifest.id) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                staged = null
                                scope.launch {
                                    try {
                                        manager.install(plugin)
                                    } catch (e: PluginPackageException) {
                                        error = e.message
                                    } catch (e: IOException) {
                                        error = e.message
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.install))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                plugin.file.delete()
                                staged = null
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
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
    onOpen: (SettingsPage) -> Unit,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.api_level, THETA_API_LEVEL))
        Button(onClick = onPick) {
            Text(stringResource(R.string.install_plugin))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        manager.failures.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        if (manager.plugins.isEmpty()) {
            Text(stringResource(R.string.no_plugins))
        }
        manager.plugins.forEach { plugin ->
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
