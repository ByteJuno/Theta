package io.github.bytejuno.thetacore.agent.ledger

/** FileRunHistory still uses the old file protocol until the separate history migration.
 * SQLite invocation storage always uses WAL and synchronous=FULL instead.
 */
enum class Durability { PROCESS_CRASH_ONLY, REQUIRE_DIRECTORY_SYNC }
