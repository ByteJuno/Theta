# Android agent storage acceptance

The production entry is `AndroidAgentStore`, opened once by application startup
through the host bridge. Ledger, contents and session history share the system
SQLite database under `Context.noBackupFilesDir`. See [storage semantics](LEDGER_STORAGE.md).
No legacy JSON/directory import is performed; legacy files are not deleted.

## Automated local checks

- `AndroidAgentStoreTest`: real bundled SQLite behind the Android adapter, shared
  ledger/history cold recovery, uncertain writes, no-backup root and legacy-data
  preservation. Its context/driver injection does not test the Android framework.
- `AndroidAgentOwnershipTest`: exclusive owner, trusted storage aliases, rejected
  close with active run/spool and failure admission.
- `AndroidSqliteConfigurationTest`: per-connection configuration contract double.
- `SqliteRunHistoryTest` and `SqliteRunAccountingTest`: transactional history,
  recovery, counters and corruption checks previously named after file storage.
- `:app:assembleRelease`: existing R8 configuration, without new keep-all rules.
  Mapping retains the explicit runtime wire methods and obfuscates the adapter.

## Dedicated reviewer device window

Run the final-commit GPU emulator regression through the existing reviewer
pipeline. A successful build or JVM test is not device acceptance.

The standalone `scripts/ledger-runtime-acceptance.py` builds a separate test APK
and calls the installed Release APK's explicit runtime contract. It verifies
launcher initialization, schema rejection, execution/actual exit, persisted
SQLite checksums, force-stop/reopen, non-replay, missing run-catalog admission,
SQL write failure and sticky startup failure. It compares logical rows rather
than whole WAL/database files. Fault injection exists only in the test APK.

The script's historical device guard permits only `emulator-5588` with its named
AVD and clears host app data. That emulator is reserved under the current
unattended rules: this development round must not run it or relax the guard.
Use an explicitly allocated device window or an approved reviewer equivalent.
Record the exact source commit, APK SHA-256, mapping SHA-256 and raw result path.

Session cold recovery currently has JVM adapter coverage; the production chat
consumer belongs to #24. Before accepting #79's combined Android session/ledger
smoke requirement, the reviewer still needs final-APK session writes and cold
recovery evidence. Do not count the host-only instrumentation or a synthetic
session row as proof of that scenario. API33, vendor firmware and physical power
loss remain separate unverified cases unless actually exercised.

`docs/DESIGN.md` already specifies the common SQLite store, WAL/FULL, no backup
and no legacy migration. `docs/PROTOCOL.md` specifies logical history/recovery
semantics without prescribing the removed per-record file format. No protected
design, protocol, signing, release or wrapper changes are needed for this cleanup.
