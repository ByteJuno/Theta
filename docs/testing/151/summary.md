# Issue 151 validation

Implementation: `3fc4a5e6b088ee7c2b83957c46fc1ff728451c96`
Base integrated before final validation: `66751d7913632b3b5615fe5e48ffc5f4afc140dc`.

Managed entry adapters can open new or existing durable chat sessions, send follow-up messages, and receive text deltas, tool activity, approval notifications and sanitized terminal outcomes. They share the application chat mutex, frozen run configuration, foreground admission and ledger. Revoked/closed entries cannot send/open; closing a listener preserves already-admitted runs and durable history. Bounded per-handle event delivery does not block the kernel, and overflow produces an explicit failure. Existing SDK signatures remain; two read-only event interfaces are added.

Validation on the committed sources:
- Required `python scripts/preflight.py` passed (234.26 seconds), with `--configuration-cache --configuration-cache-problems=fail`; configuration-cache entry stored.
- App: 721 tests, 720 passed, 1 platform skip, 0 failures/errors. Includes 8 entry-kernel cases and 5 entry-runtime cases. Before main integration, the same 13 targeted tests passed; final App suite reran them on the merged sources.
- Core: Gradle `UP-TO-DATE`; reused prior 628 tests (627 passed, 1 platform skip). All 119 core/build input files match the prior validated manifest from run `0b2072d2-e9c6-4a5b-ae2a-c3fd8b63b138`; no claim of a fresh core test execution.
- `:sdk:test` passed with NO-SOURCE; `:app:lintDebug`, 537 paired UI resource keys and storage-fixture source verification/compile passed.
- Immediately before commit, `python scripts/preflight.py --static-only` passed. All 483 committed file blobs match the final tested worktree, allowing Git newline normalization. `git diff --check` passed. SDK public JVM descriptors were recorded; existing declarations were retained.
- Coverage includes real kernel event order, explicit approval without execution, busy/concurrent rejection, existing/missing session binding, missing model/store failures, foreground failure, follow-up messages, idempotent close, collector cancellation, revoke-during-open and bounded overflow.

Limits: No APK generated or APK SHA. No phone/emulator operation, visual acceptance, API33 runtime, R8/APK plugin linkage, live third-party provider or physical power-loss test. New-head CI, independent review and prescribed emulator regression remain pending with the reviewer. JVM tests use real ChatSessionOwner, SQLite and host tool execution with a controlled model provider; they do not establish real endpoint/device acceptance.

Evidence remains outside Git in `E:\Desktop\AI研究\theta-auto-loop\event-runs\bf42fbc3-9f2e-4ab7-b5ef-d57dc4e9f75d`; existing compiler/native-access/deprecation warnings and platform skip are retained. No protected files changed and no test summary committed by the writer. Publication and the unique summary commit belong to the existing dispatcher.
