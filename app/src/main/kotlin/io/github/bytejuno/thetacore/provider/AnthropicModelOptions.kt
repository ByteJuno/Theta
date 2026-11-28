package io.github.bytejuno.thetacore.provider

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings

@Composable
internal fun AnthropicModelOptions(model: EnabledModel, enabled: Boolean, change: (EnabledModel) -> Unit) {
    val strings = uiStrings()
    var tokens by remember(model.modelId, model.parameters.maxOutputTokens) {
        mutableStateOf(model.parameters.maxOutputTokens?.toString().orEmpty())
    }
    val invalid = tokens.isNotEmpty() && tokens.toIntOrNull()?.let { it > 0 } != true
    Column {
        OutlinedTextField(tokens, { input ->
            tokens = input
            if (input.isEmpty() || input.toIntOrNull()?.let { it > 0 } == true)
                change(model.copy(parameters = anthropicParameters(model.parameters, maxTokens = input.toIntOrNull())))
        }, enabled = enabled, isError = invalid, modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.get(R.string.anthropic_max_tokens)) },
            supportingText = { Text(strings.get(if (invalid) R.string.anthropic_tokens_invalid else R.string.anthropic_tokens_hint)) })
        Text(strings.get(R.string.anthropic_effort))
        AnthropicEffort.entries.forEach { choice ->
            val effort = choice.wire; val label = choice.label
            FilterChip(model.parameters.reasoningLevel == effort,
                { change(model.withSelectedReasoningLevel(effort)) },
                enabled = enabled, label = { Text(strings.get(label)) })
        }
        Text(strings.get(R.string.anthropic_effort_hint))
    }
}
