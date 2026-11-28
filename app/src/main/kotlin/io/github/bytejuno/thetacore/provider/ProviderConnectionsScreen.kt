package io.github.bytejuno.thetacore.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.plugin.ProviderTemplateProtocol
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.chat.*
import io.github.bytejuno.thetacore.plugin.ProviderTemplate
import kotlinx.coroutines.*
import java.util.UUID

/** Non-saveable editor: typed secrets are discarded when the dialog is disposed. */
@Composable
internal fun ProviderConnectionsScreen(host: ThetaApplication, onClose: () -> Unit) {
    val strings = uiStrings()
    val store = host.providerSettings
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf(ProviderCatalog()) }
    var templates by remember { mutableStateOf<List<ProviderTemplate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<ConfiguredProvider?>(null) }
    var form by remember { mutableStateOf(ProviderSettingsForm()) }
    var models by remember { mutableStateOf<List<EnabledModel>>(emptyList()) }
    var protocol by remember { mutableStateOf(ProviderTemplateProtocol.OPENAI_CHAT.wire) }
    var secret by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var templateSource by remember { mutableStateOf<ProviderTemplate?>(null) }
    var approval by remember { mutableStateOf(false) }
    var mcp by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        try {
            catalog = withContext(Dispatchers.IO) { store.readProviderCatalog() }
            unreadable = false; replacing = false; error = null
            templates = host.plugins.activeProviderTemplates()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ProviderSettingsException) { unreadable = true; error = providerFailureText(failure.failure) }
        catch (_: Exception) { error = R.string.chat_provider_read_failed }
        finally { loading = false }
    }
    LaunchedEffect(store) { reload() }
    fun operate(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ProviderSettingsException) { error = providerFailureText(failure.failure) }
            catch (_: IllegalArgumentException) { error = R.string.chat_provider_invalid }
            catch (_: Exception) { error = R.string.chat_provider_failed }
            finally { busy = false }
        }
    }
    fun edit(provider: ConfiguredProvider?) {
        editor = provider; secret = ""; fetched = emptyList(); filter = ""; selectedModel = null; templateSource = null
        protocol = provider?.connection?.protocol ?: ProviderTemplateProtocol.OPENAI_CHAT.wire
        models = provider?.models.orEmpty()
        form = provider?.let { p ->
            val c = p.connection
            val m = p.models.first()
            ProviderSettingsForm.loaded(OpenAiProviderSettings(c.providerId, c.baseUrl, m.modelId, compat = c.compat))
                .copy(profileId = c.providerId, profileName = c.displayName, noAuth = c.noAuth, savedNoAuth = c.noAuth)
        } ?: ProviderSettingsForm()
    }
    val editable = !loading && !busy && (!unreadable || replacing)
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(strings.get(R.string.providers_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
                if (unreadable || error == R.string.providers_recovered) {
                    TextButton(enabled = !busy, onClick = { scope.launch { reload() } }) { Text(strings.get(R.string.chat_provider_reload)) }
                    if (unreadable) TextButton(enabled = !busy, onClick = { consent = true }) { Text(strings.get(R.string.chat_provider_reenter)) }
                }
                if (replacing) Text(strings.get(R.string.providers_replace_warning), color = MaterialTheme.colorScheme.error)
                if (!unreadable) {
                    Text(strings.get(R.string.providers_bindings), style = MaterialTheme.typography.titleMedium)
                    ProviderBindingChoices(catalog, editable, false) { ref -> operate {
                        catalog = withContext(Dispatchers.IO) { store.selectChatModel(requireNotNull(ref)) }
                    } }
                    ProviderBindingChoices(catalog, editable, true) { ref -> operate {
                        catalog = withContext(Dispatchers.IO) { store.selectImageModel(ref) }
                    } }
                    Text(strings.get(R.string.providers_saved), style = MaterialTheme.typography.titleMedium)
                    if (catalog.providers.isEmpty()) Text(strings.get(R.string.providers_empty))
                    catalog.providers.forEach { p ->
                        OutlinedButton(enabled = editable, onClick = { edit(p) }, modifier = Modifier.fillMaxWidth()) {
                            Text(strings.get(R.string.providers_count, p.connection.displayName, p.models.size))
                        }
                    }
                    Text(strings.get(R.string.providers_add), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(enabled = editable, onClick = { edit(null) }) { Text(strings.get(R.string.providers_custom)) }
                    // Templates are always visible in Add provider, even while editing a saved connection.
                    templates.forEach { t ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(t.name, style = MaterialTheme.typography.titleSmall)
                                Text(strings.get(R.string.chat_provider_template_source, t.pluginName))
                                Text(strings.get(R.string.chat_provider_template_host, t.host))
                                if (t.authorUnverified) Text(strings.get(R.string.chat_provider_template_unverified), color = MaterialTheme.colorScheme.error)
                                if (!t.supported) Text(strings.get(R.string.chat_provider_template_unsupported, t.protocol.wire))
                                else TextButton(enabled = editable, onClick = {
                                    edit(null)
                                    protocol = t.protocol.wire
                                    // Copy through the same validated, key-free template projection used by #135.
                                    form = ProviderSettingsForm(endpoint = t.baseUrl, profileName = t.name)
                                    val c = t.compat ?: OpenAiChatConfig.Compat()
                                    form = form.copy(explicitCacheControl = c.explicitCacheControl, maxTokensField = c.maxTokensField,
                                        streamUsage = c.streamUsage, reasoningField = c.reasoningField.orEmpty(), replayReasoning = c.replayReasoning,
                                        supportsImageInput = c.supportsImageInput, schemaProfile = c.schemaProfile)
                                    fetched = t.models.map { it.id }; templateSource = t
                                }) { Text(strings.get(R.string.chat_provider_template_use)) }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text(strings.get(R.string.providers_connection), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(form.profileName, { form = form.withProfileName(it) }, enabled = editable,
                    label = { Text(strings.get(R.string.providers_name)) }, modifier = Modifier.fillMaxWidth())
                ProviderProtocolChoice(protocol, editable) {
                    protocol = it; fetched = emptyList()
                    if (it in setOf(ProviderTemplateProtocol.ANTHROPIC.wire, ProviderTemplateProtocol.GEMINI.wire)) {
                        form = form.copy(noAuth = false)
                        models = models.map { model -> model.copy(chat = true, image = false) }
                    }
                }
                OutlinedTextField(form.endpoint, { form = form.copy(endpoint = it); fetched = emptyList() }, enabled = editable,
                    label = { Text(strings.get(R.string.chat_endpoint)) }, modifier = Modifier.fillMaxWidth())
                form.targetHost?.let { Text(strings.get(R.string.chat_provider_target_host, it), color = MaterialTheme.colorScheme.primary) }
                templateSource?.let {
                    Text(strings.get(R.string.chat_provider_template_copy_notice, it.pluginName))
                    if (it.authorUnverified) Text(strings.get(R.string.chat_provider_template_unverified), color = MaterialTheme.colorScheme.error)
                }
                if (protocol !in setOf(ProviderTemplateProtocol.ANTHROPIC.wire, ProviderTemplateProtocol.GEMINI.wire))
                    ProviderCheck(strings.get(R.string.chat_provider_no_auth), form.noAuth, editable) { form = form.copy(noAuth = it); secret = "" }
                if (!form.noAuth) OutlinedTextField(secret, { secret = it }, enabled = editable,
                    visualTransformation = PasswordVisualTransformation(), label = { Text(strings.get(R.string.chat_key)) },
                    supportingText = { Text(strings.get(if (form.canReuseKey) R.string.providers_key_reuse else R.string.chat_provider_new_key)) },
                    modifier = Modifier.fillMaxWidth())
                if (protocol == ProviderTemplateProtocol.OPENAI_CHAT.wire) {
                    Text(strings.get(R.string.providers_defaults), style = MaterialTheme.typography.titleSmall)
                    ProviderCompat(form.providerCompat(), editable) { c ->
                        form = form.copy(maxTokensField = c.maxTokensField, reasoningField = c.reasoningField.orEmpty(), replayReasoning = c.replayReasoning,
                            streamUsage = c.streamUsage, explicitCacheControl = c.explicitCacheControl, supportsImageInput = c.supportsImageInput, schemaProfile = c.schemaProfile)
                    }
                }
                Text(strings.get(R.string.providers_models), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(enabled = editable && form.endpoint.isNotBlank() && (form.noAuth || secret.isNotEmpty() || form.canReuseKey), onClick = {
                    operate { fetched = store.fetchModels(form.endpoint, secret, form.profileId, form.noAuth, protocol) }
                }) { Text(strings.get(R.string.chat_models_fetch)) }
                if (fetched.isNotEmpty()) {
                    OutlinedTextField(filter, { filter = it }, label = { Text(strings.get(R.string.chat_models_filter)) }, modifier = Modifier.fillMaxWidth())
                    fetched.filter { it.contains(filter, true) }.forEach { id ->
                        ProviderCheck(id, models.any { it.modelId == id }, editable) { checked ->
                            models = if (checked) models + anthropicTemplateModel(templateSource, id) else models.filterNot { it.modelId == id }
                        }
                    }
                }
                var custom by remember { mutableStateOf("") }
                OutlinedTextField(custom, { custom = it }, enabled = editable, label = { Text(strings.get(R.string.chat_model)) }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = editable && custom.isNotBlank() && custom.length <= 256, onClick = {
                    if (models.none { it.modelId == custom.trim() }) models = models + EnabledModel(custom.trim())
                    selectedModel = custom.trim(); custom = ""
                }) { Text(strings.get(R.string.providers_add_model)) }
                models.forEach { model ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            TextButton(enabled = editable, onClick = { selectedModel = if (selectedModel == model.modelId) null else model.modelId }) { Text(model.label) }
                            fun change(next: EnabledModel) { models = models.map { if (it.modelId == model.modelId) next else it } }
                            ProviderCheck(strings.get(R.string.providers_chat_use), model.chat, editable && (model.image || !model.chat)) { change(model.copy(chat = it)) }
                            if (protocol !in setOf(ProviderTemplateProtocol.ANTHROPIC.wire, ProviderTemplateProtocol.GEMINI.wire))
                                ProviderCheck(strings.get(R.string.providers_image_use), model.image, editable && (model.chat || !model.image)) { change(model.copy(image = it)) }
                            if (selectedModel == model.modelId) {
                                if (protocol == ProviderTemplateProtocol.ANTHROPIC.wire) AnthropicModelOptions(model, editable, ::change)
                                if (protocol == ProviderTemplateProtocol.GEMINI.wire) GeminiModelOptions(model, editable, ::change)
                                OutlinedTextField(model.alias.orEmpty(), { if (it.isBlank() || validProfileDisplayName(it)) change(model.copy(alias = it.takeIf(String::isNotBlank))) },
                                    enabled = editable, label = { Text(strings.get(R.string.providers_alias)) }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(model.systemPrompt.orEmpty(), { if (it.length <= MAX_SYSTEM_PROMPT_CHARS) change(model.copy(systemPrompt = it)) },
                                    enabled = editable, minLines = 2, maxLines = 6, label = { Text(strings.get(R.string.chat_system_prompt)) }, modifier = Modifier.fillMaxWidth())
                                if (protocol == ProviderTemplateProtocol.OPENAI_CHAT.wire) {
                                    ProviderCheck(strings.get(R.string.providers_override), model.compat != null, editable) { change(model.copy(compat = if (it) form.providerCompat() else null)) }
                                    model.compat?.let { ProviderCompat(it, editable) { c -> change(model.copy(compat = c)) } }
                                }
                                TextButton(enabled = editable, onClick = { models = models.filterNot { it.modelId == model.modelId } }) { Text(strings.get(R.string.providers_remove_model)) }
                            }
                        }
                    }
                }
                if (editor != null) TextButton(enabled = editable, onClick = { operate {
                    catalog = withContext(Dispatchers.IO) { store.deleteProvider(requireNotNull(editor).connection.providerId) }; edit(null)
                } }) { Text(strings.get(R.string.providers_delete)) }
                PersonaPicker(host, editable)
                OutlinedButton(enabled = !busy, onClick = { approval = true }) { Text(strings.get(R.string.chat_approval_settings)) }
                OutlinedButton(enabled = !busy, onClick = { mcp = true }) { Text(strings.get(R.string.mcp_title)) }
                AppLanguageSetting()
            }
        },
        confirmButton = { Button(enabled = editable && models.isNotEmpty() && form.endpoint.isNotBlank() &&
            form.profileName.isNotBlank() && (form.noAuth || secret.isNotEmpty() || form.canReuseKey), onClick = {
            operate {
                val c = ProviderConnection(form.profileId ?: UUID.randomUUID().toString(), form.profileName.trim(), form.endpoint.trim(),
                    protocol = protocol, noAuth = form.noAuth, compat = form.providerCompat())
                catalog = withContext(Dispatchers.IO) { store.saveProvider(c, models, if (c.noAuth) "" else secret, replacing) }
                unreadable = false; replacing = false; edit(catalog.providers.first { it.connection.providerId == c.providerId })
            }
        }) { Text(strings.get(R.string.chat_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(strings.get(R.string.chat_close)) } },
    )
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text(strings.get(R.string.chat_provider_reenter)) },
        text = { Text(strings.get(R.string.providers_replace_warning)) },
        confirmButton = { TextButton(onClick = { consent = false; replacing = true; edit(null); error = null }) { Text(strings.get(R.string.providers_replace_confirm)) } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text(strings.get(R.string.chat_close)) } })
    if (approval) ChatApprovalDialog(host) { approval = false }
    if (mcp) io.github.bytejuno.thetacore.mcp.McpScreen(host) { mcp = false }
}

@Composable
private fun ProviderCheck(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(top = 12.dp))
    }
}

