package io.github.bytejuno.thetacore

/** Main-thread UI lifetime, installed before admission and independent of recomposition. */
internal class HostInvocationLifetime {
    private var disposed = false
    private var cancel: (() -> Unit)? = null

    fun adopt(cancelInvocation: () -> Unit) {
        if (disposed) cancelInvocation() else {
            check(cancel == null) { "Invocation already adopted" }
            cancel = cancelInvocation
        }
    }

    fun dispose() {
        disposed = true
        val action = cancel
        cancel = null
        action?.invoke()
    }
}

/** Main-thread Activity ownership only; durable recovery remains owned by the Application. */
internal class HostPageAttribution {
    private var owner: Any? = null
    fun claim(token: Any) { owner = token }
    fun owns(token: Any): Boolean = owner === token
    fun release(token: Any): Boolean {
        if (!owns(token)) return false
        owner = null
        return true
    }
}

/** Each Activity retains its selection while paused, but cannot mutate another owner's journal. */
internal class HostPageLease(
    private val attribution: HostPageAttribution,
    private val enter: (String) -> Boolean,
    private val leave: () -> Unit,
) {
    private val token = Any()
    private var resumed = false
    private var selectedId: String? = null
    private var journaled = false
    private var journaledId: String? = null
    val ownsPage: Boolean get() = resumed && attribution.owns(token)

    fun resume() {
        journaled = false
        resumed = true
        attribution.claim(token)
        // Re-establish A's remembered page before any resumed plugin execution, even if
        // another Activity displayed the host list while A was paused.
        journalSelection()
    }

    fun pause() {
        journaled = false
        resumed = false
        if (attribution.release(token)) leave()
    }

    /** Called synchronously before selecting or composing a plugin page, never an effect. */
    fun beforeRender(id: String?): Boolean {
        if (!ownsPage) {
            journaled = false
            return false
        }
        selectedId = id
        return journalSelection()
    }

    private fun journalSelection(): Boolean {
        val id = selectedId
        if (journaled && journaledId == id) return true
        // Retire the cache BEFORE IO: failure/throw must never preserve a stale success.
        journaled = false
        val accepted = if (id != null) enter(id) else { leave(); true }
        if (accepted) {
            journaledId = id
            journaled = true
        }
        return accepted
    }
}
