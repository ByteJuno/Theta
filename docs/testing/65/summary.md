# Issue 65: session-scoped tool result content

Initial implementation source: `262c606edf5313dae81a932e1bedbcde42bad63d`, based on main `500b49f7149739846fc6766dd83562b989556308`. Results below are bound to their stated source revisions. Tested source manifest is retained outside Git.

The host blob reader now uses trusted ToolEnv.sessionId, including earlier runs. Foreign and unknown IDs return identical public-safe errors before range validation; authorized invalid ranges, ambiguous legacy references and missing/corrupt content have explicit messages. Range reads remain bounded to 8192 bytes.

A trusted `InvocationLedger.deleteSession` operation atomically removes session history and content ownership, collects content with no other session owner, and persists a deletion tombstone. It requires closed history handles, no active owner pins and no unfinished/unconfirmed invocation. Invocation records, effects, isolation and approval receipts remain available, including subsequent recovery decisions. New calls/content cannot resurrect a deleted session. SQLite v1/v2 upgrade to v3 adds only the deletion table (v1 also receives existing history tables). There was no session-delete UI on main; this change supplies the host API, not a new UI action.

Initial validation on Windows with real bundled SQLite (AndroidAgentStore test driver injection):
- 44/44 targeted tests passed in 35.49s: SessionBlobTest 7, SharedHistoryDatabaseTest 13, SqliteOwnerCloseTest 3, HostSessionBlobTest 1, HostBlobOutputTest 8, AndroidAgentStoreTest 12.
- 23/23 affected existing regressions passed on the same implementation in 17.19s: BlobRegressionTest 11, ToolOutputTest 7, SqliteOpenFailureTest 5.
- Total 67 passed, zero failures/skips in the successful runs. Strict `--configuration-cache --configuration-cache-problems=fail`; configuration cache stored. App/core compilation and UI-resource static validation passed. No new visible UI was added.
- Cross-run chunk reconstruction, restart, end/invalid offsets, equal foreign/unknown denial, shared/unique content cleanup, tombstone persistence, unconfirmed-worker refusal, retained UNKNOWN/approval/recovery, pre/post-COMMIT failure handling and v1/v2 upgrades are covered.
- Initial attempt failed four new test cases (restart loading guard and expected admission result); the original logs/XML remain in the external run evidence. The guard and test expectation were corrected, without removing assertions or extending timeouts.

Evidence: `event-runs/c6393bca-e8a8-4355-80ca-0f11ca3acd51`; validation directories `validation-issue11/issue65-c6393bca-{targeted,r2,regression}`. Raw logs/XML are not committed.

APK: not generated; APK SHA: not applicable. No phone or emulator operated. Android API33 runtime, release/R8, physical durability and final-head CI/regression are not claimed. The independent reviewer assesses the code. The outer review script runs the required emulator regression, checks CI and head/main, and merges only after all gates pass. Blob quarantine republishing policy remains issue #66. SQLite row deletion is logical cleanup, not secure erasure of storage media; retained ledger safety records are deliberate.

## Review return on `b7fb1fef`

The reviewer reproduced three failures on real SQLite or through the host tool. A 65,538-byte UTF-8 session ID broke ledger reopen after deletion, an abandoned summary spool survived deletion, and offset `9223372036854775808` exposed `NumberFormatException`. The external run directory `event-runs/8e70214a-da99-434f-b8cf-6035cfb341a8` retains the original reviewer verdict.

Verified implementation `0d3f8550e3ea4130da7cb0ef905aaabe69e491d6` on Windows with bundled SQLite and strict Gradle configuration cache. The six selected suites reported 55 tests in 32.53 s, with 54 passed, zero failures or errors, and one existing platform skip for POSIX symlink/parent traversal. The new checks passed:

- Create and delete the long ID, reopen the database and ledger, confirm the tombstone and open the unrelated session.
- Interrupt immediately after deletion commits, reopen, retry cleanup, and verify the other session's scratch bytes survive until its own deletion.
- Send the oversized integer through the host tool. The owning session receives the public range error. Foreign and unknown IDs receive identical session-unavailable errors.

Raw results are in `validation-issue11/issue65-8e70214a-final`. This run generated no APK and involved no device. Final-head review, prescribed emulator regression and CI are pending.
