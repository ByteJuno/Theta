# Issue 60 — surrogate tool rounds

Source commit: `bfa7e6f4cfb17cb7ace68ade49352a739127ab7c`.
Base: `2c558ba851517506e3d6128fd157cf00e1746235` (merged PR95).
APK: not generated; SHA-256: not applicable.

The history writer retains lone UTF-16 code units in assistant text, reasoning and
opaque replay using its existing escaped, framed encoding. Tool arguments, names
and binding fields retain the strict admission check. The run loop checks the
whole batch before pending intent or ledger registration and returns PAUSED with
an explicit reason when those fields contain an unpaired surrogate. It neither
registers valid siblings nor fabricates tool results. Original response evidence
remains in the configured RunJournal; MemoryRunJournal is not durable storage.
Possible server execution retains its durable reconciliation barrier.

## Actual validation

Windows / JDK 25 / bundled SQLite JVM driver, 2026-11-17:

```text
python -X utf8 -B ../run-validation.py issue60-aa03b04c-regression :agent-core:test
  --tests '*SurrogateToolRoundTest'
  --tests '*AgentRunLoopTest'
  --tests '*RunRecoveryTest'
  --tests '*HistoryLedgerConsistencyTest'
  --tests '*SqliteRunHistoryTest'
  --tests '*HistoryStorageRegressionTest.invalidIntentNeverWritesAndReopenedSessionRemainsUsable'
  --tests '*HistoryStorageRegressionTest.usedIdentityAndBlankStepAreRejectedBeforeIntentButInvalidArgumentsRemainEvidence'
  --tests '*HistoryStorageRegressionTest.framedHistoryPreservesLoneSurrogatesInResultsWithoutChangingLedgerTruth'
  --tests '*HistoryStorageRegressionTest.committedStepWithLoneSurrogateResultStillClearsPendingAfterLedgerReload'
  --tests '*HistoryStorageRegressionTest.differentCommittedResultStillFailsClosedOnLedgerComparison'
```

The helper supplied `--configuration-cache --configuration-cache-problems=fail`;
the configuration cache was stored. 66 tests passed, zero failures/errors/skips;
26.66 seconds from the validation report. `git diff --check` passed. Tested source
bytes were matched against the implementation commit, allowing Git CRLF/LF
normalization only.

Coverage includes:

- Text, reasoning and all replay fields preserved across a completed tool round,
  provider replay, SQLite close/reopen and the next run without re-execution.
- High/low surrogate arguments, invalid names, wire/response/provider bindings:
  memory and SQLite history pause before registering any sibling; paired history
  and revision remain unchanged, no pending intent, no execution/cancellation.
  Direct invalid-intent tests also cover request binding and run identity.
- Reopen after pending intent, completed ledger registration before append, and
  lost acknowledgement after append commit: exact assistant code units survive;
  ledger terminal truth is retained and pending clears exactly once.
- Possible server execution remains blocked after reopening; valid emoji and
  literal JSON escapes remain accepted. Existing history/ledger mismatch,
  truncation, cancellation and recovery assertions pass.

The first targeted attempt ran 6 tests, with 4 passing and 2 new fixture failures:
the fixtures incorrectly assumed a caller request ID survived the loop's UUID
generation. Expected bindings now come from the actual journal, and unreachable
request-ID injection is covered directly at the history boundary. Original
failure XML/logs and final results remain outside Git under
`validation-issue11/issue60-aa03b04c-{targeted,regression}`.

## Limits

JVM tests and deterministic storage fault injection are not Android/process-kill
acceptance. No APK, device, API33 runtime, R8 or visual validation was run; this
change adds no UI. Formal final-head review, emulator regression and CI remain
the independent review pipeline's responsibility. No protected files changed.
