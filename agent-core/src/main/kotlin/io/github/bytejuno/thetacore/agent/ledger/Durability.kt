package io.github.bytejuno.thetacore.agent.ledger

/** Retained for legacy filesystem test fixtures only.
 * AgentDatabase, invocation storage and session history always use WAL and synchronous=FULL.
 */
enum class Durability { PROCESS_CRASH_ONLY, REQUIRE_DIRECTORY_SYNC }
