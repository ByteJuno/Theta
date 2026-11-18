# Issue #62 — recovery cancellation wording

Source commit: `d711c4ecc3bfd0c5dc32aaae2eaec99409bd88f8`, based on main
`2c558ba851517506e3d6128fd157cf00e1746235`. This summary is the only subsequent change.

Missing registrations recovered from a durable pending step now report
`Process interrupted before tool submission: not executed`. The recovery-only entry
uses the existing first-write cancellation transition: status remains `cancelled`,
effect remains `none`, and no execution instance is created. Existing ledger results
are preserved. Ordinary model truncation still reports
`Truncated model turn: not executed`. No record schema or stored-input equality changed.

## Validation — 2026-11-17

Windows / JDK 25.0.4, `:agent-core:test` with `--tests` filters for the following
classes; `--configuration-cache --configuration-cache-problems=fail --no-build-cache
--rerun-tasks -Pkotlin.compiler.execution.strategy=in-process`:

| Test class | Passed |
| --- | ---: |
| InvocationLedgerTest | 30 |
| AgentRunLoopTest | 24 |
| HistoryLedgerConsistencyTest | 7 |
| RecoveryCancellationTextTest | 2 |
| RunRecoveryTest | 16 |
| SqliteRunHistoryTest | 9 |
| **Total** | **88** |

Zero failures, errors or skips. Configuration cache stored; runner time 60.35 s.
Tested source bytes matched the source commit after Git newline normalization;
`git diff --check` passed. No failed validation attempt in this round.

The two new tests cover four fault boundaries in a shared SQLite database (ledger
commit before/after, history append commit before/after), repeated recovery/reopen,
and existing truncation records with both uncommitted and committed pending history.
They assert the projected explanation, cancelled/none, absent execution/approval/
isolation, unchanged existing records, and no duplicate history append. The run-loop
test additionally checks unchanged wording for LENGTH and CONTEXT_EXCEEDED.
Existing missing-row, source/result disagreement and unknown-effect assertions remain.

Raw logs and fresh JUnit XML are retained outside Git in
`theta-auto-loop/validation-issue11/issue62-bcd4325f-targeted`.

## Limits

APK: not generated; SHA-256: unavailable. No phone or emulator was accessed.
The fault tests use controlled JVM exceptions and SQLite reopen, not an Android
process kill or physical power loss. API33 runtime, R8, CI and official emulator
regression were not run here; final-head review, prescribed regression and CI belong
to the independent review pipeline. No app UI changed, so no visual acceptance is
claimed. No protected files, dependencies, scheduler, release or signing setup changed.
