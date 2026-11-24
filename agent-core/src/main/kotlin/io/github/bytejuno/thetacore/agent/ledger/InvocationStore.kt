package io.github.bytejuno.thetacore.agent.ledger

import java.io.Closeable

/** Single writer for the entire host. Reads return detached values; save is revision CAS.
 * Any ambiguous acknowledgement MUST suspend all writes/claims until reopen and cold recovery.
 */
interface InvocationStore : Closeable {
    /** Exactly one coordinator may attach. Only it receives the session deletion operation, if supported. */
    fun bindCoordinator(): ((String) -> Unit)?
    /** Admission health, independent of cached projections and SQL writes. Throw StoreException
     * when the shared owner is closed/uncertain. This does not revoke already executing effects. */
    fun checkAdmission() {}
    fun isSessionDeleted(sessionId: String): Boolean = false
    fun records(): List<InvocationRecord>
    fun save(record: InvocationRecord, expectedRevision: Long?)
    /** Atomically commit full contents and their record references under the same writer ownership. */
    fun saveWithBlobs(record: InvocationRecord, expectedRevision: Long?, blobs: List<BlobWrite>) {
        check(blobs.isEmpty()) { "Store does not support blobs" }
        save(record, expectedRevision)
    }
    fun readBlob(ref: BlobRef, offset: Long, limit: Int): ByteArray = error("Store does not support blobs")
    /** Durable per-run frozen tool catalog store, when supported by the backing database. */
    fun runCatalogStore(): RunCatalogStore? = null
}
class StoreException(message: String, val uncertain: Boolean = false, cause: Throwable? = null) :
    RuntimeException(message, cause)
