# Issue 31 verification

Implementation: `69d972337bbe28a218aaafb12f6363dc24526e26`, based on
`9334f8bd3c6ffd6dc484e06f384c055e0d550c2a` (`origin/main`). Debug APK SHA-256:
`6739529ab2dacb19f61369a56abd8b7776459f9b51f6656ebd1a98cb7f4caec5`.

Per-run frozen model-name to `FrozenDefinition` mappings are now durably persisted
in SQLite (`AgentDatabase.SCHEMA_VERSION = 6`, chunked `run_catalogs` and
`run_catalog_chunks` tables with SHA-256 checksums, bounded SQL read projections,
unpaired-surrogate-safe JSON encoding, and fail-closed corruption handling via
`SqliteRunCatalogStore` exposed through `InvocationStore.runCatalogStore()`).
`DefinitionGate` provides `restoreRun`, `restoreRuns`, and `frozenRun`;
`FrozenToolCatalog` provides `restore(runId)`, `restore(runId, definitions)`, and
`restoreAll()`, persists new freezes before publishing in memory, and restores
persisted mappings if `freeze(runId)` or `restorePending(runId, ...)` is called
after restart instead of re-allocating aliases from the current tool directory.
`HostToolBridgeRoot`, `ChatSessionOwner`, and `HostToolRuntimeApi` are wired to
the durable run catalog store.

Windows/JDK 25: 200/200 JVM tests passed, no failures/errors/skips, in one
invocation (`issue31-green5-20261124`):
- `agent-core`: `RunCatalogPersistenceTest` (4), `PrivateImageStoreTest` (4),
  `SharedHistoryDatabaseTest` (13), `SqliteInvocationStoreTest` (8),
  `SqliteOpenFailureTest` (5).
- `app`: `FrozenToolCatalogPersistenceTest` (2), `AndroidAgentStoreTest` (12),
  `HostToolExecutionServiceTest` (88), `HostToolRuntimeApiTest` (4),
  `ChatSessionOwnerTest` (60).
`:app:assembleDebug` and `:app:lintDebug` passed in the same invocation (86.80s,
0 errors) with `--configuration-cache --configuration-cache-problems=fail`.

New regression tests cover the exact collision-hijacking acceptance scenario:
freezing `host/a/b` and `host/a_b` before restart (`host/a/b` ->
`host_a_b_02c2b0e6aca1`), restarting and registering `host/a_b_02c2b0e6aca1`,
verifying the restarted run still resolves `host_a_b_02c2b0e6aca1` to original
`host/a/b` when unchanged and explicitly rejects (`STALE definition`) when
`host/a/b` is missing or changed—never executing the hijacking tool—while new
runs allocate fresh mappings; plus chunked >8 KiB persistence, unpaired
surrogates, immutability conflicts, uncommitted fault rollback, corruption
fail-closed rejection, and schema v1/v4/v5 -> v6 upgrades.

Limitations: physical device, Android API 33 runtime, Release/R8, and emulator
instrumentation were not exercised locally; final-head independent review,
prescribed regression, and CI remain required before reviewer merge. Protected
files are unchanged. Raw logs and XML are retained outside Git.