@Composable
private fun ProviderCompat(value: OpenAiChatConfig.Compat, enabled: Boolean, change: (OpenAiChatConfig.Compat) -> Unit) {
    val strings = uiStrings()
    fun next(maxTokensField: String = value.maxTokensField, reasoningField: String? = value.reasoningField,
        replayReasoning: Boolean = value.replayReasoning, streamUsage: Boolean = value.streamUsage,
        cache: Boolean = value.explicitCacheControl, images: Boolean = value.supportsImageInput,
        schema: SchemaProfile = value.schemaProfile) = OpenAiChatConfig.Compat(maxTokensField = maxTokensField,
        reasoningField = reasoningField, replayReasoning = replayReasoning, streamUsage = streamUsage,
        explicitCacheControl = cache, supportsImageInput = images, schemaProfile = schema)
    var expanded by remember { mutableStateOf(false) }
    TextButton(enabled = enabled, onClick = { expanded = !expanded }) { Text(strings.get(if (expanded) R.string.chat_compat_hide else R.string.chat_compat_show)) }
    if (expanded) {
        OutputTokenField.entries.forEach { field -> FilterChip(value.maxTokensField == field.wire,
            { change(next(maxTokensField = field.wire)) }, enabled = enabled, label = { Text(strings.get(field.label)) }) }
        ProviderCheck(strings.get(R.string.chat_compat_cache), value.explicitCacheControl, enabled) { change(next(cache = it)) }
        ProviderCheck(strings.get(R.string.chat_compat_usage), value.streamUsage, enabled) { change(next(streamUsage = it)) }
        ProviderCheck(strings.get(R.string.chat_compat_image_input), value.supportsImageInput, enabled) { change(next(images = it)) }
        OutlinedTextField(value.reasoningField.orEmpty(), { if (it.isEmpty() || validReasoningField(it)) change(next(reasoningField = it.ifEmpty { null }, replayReasoning = value.replayReasoning && it.isNotEmpty())) },
            enabled = enabled, label = { Text(strings.get(R.string.chat_compat_reasoning)) }, modifier = Modifier.fillMaxWidth())
        ProviderCheck(strings.get(R.string.chat_compat_replay), value.replayReasoning, enabled && value.reasoningField != null) { change(next(replayReasoning = it)) }
        Text(strings.get(R.string.providers_schema))
        SchemaProfile.entries.forEach { schema -> FilterChip(value.schemaProfile == schema, { change(next(schema = schema)) }, enabled = enabled, label = { Text(strings.get(schema.label)) }) }
    }
}

