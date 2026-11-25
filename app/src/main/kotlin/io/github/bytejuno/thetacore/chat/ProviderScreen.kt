package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.provider.openai.MAX_SYSTEM_PROMPT_CHARS
import io.github.bytejuno.thetacore.agent.provider.openai.ModelsRequestException
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import kotlinx.coroutines.*

@Composable
internal fun ProviderScreen(host: ThetaApplication, onClose: () -> Unit) {
    var imageProfile by remember { mutableStateOf(false) }
    // A profile-kind switch disposes every field and cancels its load/fetch scope, even on switch-back.
    key(imageProfile) {
        ProviderProfile(host, imageProfile, { imageProfile = it }, onClose)
    }
}

internal fun providerFailureText(failure: ProviderSettingsException.Failure): Int = when (failure) {
    ProviderSettingsException.Failure.NOT_CONFIGURED -> R.string.chat_configure_first
    ProviderSettingsException.Failure.DECRYPT_FAILED -> R.string.chat_credentials_unavailable
    ProviderSettingsException.Failure.ENDPOINT_CHANGED -> R.string.chat_provider_new_key
    ProviderSettingsException.Failure.READ_FAILED, ProviderSettingsException.Failure.INVALID_DATA -> R.string.chat_provider_read_failed
    ProviderSettingsException.Failure.WRITE_FAILED -> R.string.chat_provider_failed
    ProviderSettingsException.Failure.CATALOG_RECOVERED -> R.string.chat_provider_catalog_recovered
}

