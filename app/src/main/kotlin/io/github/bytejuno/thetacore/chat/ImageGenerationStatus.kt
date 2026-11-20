package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R

internal const val IMAGE_TOOL_KEY = "host/generate_image"
internal const val IMAGE_PROVIDER_ID = "images"
internal const val IMAGE_COMPLETE = "image_generation_complete"
internal const val IMAGE_CONFIGURE = "image_generation_configure_settings"
internal const val IMAGE_ATTEMPTED = "image_generation_already_attempted"
internal const val IMAGE_FAILED = "image_generation_failed_check_settings_no_auto_retry"

internal fun imageResultLabel(result: String): Int = when (result) {
    IMAGE_COMPLETE -> R.string.chat_image_complete
    IMAGE_CONFIGURE -> R.string.chat_image_configure
    IMAGE_ATTEMPTED -> R.string.chat_image_already_attempted
    else -> R.string.chat_image_failed
}

