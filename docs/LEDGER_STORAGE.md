# Invocation ledger storage and retention

`InvocationLedger` attaches to one exclusive writer and loads `records()` once at
construction. It indexes snapshots by `callId`; acknowledged writes replace that
entry under `DefinitionGate`. Policy, ordering, resource and task-chain checks
still scan in-memory history. `JsonInvocationStore` scans and validates the files
once on open, caches acknowledged snapshots, and returns detached values. External
edits while a writer is open are unsupported; close and reopen to validate them.

A failed write before replacement leaves the previous snapshot visible. An
ambiguous replacement acknowledgement poisons the writer and halts coordinator
mutations. Reads continue to expose the last acknowledged snapshot, which may be
older than disk. Close, reopen and cold-recover before admitting more execution.
The existing atomic-file and durability modes remain in force; this does not add
an Android durability adapter or a physical power-loss guarantee.

New writes use `storageVersion: 1` alongside aggregate `formatVersion: 2`. A
per-record `texts` array stores each distinct result/evidence text once; the
logical result, exit evidence and evidence outcome use integer references. Different
late evidence keeps its own text. The whole aggregate and text table are replaced
atomically in the same file. Deduplication is within a record, not across calls or
arbitrary input strings. Existing inline-text v2 files remain readable and are not
rewritten merely by opening them; their next successful mutation uses the compact
encoding. Unknown encodings, missing/out-of-range references and malformed
aggregates fail closed without deleting the source evidence. Older binaries do
not understand the new encoding; downgrade requires compatible software rather
than discarding the files.

## Retention policy

Retain terminal history indefinitely, just like unfinished records. There is no
automatic time/count eviction, deletion API or startup cleanup. In particular,
unconfirmed instances, pending recovery decisions, restart barriers, unresolved
isolation and unknown-effect evidence are never removed. Closed records also
remain queryable after restart and preserve approval grants, task-chain claim
counts, frozen bindings and callback replay identity. This follows the existing
protocol requirement to keep original invocation history available.

Both the writer and coordinator hold snapshots including text in memory. Memory
therefore grows with retained history; this change removes repeated disk scans,
not all O(history) policy work or unbounded-history memory growth. A future host
archive/retention integration must preserve query access, grants, claim counters,
retry uncertainty and recovery semantics before introducing any deletion or body
eviction. No bounded storage or lazy-body guarantee is made here.

`LedgerStorageIndexTest` covers a counting store seeded with 3000 records, text
occurrence and restart round trips, late evidence, legacy reads, corrupt text
references, snapshot aliasing and retained grants/budgets/recovery barriers. The
existing ledger fault-injection and cold-start tests remain required. These are
JVM checks, not Android filesystem or device evidence.