@Composable
private fun ProviderProfile(
    host: ThetaApplication,
    imageProfile: Boolean,
    onProfile: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    val store = if (imageProfile) host.imageSettings else host.providerSettings
    val editor = remember { ProviderSettingsEditor() }
    var form by editor::form
    // Only a newly typed key can enter this non-saveable field.
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    var approvalSettingsOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val modelLists = remember(scope, store, imageProfile) {
        ProviderModels(scope) { endpoint, key ->
            if (imageProfile) store.fetchModels(endpoint, key)
            else store.fetchModels(endpoint, key, editor.form.profileId, editor.form.noAuth)
        }
    }
    val discovery by modelLists.state.collectAsState()
    DisposableEffect(modelLists) { onDispose { modelLists.invalidate() } }
    LaunchedEffect(store, imageProfile, reload) { editor.load(store, imageProfile) }
    fun resetModels() { modelLists.invalidate(); filter = "" }
    fun clearTransientInputs() { resetModels(); secret = ""; error = null }
    val editable = !busy && editor.editable
    val usingNoAuth = !imageProfile && form.noAuth
    val hasKey = usingNoAuth || secret.isNotEmpty() || form.canReuseKey
    AlertDialog(
        onDismissRequest = { if (!busy) { resetModels(); onClose() } },
        title = { Text(strings.get(R.string.chat_provider)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.get(R.string.chat_provider_notice))
                FlowRow {
                    TextButton(enabled = !busy && imageProfile, onClick = { clearTransientInputs(); onProfile(false) }) {
                        Text(strings.get(R.string.chat_text_profile))
                    }
                    TextButton(enabled = !busy && !imageProfile, onClick = { clearTransientInputs(); onProfile(true) }) {
                        Text(strings.get(R.string.chat_image_profile))
                    }
                }
                Text(
                    strings.get(if (imageProfile) R.string.chat_image_profile else R.string.chat_text_profile),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (imageProfile) Text(strings.get(R.string.chat_image_settings_notice))
                if (editor.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(strings.get(R.string.chat_provider_loading))
                }
                editor.loadError?.let {
                    Text(strings.get(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text(strings.get(R.string.chat_provider_reload)) }
                    TextButton(onClick = {
                        clearTransientInputs()
                        editor.beginReplacement()
                    }) { Text(strings.get(R.string.chat_provider_reenter)) }
                }
                if (editor.replacingUnreadable) {
                    Text(strings.get(R.string.chat_provider_reenter_notice), style = MaterialTheme.typography.bodySmall)
                    if (!imageProfile) {
                        Text(
                            strings.get(R.string.chat_provider_replace_catalog_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (!imageProfile && editor.editable && !editor.replacingUnreadable) {
                    if (editor.profiles.isNotEmpty()) {
                        Text(strings.get(R.string.chat_profiles_title), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            editor.profiles.forEach { profile ->
                                val isEditing = !editor.creatingNew && form.profileId == profile.id
                                FilterChip(
                                    selected = isEditing,
                                    enabled = editable,
                                    onClick = {
                                        clearTransientInputs()
                                        editor.selectProfileForEdit(profile.id)
                                    },
                                    label = {
                                        Text(
                                            text = if (profile.id == editor.activeProfileId) {
                                                strings.get(R.string.chat_profile_chip_active, profile.displayName)
                                            } else {
                                                profile.displayName
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                            AssistChip(
                                onClick = {
                                    clearTransientInputs()
                                    editor.startNewProfile()
                                },
                                enabled = editable && !editor.creatingNew,
                                label = { Text(strings.get(R.string.chat_profile_new)) },
                            )
                        }
                        val editingSavedId = form.profileId
                        if (editingSavedId != null && !editor.creatingNew) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (editingSavedId != editor.activeProfileId) {
                                    OutlinedButton(enabled = editable, onClick = {
                                        clearTransientInputs(); busy = true
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { store.selectActiveProfile(editingSavedId) }
                                                editor.load(store, imageProfile = false, preferredProfileId = editingSavedId)
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: ProviderSettingsException) { error = providerFailureText(failure.failure) }
                                            catch (_: Exception) { error = R.string.chat_provider_failed }
                                            finally { busy = false }
                                        }
                                    }) { Text(strings.get(R.string.chat_profile_activate)) }
                                }
                                TextButton(enabled = editable, onClick = {
                                    clearTransientInputs(); busy = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { store.deleteProfile(editingSavedId) }
                                            editor.load(store, imageProfile = false)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: ProviderSettingsException) { error = providerFailureText(failure.failure) }
                                        catch (_: Exception) { error = R.string.chat_provider_failed }
                                        finally { busy = false }
                                    }
                                }) { Text(strings.get(R.string.chat_profile_delete)) }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = form.profileName,
                        onValueChange = { error = null; form = form.withProfileName(it) },
                        enabled = editable,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.get(R.string.chat_profile_name)) },
                    )
                    Text(strings.get(R.string.chat_profile_name_notice), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = form.endpoint,
                    onValueChange = { resetModels(); error = null; form = form.copy(endpoint = it) },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.get(R.string.chat_endpoint)) },
                )
                if (!imageProfile) {
                    ProviderToggle(R.string.chat_provider_no_auth, form.noAuth, editable) { checked ->
                        clearTransientInputs()
                        form = form.copy(noAuth = checked)
                    }
                }
                if (!usingNoAuth) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { resetModels(); error = null; secret = it },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.get(R.string.chat_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (form.saved != null) {
                        Text(
                            strings.get(
                                if (form.canReuseKey) R.string.chat_provider_saved_key else R.string.chat_provider_new_key,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(strings.get(R.string.chat_provider_no_auth_notice), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { error = null; form = form.copy(model = it) },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.get(R.string.chat_model)) },
                )
                Text(strings.get(R.string.chat_models_manual), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = editable && !discovery.loading && form.endpoint.isNotBlank() && hasKey,
                    onClick = {
                        filter = ""
                        modelLists.load(form.endpoint.trim(), if (usingNoAuth) "" else secret, form.canReuseKey, usingNoAuth)
                    },
                ) {
                    Text(strings.get(if (discovery.loading) R.string.chat_models_loading else R.string.chat_models_fetch))
                }
                discovery.settingsFailure?.let {
                    Text(strings.get(providerFailureText(it)), color = MaterialTheme.colorScheme.error)
                }
                discovery.failure?.let { failure ->
                    Text(strings.get(when (failure) {
                        ModelsRequestException.Failure.INVALID_SETTINGS -> R.string.chat_models_invalid_settings
                        ModelsRequestException.Failure.HTTP -> R.string.chat_models_http_failed
                        ModelsRequestException.Failure.TIMEOUT -> R.string.chat_models_timeout
                        ModelsRequestException.Failure.NETWORK -> R.string.chat_models_network_failed
                        ModelsRequestException.Failure.TOO_LARGE -> R.string.chat_models_too_large
                        ModelsRequestException.Failure.INVALID_RESPONSE -> R.string.chat_models_invalid_response
                    }), color = MaterialTheme.colorScheme.error)
                }
                discovery.models?.let { models ->
                    if (models.isEmpty()) Text(strings.get(R.string.chat_models_empty))
                    else {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            enabled = editable,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.get(R.string.chat_models_filter)) },
                        )
                        val matches = remember(models, filter) { models.filter { it.contains(filter, ignoreCase = true) } }
                        if (matches.isEmpty()) Text(strings.get(R.string.chat_models_no_matches))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            items(matches, key = { it }) { name ->
                                TextButton(
                                    enabled = editable,
                                    onClick = { form = form.copy(model = name) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                if (!imageProfile) {
                    OutlinedTextField(
                        value = form.systemPrompt,
                        onValueChange = { error = null; form = form.withSystemPrompt(it) },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        label = { Text(strings.get(R.string.chat_system_prompt)) },
                    )
                    Text(
                        strings.get(R.string.chat_system_prompt_notice, MAX_SYSTEM_PROMPT_CHARS),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { advanced = !advanced }, enabled = editable) {
                        Text(strings.get(if (advanced) R.string.chat_compat_hide else R.string.chat_compat_show))
                    }
                    if (advanced) {
                        ProviderToggle(R.string.chat_compat_cache, form.explicitCacheControl, editable) {
                            form = form.copy(explicitCacheControl = it)
                        }
                        Text(strings.get(R.string.chat_compat_cache_notice), style = MaterialTheme.typography.bodySmall)
                        Text(strings.get(R.string.chat_compat_tokens), style = MaterialTheme.typography.labelLarge)
                        // Stack long protocol identifiers so narrow screens need no horizontal scrolling.
                        for (field in OutputTokenField.entries) {
                            FilterChip(
                                selected = form.maxTokensField == field.wire,
                                enabled = editable,
                                onClick = { form = form.copy(maxTokensField = field.wire) },
                                label = { Text(strings.get(field.label)) },
                            )
                        }
                        ProviderToggle(R.string.chat_compat_usage, form.streamUsage, editable) {
                            form = form.copy(streamUsage = it)
                        }
                        OutlinedTextField(
                            value = form.reasoningField,
                            onValueChange = { error = null; form = form.withReasoningField(it) },
                            enabled = editable,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.get(R.string.chat_compat_reasoning)) },
                        )
                        Text(strings.get(R.string.chat_compat_reasoning_notice), style = MaterialTheme.typography.bodySmall)
                        ProviderToggle(
                            R.string.chat_compat_replay,
                            form.replayReasoning,
                            editable && form.reasoningField.isNotBlank(),
                        ) { form = form.copy(replayReasoning = it) }
                    }
                }
                error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
                if (!imageProfile) {
                    OutlinedButton(onClick = { approvalSettingsOpen = true }, enabled = !busy) {
                        Text(strings.get(R.string.chat_approval_settings))
                    }
                }
                AppLanguageSetting()
            }
        },
        confirmButton = {
            Button(enabled = editable && form.endpoint.isNotBlank() && form.model.isNotBlank() && hasKey, onClick = {
                resetModels(); busy = true; error = null
                val draft = form
                val newKey = secret
                val replaceUnreadable = editor.replacingUnreadable
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val settings = draft.settings(if (imageProfile) IMAGE_PROVIDER_ID else CHAT_PROVIDER_ID, imageProfile)
                            if (imageProfile) {
                                if (newKey.isEmpty()) store.updateSettings(settings) else store.replace(settings, newKey)
                            } else {
                                store.saveProfile(
                                    profileId = draft.profileId,
                                    displayName = draft.profileName,
                                    settings = settings,
                                    apiKey = if (draft.noAuth) "" else newKey,
                                    noAuth = draft.noAuth,
                                    activate = true,
                                    replaceUnreadable = replaceUnreadable,
                                )
                            }
                        }
                        secret = ""
                        onClose()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: ProviderSettingsException) {
                        if (!imageProfile && failure.failure == ProviderSettingsException.Failure.CATALOG_RECOVERED) {
                            secret = ""
                            editor.load(store, imageProfile = false)
                        }
                        error = providerFailureText(failure.failure)
                    }
                    catch (_: IllegalArgumentException) { error = R.string.chat_provider_invalid }
                    catch (_: Exception) { error = R.string.chat_provider_failed }
                    finally { busy = false }
                }
            }) { Text(strings.get(if (busy) R.string.chat_provider_saving else R.string.chat_save)) }
        },
        dismissButton = {
            TextButton(onClick = { resetModels(); onClose() }, enabled = !busy) {
                Text(strings.get(R.string.chat_close))
            }
        },
    )
    if (approvalSettingsOpen) ChatApprovalDialog(host) { approvalSettingsOpen = false }
}

@Composable
private fun ProviderToggle(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiStrings().get(label), Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
