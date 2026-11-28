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
internal fun GeminiModelOptions(model: EnabledModel, enabled: Boolean, change: (EnabledModel) -> Unit) {
    val strings = uiStrings()
    var tokens by remember(model.modelId, model.parameters.maxOutputTokens) {
        mutableStateOf(model.parameters.maxOutputTokens?.toString().orEmpty())
    }
    val invalid = tokens.isNotEmpty() && tokens.toIntOrNull()?.let { it > 0 } != true
    Column {
        OutlinedTextField(tokens, { value ->
            tokens = value
            if (value.isEmpty() || value.toIntOrNull()?.let { it > 0 } == true)
                change(model.copy(parameters = anthropicParameters(model.parameters, maxTokens = value.toIntOrNull())))
        }, enabled = enabled, isError = invalid, modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.get(R.string.gemini_max_tokens)) },
            supportingText = { Text(strings.get(if (invalid) R.string.gemini_tokens_invalid else R.string.gemini_tokens_hint)) })
        Text(strings.get(R.string.gemini_thinking))
        GeminiEffort.entries.forEach { choice ->
            FilterChip(model.parameters.reasoningLevel == choice.wire,
                { change(model.copy(parameters = anthropicParameters(model.parameters, effort = choice.wire))) },
                enabled = enabled, label = { Text(strings.get(choice.label)) })
        }
        Text(strings.get(R.string.gemini_thinking_hint))
    }
}
