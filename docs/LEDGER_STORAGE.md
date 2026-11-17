# Agent SQLite storage and retention

`InvocationLedger` attaches to one exclusive writer and loads `records()` once at
construction. It indexes snapshots by `callId`; acknowledged writes replace that
entry under `DefinitionGate`. Policy, ordering, resource and task-chain checks
still scan in-memory history. `SqliteInvocationStore` validates the stored records
once on open, caches acknowledged snapshots, and returns detached values. External
edits while a writer is open are unsupported; close and reopen to validate them.

A failed transaction leaves the previous acknowledged snapshot visible. An
ambiguous commit acknowledgement poisons the shared database and halts coordinator
mutations. Reads continue to expose the last acknowledged snapshot, which may be
older than disk. Close, reopen and cold-recover before admitting more execution.
Ledger records, result contents and session history share one `AgentDatabase`.
SQLite WAL transactions use `synchronous=FULL` and foreign keys; the Android
adapter configures both existing and future pooled connections before schema work.
This is not a physical power-loss guarantee.

Record payloads use `storageVersion: 1` alongside aggregate `formatVersion: 2`. A
per-record `texts` array stores each distinct result/evidence text once; the
logical result, exit evidence and evidence outcome use integer references. Different
late evidence keeps its own text. Record metadata and bounded chunks are committed
in one database transaction with revision CAS. Deduplication is within a record,
not across calls or arbitrary input strings. The payload codec can read legacy
inline-text aggregates stored inside SQLite; this does not import old JSON files.
Unknown encodings, missing/out-of-range references and malformed aggregates fail
closed without deleting the source evidence.

## Android ownership and recovery

`AndroidAgentStore` opens the system SQLite implementation through AndroidX's
support driver. `PluginManager.initializeHostTools` opens it once through the
process-owned bridge; application startup awaits cold recovery before admitting
tool execution. It also creates/opens `SqliteRunHistory` handles on that same
database. The production chat/session consumer remains part of #24.

The database is `Context.noBackupFilesDir/agent.db`, with its WAL, shared-memory,
writer/provenance and session scratch files under that private no-backup root.
The manifest disables backup and data-extraction rules exclude cloud backup and
device transfer. Plugin packages, preferences and plugin data are separate and
are not changed by this storage adapter.

A lifetime OS lock excludes a second owner. Active runs and summary spools pin
the owner: a rejected close leaves ledger admission intact. A failed/uncertain
write closes admission across ledger and history until reopen and cold recovery.
Startup never silently deletes corruption or retries as a new installation.
Legacy JSON/directory storage is neither imported nor automatically deleted.

History appends commit whole batches, revision, call index and HEAD atomically.
Indexed pages read bounded chunks; `snapshot()` and the current run loop still
materialize history. A storage failure cannot undo an external tool side effect.

No new R8 keep rules are required for the adapter: the driver, history and store
are reached through direct calls. The existing explicit instrumentation/runtime
wire boundary retains its own methods; internal store names may be obfuscated.
Release build success alone does not prove Android restart or recovery behavior.

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