internal val SchemaProfile.label: Int get() = when (this) {
    SchemaProfile.FULL -> R.string.providers_schema_full
    SchemaProfile.NO_TOP_LEVEL_COMBINATORS -> R.string.providers_schema_no_combinators
    SchemaProfile.BASIC -> R.string.providers_schema_basic
}

@Composable
internal fun ProviderBindingChoices(catalog: ProviderCatalog, enabled: Boolean, image: Boolean, select: (ModelRef?) -> Unit) {
    val strings = uiStrings()
    Text(strings.get(if (image) R.string.providers_image_binding else R.string.providers_chat_binding), style = MaterialTheme.typography.titleSmall)
    val active = if (image) catalog.bindings.activeImageModelRef else catalog.bindings.activeChatModelRef
    if (image) FilterChip(active == null, { select(null) }, enabled = enabled, label = { Text(strings.get(R.string.providers_image_none)) })
    catalog.providers.forEach { p ->
        val candidates = p.models.filter { if (image) it.image else it.chat }
        if (candidates.isNotEmpty()) {
            Text(p.connection.displayName, style = MaterialTheme.typography.labelLarge)
            candidates.forEach { m ->
                val ref = ModelRef(p.connection.providerId, m.modelId)
                FilterChip(active == ref, { select(ref) }, enabled = enabled, label = { Text(m.label) })
            }
        }
    }
}

private fun ProviderSettingsForm.providerCompat() = OpenAiChatConfig.Compat(
    maxTokensField = maxTokensField, reasoningField = reasoningField.ifEmpty { null }, replayReasoning = replayReasoning,
    streamUsage = streamUsage, explicitCacheControl = explicitCacheControl, supportsImageInput = supportsImageInput, schemaProfile = schemaProfile,
)

private fun validReasoningField(value: String): Boolean = runCatching { OpenAiChatConfig.Compat(reasoningField = value) }.isSuccess
