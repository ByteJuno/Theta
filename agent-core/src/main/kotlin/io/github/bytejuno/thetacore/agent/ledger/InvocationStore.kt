package io.github.bytejuno.thetacore.agent.ledger

import java.io.Closeable

/** Single writer for the entire host. Reads return detached values; save is revision CAS.
 * Any ambiguous acknowledgement MUST suspend all writes/claims until reopen and cold recovery.
 */
interface InvocationStore : Closeable {
    /** Exactly one coordinator may attach for the lifetime of this open writer. */
    fun bindCoordinator()
    fun records(): List<InvocationRecord>
    fun save(record: InvocationRecord, expectedRevision: Long?)
}
class StoreException(message: String, val uncertain: Boolean = false, cause: Throwable? = null) :
    RuntimeException(message, cause)
