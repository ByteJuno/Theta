# Issue 150 validation

Implementation source: `74dd3327079a40367f42a49f1eeb4bf5e47c9856`.
Branch: `auto/150-schema-profiles`; PR: #160.
Integrated main: `f2a468d42220520279f583e0fcc33640353d10b4`.
RETURN: https://github.com/ByteJuno/Theta/pull/160#issuecomment-7186226526.

Resolved the Schema validator conflict while retaining bounded local refs, original-schema argument validation, frozen definition identity, MCP header admission, and the immutable excluded-tool execution boundary. Header validation runs before model projection so unsafe original locations cannot be hidden by reference expansion or downgrade. Two new formal integration tests cover valid local-ref/header coexistence and invalid header exclusions in all three profiles. No new UI behavior relative to main.

Actual checks on the committed implementation:
- Required preflight passed in 303.15s with strict configuration cache (reused). Core: 628 total, 627 passed, one Windows platform skip; App: 711 total, 710 passed, one Windows platform skip; zero failures/errors.
- `:app:lintDebug`, `:app:verifyUiResources` (537 English/Chinese keys), `:samples:storage-fixture:verifyStorageFixtureSources`, and `:samples:storage-fixture:compileDebugKotlin` passed.
- Existing GPU test entry passed 38 JVM tests in 57.84s: SchemaProfilesTest, SchemaAdmissionTest and McpClientTest. Deterministic test transport is not a live-provider acceptance result.
- All 479 committed blobs match the preflight worktree and the GPU archive under standard Git newline normalization. Remote branch and latest main were fetched and checked before final validation/commit; both are incorporated.
- No test failure this round. Initial terminal decoding/output truncation and a merge command rejected for missing default committer identity were corrected; original logs are retained outside Git. Commit used the authorized per-command ByteJuno identity.

APK: not generated; SHA256: not available. No APK generated or APK SHA; no phone/emulator, visual, API33 runtime, R8, physical power-loss or live third-party-provider acceptance. GPU entry ran JVM tests in its scratch checkout, not device tests. New-head CI, independent review and prescribed emulator regression remain reviewer-owned and pending.
Visual verification: not performed. This RETURN adds no UI changes; merged MCP screens retain their own evidence, and this run makes no new visual acceptance claim. Historical platform/device evidence remains bound to its original commit. Raw logs and manifests are stored outside Git in `event-runs/0b2072d2-e9c6-4a5b-ae2a-c3fd8b63b138`.
