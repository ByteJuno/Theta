# Issue 59 verification

Implementation: `0951dcaaf764ec910a1ebb4daeaeecea3f83ef1e`, based on
`9334f8bd3c6ffd6dc484e06f384c055e0d550c2a` (`origin/main`). Debug APK SHA-256:
`0de1f2fa9942855a5223a317374c94bd060286ba759b852753c730424b48faa0`.

`ThetaApplication` now owns a single lazy process-level `OkHttpModelTransport`
(`modelTransport`) and injects it into `ChatSessionOwner` via
`sharedTargetFactory(modelTransport)` so chat runs reuse one `OkHttpClient`
connection pool instead of allocating a fresh transport per run.
`ChatSessionOwner`'s default `targetForRun` also lazily creates and shares a
single `OkHttpModelTransport` across runs rather than calling
`OpenAiRunSnapshot.target()` without a shared transport.

Windows/JDK 25: 83/83 JVM tests passed, no failures/errors/skips, in one
invocation (`issue59-green6-20261124`):
- `SharedTransportIntegrationTest` (3): verifies injected transport instance
  identity across multiple runs and credential updates (`assertSame`), verifies
  `ChatSessionOwner`'s default target factory reuses one HTTP/1.1 keep-alive TCP
  connection across consecutive runs (`connections == 1`, `requests == 2`), and
  runs an end-to-end integration test from `OpenAiSettingsStore` (real AES-GCM)
  through `snapshotForNewRun()` and a shared `OkHttpModelTransport` into
  `AgentRunLoop` with real SQLite history/ledger and a host tool call across two
  runs (`connections == 1`, `requests == 3`, zero secret leakage).
- `ChatSessionOwnerTest` (60).
- `OpenAiSettingsStoreTest` (20).
`:app:assembleDebug` and `:app:lintDebug` passed in the same invocation (66.31s,
0 errors) with `--configuration-cache --configuration-cache-problems=fail` (cache
reused).

Limitations: no external internet endpoints or real credentials were used; only
local loopback HTTP/1.1 sockets were exercised. Physical device, Android API 33
runtime, Release/R8, and emulator instrumentation were not run locally.
Final-head independent review, prescribed regression, and CI remain required
before reviewer merge. Protected files are unchanged. Raw logs and XML are
retained outside Git.
