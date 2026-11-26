# Issue 136 validation

Implementation commit: `1dad56bc5a1633adf3b32b4987ffc23bdbf20b88`.
Reviewed parent: `65596833e1df5b510019168d5c171290ff428b63`.
RETURN: https://github.com/ByteJuno/Theta/pull/149#issuecomment-7181556232

Changes and checks:
- Unknown-tool errors after input_required retain FAILED/UNKNOWN, including accept and decline. Host-ledger regression confirms no automatic replay, rejects unattended same-argument retry and preserves prior effect. First-request unknown-tool rejection remains REJECTED/NONE.
- MCP awaits the first existing PluginCrashRecovery admission decision, honoring manual skip_plugins and automatic skipData escalation. Startup, reload, enabled save, enable and refresh cannot bypass it. Both attach-before-restore and restore-before-attach are covered: blocked modes produce zero HTTP requests and zero registered MCP tools; normal mode loads tools; resume stays next-cold-start only.
- Scalar form title/description/required/enum types are checked before rendering. Unsupported forms remain declinable; presentation helpers avoid unsafe casts. Valid scalar enum values retain their JSON types.
- Header scanning visits only schema nodes. const/enum literal objects are preserved while invalid annotation positions remain rejected.

Full preflight passed in 173.21s with strict --configuration-cache --configuration-cache-problems=fail (cache stored), lintDebug, storage-fixture checks and 526 English/Chinese resource keys. Agent-core: 604 tests, 603 passed, 1 platform skip, 0 failures/errors. App: 691 tests, 690 passed, 1 platform skip, 0 failures/errors. Local targeted regressions: 55/55 in 109.44s. Existing /opt/homelab-report/theta-gpu-test entry: 55/55, GRADLE_EXIT=0, 54.54s. GPU results are JVM tests, not device/emulator rendering.

Test snapshot matches all 14 committed source files allowing only Git CRLF-to-LF normalization. Raw logs/XML/source archive and reviewer failure history remain outside Git in this event run. Initial encoding/output issues and the post-commit normalization assertion were resolved; no code/test failure occurred this round. A draft publication guard mistakenly matched English prose, and was corrected to validate actual closing references before writing the handoff.

APK: not generated; SHA-256 unavailable. Visual verification: not performed. Pending exact-build reviewer checks: input form unsupported/decline path, recovery notice and disabled MCP controls, both languages, small screen/large font/themes. Actual device cold-launch ordering, API33 runtime, R8, physical power loss and third-party interoperability remain unverified. No phone/emulator operated. New-head CI and prescribed final emulator regression remain pending the existing reviewer; prior-head results are not relabeled.
