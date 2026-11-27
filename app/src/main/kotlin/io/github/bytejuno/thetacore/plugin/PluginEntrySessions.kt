package io.github.bytejuno.thetacore.plugin

import android.content.Context
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.sdk.EntryRequest
import io.github.bytejuno.thetacore.sdk.EntrySession
import io.github.bytejuno.thetacore.sdk.EntryUnavailableException

internal fun chatEntrySessionFactory(context: Context): suspend (EntryRequest, () -> Boolean) -> EntrySession = { request, admitted ->
    val application = context.applicationContext as? ThetaApplication ?: throw EntryUnavailableException()
    application.chat.entrySessions.open(request, admitted)
}
