# Issue 136 validation

Implementation source commit: `c0085726980e339e1d6161f7749dd4971cc37dbb`
Base main: `6d11d8191ced3d77188710e9e58bd3574137a85f`
Reviewed predecessor: `70ab21f208b6e43c2d1d631cbbb627fecf713acd`
RETURN: `IC_kwDOVkrS_s8AAAABq_kcbQ`
Owner scope decision: 2026-11-26T02:02:12Z, input_required accepted; OAuth deferred.

Credential recovery, HTTP error fallback, explicit session renegotiation, host/chat input accept/decline/cancel, unattended decline, and localized progress are implemented. Session failures retain UNKNOWN effects and close admission; no automatic tools/call replay. Existing key failure classification and encryptForReplacement are reused; read/transient failures preserve ciphertext and keys.

## Actual checks

- `python scripts/preflight.py` with strict `--configuration-cache --configuration-cache-problems=fail`: passed in 150.51 seconds; configuration cache reused.
- agent-core: 603 tests, 602 passed, 1 platform skip, zero failures/errors.
- app: 683 tests, 682 passed, 1 platform skip, zero failures/errors.
- lintDebug, storage-fixture source check/compile and 525 English/Chinese resource keys: passed.
- Designated GPU test entry: 36 targeted tests, zero failures, 52.91 seconds. Same source hashes; 256 MiB test heap. Packaging used the unchanged Git LF gradlew blob because the Windows checkout contains CRLF.
- Working source SHA-256 manifest matches the tested worktree; committed source blobs match Git-normalized worktree bytes. Protected files and docs/testing/136/summary.md were not changed by the writer.
- No dependency additions. Pinned SDK review and storage decision are documented in the PR body; no upstream source copied.

## Failure history retained outside Git

The first targeted run failed UI literal checks on JSON field names in the composable source. Protocol parsing moved into the presentation helper. Two session-test fixture attempts were blocked by existing ledger UNKNOWN/admission and monotonic-order rules; corrected preparation ordering preserved the gate and assertions. Initial GPU launcher returned 127 before tests due to CRLF; LF packaging retry passed. No assertion deletion, timeout enlargement, empty commit, or device-result substitution.

## Limits and remaining evidence

APK: not generated; SHA-256: not applicable. No phone or emulator accessed. Visual behavior is unverified: credential replacement confirmation; form accept/decline/stop; English/Chinese progress; small screen, large font, light/dark themes. Formal new-head CI and dedicated-emulator regression belong to the independent reviewer. No API33 runtime, R8 release, physical power-loss, or third-party MCP service acceptance claimed.

Only scalar form elicitation is displayed. Unsupported schemas/modes fail closed or can be declined; sampling, URL/OAuth, state-only load shedding and arbitrary nested/multiselect form rendering are outside this implementation. Eight input rounds and existing execution budgets bound interaction; input waits do not pause execution budgets. UNKNOWN side effects require the existing reconciliation process.

Raw evidence remains in event-runs/9f0c2389-3007-45e7-a04a-6c62479d1351/ and validation-issue11/issue136-9f0c2389-*. It is not part of the repository.
