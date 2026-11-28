package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.R

internal enum class GeminiEffort(val wire: String?, val label: Int) {
    DEFAULT(null, R.string.gemini_default), MINIMAL("minimal", R.string.gemini_minimal),
    LOW("low", R.string.gemini_low), MEDIUM("medium", R.string.gemini_medium),
    HIGH("high", R.string.gemini_high), XHIGH("xhigh", R.string.gemini_xhigh),
}
