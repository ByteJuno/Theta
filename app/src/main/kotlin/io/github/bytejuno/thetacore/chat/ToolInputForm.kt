package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.ToolInputRequest
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.serialization.json.*

@Composable
internal fun ToolInputForm(request: ToolInputRequest, respond: (JsonObject?) -> Unit) {
    val strings = uiStrings()
    val definitions = remember(request.id) { toolInputFields(request.schema) }
    val draft = LocalToolInputDrafts.current[request.id]
    var values by draft::values
    var submitted by draft::submitted
    val answer = remember(request.id, values) { toolInputAnswer(request.schema, values) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.get(R.string.tool_input_title), style = MaterialTheme.typography.titleSmall)
        Text(strings.get(R.string.tool_input_notice))
        Text(request.prompt)
        if (definitions == null) Text(strings.get(R.string.tool_input_unsupported), color = MaterialTheme.colorScheme.error)
        definitions?.forEach { (key, field) ->
            val label = toolInputLabel(key, field)
            val choices = toolInputChoices(field)
            when {
                choices != null -> {
                    Text(label)
                    FlowRow {
                        choices.forEach { choice ->
                            val value = choice
                            FilterChip(selected = values[key] == value, enabled = !submitted,
                                onClick = { values = values + (key to value) }, label = { Text(value) })
                        }
                    }
                }
                toolInputBoolean(field) -> Row {
                    Checkbox(toolInputChecked(values[key]), { values = values + (key to it.toString()) }, enabled = !submitted)
                    Text(label)
                }
                else -> OutlinedTextField(values[key].orEmpty(), { values = values + (key to it) },
                    enabled = !submitted, modifier = Modifier.fillMaxWidth(), label = { Text(label) })
            }
        }
        if (definitions != null && answer == null) Text(strings.get(R.string.tool_input_invalid))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !submitted && answer != null, onClick = { submitted = true; respond(answer) }) {
                Text(strings.get(R.string.tool_input_send))
            }
            TextButton(enabled = !submitted, onClick = { submitted = true; respond(null) }) {
                Text(strings.get(R.string.tool_input_decline))
            }
        }
    }
}
