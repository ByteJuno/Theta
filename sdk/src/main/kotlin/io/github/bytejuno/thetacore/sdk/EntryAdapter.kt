package io.github.bytejuno.thetacore.sdk

import kotlinx.coroutines.flow.Flow

/** A managed trigger into the host chat backend. */
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

/** Ordered events for an entry's bound session. Existing S1 types retain their ABI. */
sealed interface SessionEvent {
    data class Text(val delta: String) : SessionEvent
    data object Finished : SessionEvent
    data class Failed(val message: String) : SessionEvent
    /** Ends one model attempt. When discardText is true, its streamed Text deltas
     * belong to a discarded attempt. The run may still continue with another attempt. */
    interface AttemptEnded : SessionEvent { val discardText: Boolean }
    interface ToolActivity : SessionEvent { val toolName: String; val state: String }
    interface NeedsApproval : SessionEvent { val summary: String }
}

class EntryUnavailableException : IllegalStateException("Entry session backend is unavailable")
