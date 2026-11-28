package io.github.bytejuno.thetacore.provider

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.bytejuno.thetacore.plugin.ProviderTemplateProtocol
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings

@Composable
internal fun ProviderProtocolChoice(protocol: String, enabled: Boolean, select: (String) -> Unit) {
    val strings = uiStrings()
    Column {
        Text(strings.get(R.string.responses_protocol))
        listOf(ProviderTemplateProtocol.OPENAI_CHAT.wire to R.string.responses_chat,
            ProviderTemplateProtocol.OPENAI_RESPONSES.wire to R.string.responses_native,
            ProviderTemplateProtocol.ANTHROPIC.wire to R.string.anthropic_protocol,
            ProviderTemplateProtocol.GEMINI.wire to R.string.gemini_protocol).forEach { (wire, label) ->
            FilterChip(protocol == wire, { select(wire) }, enabled = enabled, label = { Text(strings.get(label)) })
        }
        if (protocol == ProviderTemplateProtocol.OPENAI_RESPONSES.wire) Text(strings.get(R.string.responses_native_hint))
        if (protocol == ProviderTemplateProtocol.ANTHROPIC.wire) Text(strings.get(R.string.anthropic_hint))
        if (protocol == ProviderTemplateProtocol.GEMINI.wire) Text(strings.get(R.string.gemini_hint))
    }
}
