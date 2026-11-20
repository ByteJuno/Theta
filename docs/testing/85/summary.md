# Issue 85 — production checkpoint recovery

- Source: `1d1f358e82d0053f8037a9940c5433f28211cfa7`; tree `1a113f637206ab7184dffdffd22bf787b541ece5`.
- Base: `a9194436561fd2a3f3e581fd95f1e4af38012b90`; dependencies #24 and #79 verified CLOSED before selection. Original issue85 stash remains intact and was not applied.
- Debug APK SHA-256: `b295882175fc37b4be3354deee725d9d6347f18076cdc5adcb605a97a82ea65b`. Built from the source above; this summary-only commit does not change APK inputs.

## Delivered

The existing ChatSessionOwner and unified Android SQLite store now drive distinct attended actions: exact-evidence retry confirmation, larger-window readmission without summary calls, and explicitly acknowledged summary generation/continuation. The kernel-issued next identity and cumulative budgets remain intact. Tokens bind session/run, revision, checkpoint, reconciliation and context; stale, duplicate, cross-session, unconfirmed and unattended selections are rejected. English/Chinese recovery cards and scrollable confirmation dialogs expose technical details separately from normal explanations.

Durable accounting carries attendance. Existing summary settings retain their context window; window-only readmission does not enable summaries. Legacy ambiguous identity fails closed rather than being promoted to attended. The send path also cannot promote a paused unattended run. No new runner, scheduler or file storage.

## Actual checks

- 101 targeted cases passed, 0 failures/errors/skips across retained applicable runs: final app `ChatSessionOwnerTest` 26 + `ChatRecoveryTest` 3; unchanged core `ChatRecoveryIdentityTest` 3 + `SqliteRunAccountingTest` 4 + `ContextPolicyTest` 65.
- Final app tests: `issue85-c68718a2-final-tests`, 47.37s, configuration cache reused. Core source/tests unchanged since `issue85-c68718a2-r2` (combined run 83.71s; that run had app failures, not a global pass).
- Final build: `issue85-c68718a2-final-build`, 74.83s; `:app:verifyUiResources :app:lintDebug :app:assembleDebug` passed. Strict `--configuration-cache --configuration-cache-problems=fail` in every run.
- Lint: 0 errors / 27 warnings. New window-unit plural suggestions concern values constrained to >=1024; old checkpoint-label resource is no longer used. No lint suppression added.
- Initial duplicate-session fixture failure preserved. Repeated 10-second large-history failure led to eliminating redundant checkpoint reads and active recovery-token construction, not extending deadlines. Isolated unchanged deadline passed in 6.846s and final full targeted app run passed. Unattended fixture catalog mismatch corrected without weakening assertions.
- `git diff --check` passed; protected files unchanged. Raw logs/XML/APK and source digest manifest live outside Git under `event-runs/c68718a2-0cb7-4bf0-9ff0-e83c319d837f` and `validation-issue11/issue85-c68718a2-*`.

## Limits and reviewer handoff

**Visual and device behavior are unverified.** No authorized dedicated local renderer/emulator was available; no phone, shared emulator or reviewer emulator was accessed. Source/resource checks and compiled APK are not rendered acceptance. Inspect final-head English/Chinese recovery, long IDs/diagnostics, error/disabled states, confirmation cancellation, narrow screen, large font, light/dark themes and touch targets. Exercise all three paths after cold reopen, exact-once UI selection, larger-window no-summary behavior and unattended absence using the final artifact.

Independent reviewer owns final code review, prescribed emulator regression, CI and authorized merge. No writer merge; no API33 runtime, Release/R8, real-provider or physical power-loss acceptance claimed. PR104 foreground-service integration is separate and pending review; do not attribute its old/new APK evidence to this branch.
