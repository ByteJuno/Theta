package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.Effect
import io.github.bytejuno.thetacore.agent.ledger.InvocationRecord

internal const val IMAGE_TOOL_KEY = "host/generate_image"
internal const val IMAGE_PROVIDER_ID = "images"
internal const val IMAGE_COMPLETE = "image_generation_complete"
internal const val IMAGE_CONFIGURE = "image_generation_configure_settings"
internal const val IMAGE_ATTEMPTED = "image_generation_already_attempted"
internal const val IMAGE_FAILED = "image_generation_failed_check_settings_no_auto_retry"

internal fun imageResultLabel(record: InvocationRecord): Int? {
    val result = record.result ?: return null
    return when {
        result.text == IMAGE_COMPLETE -> R.string.chat_image_complete
        result.text == IMAGE_CONFIGURE -> R.string.chat_image_configure
        result.text == IMAGE_ATTEMPTED || result.text.endsWith("\n$IMAGE_ATTEMPTED") -> R.string.chat_image_already_attempted
        record.instance == null && result.effect == Effect.NONE -> R.string.chat_image_not_sent
        else -> R.string.chat_image_failed
    }
}

internal fun shouldLoadChatImage(record: InvocationRecord): Boolean =
    record.state.terminal && record.instance != null

