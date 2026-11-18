# Issue 65: session-scoped tool result content

Implementation source: `262c606edf5313dae81a932e1bedbcde42bad63d`, based on main `500b49f7149739846fc6766dd83562b989556308`. The subsequent documentation commit changes only this summary. Tested source manifest is retained outside Git.

The host blob reader now uses trusted ToolEnv.sessionId, including earlier runs. Foreign and unknown IDs return identical public-safe errors before range validation; authorized invalid ranges, ambiguous legacy references and missing/corrupt content have explicit messages. Range reads remain bounded to 8192 bytes.

A trusted `InvocationLedger.deleteSession` operation atomically removes session history and content ownership, collects content with no other session owner, and persists a deletion tombstone. It requires closed history handles, no active owner pins and no unfinished/unconfirmed invocation. Invocation records, effects, isolation and approval receipts remain available, including subsequent recovery decisions. New calls/content cannot resurrect a deleted session. SQLite v1/v2 upgrade to v3 adds only the deletion table (v1 also receives existing history tables). There was no session-delete UI on main; this change supplies the host API, not a new UI action.

Validation on Windows with real bundled SQLite (AndroidAgentStore test driver injection):
- 44/44 targeted tests passed in 35.49s: SessionBlobTest 7, SharedHistoryDatabaseTest 13, SqliteOwnerCloseTest 3, HostSessionBlobTest 1, HostBlobOutputTest 8, AndroidAgentStoreTest 12.
- 23/23 affected existing regressions passed on the same implementation in 17.19s: BlobRegressionTest 11, ToolOutputTest 7, SqliteOpenFailureTest 5.
- Total 67 passed, zero failures/skips in the successful runs. Strict `--configuration-cache --configuration-cache-problems=fail`; configuration cache stored. App/core compilation and UI-resource static validation passed. No new visible UI was added.
- Cross-run chunk reconstruction, restart, end/invalid offsets, equal foreign/unknown denial, shared/unique content cleanup, tombstone persistence, unconfirmed-worker refusal, retained UNKNOWN/approval/recovery, pre/post-COMMIT failure handling and v1/v2 upgrades are covered.
- Initial attempt failed four new test cases (restart loading guard and expected admission result); the original logs/XML remain in the external run evidence. The guard and test expectation were corrected, without removing assertions or extending timeouts.

Evidence: `event-runs/c6393bca-e8a8-4355-80ca-0f11ca3acd51`; validation directories `validation-issue11/issue65-c6393bca-{targeted,r2,regression}`. Raw logs/XML are not committed.

APK: not generated; APK SHA: not applicable. No phone or emulator operated. Android API33 runtime, release/R8, physical durability and final-head CI/regression are not claimed. Independent reviewer owns final-head review, prescribed emulator regression, CI and merge. Blob quarantine republishing policy remains issue #66. SQLite row deletion is logical cleanup, not secure erasure of storage media; retained ledger safety records are deliberate.
