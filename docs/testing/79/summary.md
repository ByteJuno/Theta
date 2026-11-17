# Issue 79 validation — 2026-11-17

Source commit: `6fbaa4c329297a23357ad2e66f405bd7906e01e0`, based on merged
main `b2eec5929f97a60390b13c9dd05de70e189b16d4`. This summary is the only
test-evidence file added for #79. No raw logs, XML, APK or intermediate runs are
committed.

## Scope

- Rename the Android shared ledger/history owner to `AndroidAgentStore`; keep its
  system SQLite driver, no-backup root, exclusive owner and failure admission.
- Rename the existing SQLite history/accounting suites; retain their assertions.
- Add Android-adapter coverage for pending history/ledger cold recovery and the
  shared uncertain-write barrier; preserve legacy files without importing them.
- Update storage documentation and the separate R8 instrumentation's persisted
  row/checksum, restart and failure checks to SQLite. Do not move live WAL files
  or open/close the lifetime lock inode for fault injection.

## Actual results

Windows/JDK 25.0.4, strict `--configuration-cache
--configuration-cache-problems=fail`, no build cache. First attempt used
`--rerun-tasks`; the corrective run reran the modified app test task and completed
Release assembly. Kotlin compilation used in-process mode.

| Suite | Passed |
| --- | ---: |
| SqliteRunHistoryTest | 9 |
| SqliteRunAccountingTest | 4 |
| AndroidAgentOwnershipTest | 4 |
| AndroidSqliteConfigurationTest | 3 |
| HostToolExecutionServiceTest | 79 |
| AndroidAgentStoreTest | 12 |

111 tests passed across the applicable runs, no skips. The first attempt had one
new fixture failure: a positional `true` marked a call cancelled before execution
while the test expected unknown-tool rejection. Corrected the fixture to the
named `cancelledBeforeExecution = false`, retaining the rejection assertion;
the complete affected 12-test class then passed. The other 99 tests' sources and
production source were unchanged. Both attempts' logs/XML remain outside Git.

`ledger-runtime-acceptance-test.py`: 5/5 normal and 5/5 with `-O`, offline only.
Final `LedgerInstrumentation.java` compiled against Android 36; Java source/target
8 emitted obsolete-option/bootstrap warnings. Instrumentation did not run.

`:app:assembleRelease` passed, including R8 and lintVital. Release environment
variables were stripped; the existing local debug-signing fallback was used.
No signing or keep configuration changed. Mapping obfuscates `AndroidAgentStore`
and retains the existing explicit host runtime methods.

APK SHA-256: `7fb3f8858a514c3e21ae482d464e34fa39dc9cbb0a9004b77c09740323ae3e5d`

Mapping SHA-256: `89457475c8ab404b476f87c3eacade2314290c57c15f1d433ad57f0f6d690bb2`

`git diff --check` passed. Production sources and active scripts have no references
to the three removed legacy implementation names. Historical evidence keeps its
original names and hashes. Protected DESIGN/PROTOCOL already contain no obsolete
file-format requirements needing this change; they remain unchanged.

## Pending acceptance

No phone/emulator operations, force-stop, API33 runtime, physical power loss or
final-head CI acceptance occurred here. JVM adapter tests use bundled desktop
SQLite and an injected context; they do not prove Android framework behavior.
The updated host-only R8 instrumentation does not prove Android session recovery.
The final-head reviewer must supply the GPU regression and combined session/ledger
Release recovery smoke evidence described in
[ANDROID_STORAGE_ACCEPTANCE.md](../../ANDROID_STORAGE_ACCEPTANCE.md). #24 owns
the production chat/session consumer. No final device acceptance is claimed.
