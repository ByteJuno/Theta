package io.github.bytejuno.thetacore.agent.ledger

/** Receipt identity is the revocation boundary: a later explicit grant has a new identity. */
data class PermanentToolGrant(val definition: FrozenDefinition, val risk: Risk, val receiptId: String)
