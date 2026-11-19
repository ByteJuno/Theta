package io.github.bytejuno.thetacore.agent.ledger

/** Deliberately public-safe retrieval failure; no storage paths or internal exception names. */
class BlobReadException(message: String) : RuntimeException(message)
