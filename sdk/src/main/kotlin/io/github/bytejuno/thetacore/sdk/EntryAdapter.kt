package io.github.bytejuno.thetacore.sdk

import kotlinx.coroutines.flow.Flow

/** First S1 entry contract. No agent/provider implementation is implied by registration. */
interface EntryAdapter : Extension {
    val displayName: String
    fun isAvailable(device: DeviceInfo): Boolean
    /** Register listeners, call ready once, then suspend until cancellation; release in finally. */
    suspend fun attach(host: EntryHost)
}

/** Immutable host snapshot, deliberately not an interface with plugin-controlled getters. */
data class DeviceInfo(val sdkInt: Int, val manufacturer: String = "", val model: String = "")
data class EntryRequest(val text: String, val sessionId: String? = null)

interface EntryHost {
    fun ready()
    /** Throws when the host has no chat backend; never fabricates a successful session. */
    suspend fun open(request: EntryRequest): EntrySession
}

interface EntrySession {
    val events: Flow<SessionEvent>
    suspend fun send(text: String)
    fun close()
}

/** Text-only S1 subset; media, tool activity and approval events are not implemented yet. */
sealed interface SessionEvent {
    data class Text(val delta: String) : SessionEvent
    data object Finished : SessionEvent
    data class Failed(val message: String) : SessionEvent
}

class EntryUnavailableException : IllegalStateException("Entry session backend is unavailable")
