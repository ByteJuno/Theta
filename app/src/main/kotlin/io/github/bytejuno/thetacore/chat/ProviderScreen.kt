package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.Composable
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.provider.ProviderSettingsException

@Composable
internal fun ProviderScreen(host: ThetaApplication, onClose: () -> Unit) =
    io.github.bytejuno.thetacore.provider.ProviderConnectionsScreen(host, onClose)

internal fun providerFailureText(failure: ProviderSettingsException.Failure): Int = when (failure) {
    ProviderSettingsException.Failure.NOT_CONFIGURED -> R.string.chat_configure_first
    ProviderSettingsException.Failure.DECRYPT_FAILED -> R.string.chat_credentials_unavailable
    ProviderSettingsException.Failure.ENDPOINT_CHANGED -> R.string.chat_provider_new_key
    ProviderSettingsException.Failure.READ_FAILED, ProviderSettingsException.Failure.INVALID_DATA -> R.string.chat_provider_read_failed
    ProviderSettingsException.Failure.WRITE_FAILED -> R.string.chat_provider_failed
    ProviderSettingsException.Failure.RECOVERED -> R.string.providers_recovered
}
