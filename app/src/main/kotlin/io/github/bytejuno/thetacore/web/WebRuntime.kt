package io.github.bytejuno.thetacore.web

import android.content.Context
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.storage.androidSecretCipher

internal object WebRuntime {
    fun settings(context: Context) = WebSearchSettingsStore(context.noBackupFilesDir,
        androidSecretCipher("theta.web.credentials.v1", "theta-web-credentials-v1", allowKeyRecovery = true))
    fun tools(context: Context): List<io.github.bytejuno.thetacore.sdk.Tool> {
        val app = context.applicationContext
        val http = WebHttp()
        val message: (WebError) -> String = { app.getString(when (it) {
            WebError.ADDRESS -> R.string.web_address_error
            WebError.DOWNLOAD -> R.string.web_size_error
            WebError.RESPONSE -> R.string.web_response_error
            WebError.NETWORK -> R.string.web_network_error
            WebError.SETTINGS -> R.string.web_settings_error
            WebError.INPUT -> R.string.web_input_error
        }) }
        return listOf(WebFetchTool(http, message), WebSearchTool(http, settings(app), message))
    }
}
