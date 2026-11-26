# Issue 150 validation

Implementation SHA: `0f6efb8af81920446b67d9dda21e66294371a405`
Branch: `auto/150-schema-profiles`
RETURN: https://github.com/ByteJuno/Theta/pull/160#issuecomment-7183634748
Observed remote head: `77c02a045ffaec1eac1feb49a559ce9b72f553c0`; integrated main: `d05a9101d6a43df9bc2253f67e265bec9b4b3bf6`.

The provider catalog exclusion now becomes a durable execution rejection before any tool claim. Original Schema and FrozenDefinition digest remain intact. Mixed tool results, real SQLite reopening, pending-step continuation, history encoding, forced selection before transport/credentials, and existing truncation cancellation semantics are covered by seven regression tests.

- `python scripts/preflight.py` passed with `--configuration-cache --configuration-cache-problems=fail` and existing Android path/sdk options. Final full execution: core 611 tests (610 passed, one platform skip), App 687 (686 passed, one platform skip), no errors/failures; 156.267 seconds. Subsequent precommit run after upstream documentation integration passed in 8.353 seconds, with unchanged test/compile/lint tasks up to date and configuration cache reused.
- GPU entry `/opt/homelab-report/theta-gpu-test :agent-core:test --tests '*SchemaAdmissionTest' --tests '*SchemaProfilesTest' --configuration-cache --configuration-cache-problems=fail --console=plain --max-workers=4`: 21 passed, zero failed, 45.107 seconds including transport. Source matches this implementation except the subsequently integrated upstream docs/UI_DESIGN.md; all production/test/build inputs are verified identical.
- lintDebug, storage-fixture source verification and compileDebugKotlin passed; 497 English/Chinese keys verified.
- All 463 committed files verified against local/GPU manifests using Git blobs with standard CRLF/LF normalization. Upstream UI_DESIGN.md retained identical Git content, including its Markdown hard break. No raw evidence, Probe files or testing summary were committed by the writer.

Failure evidence retained: first preflight failed on a missing fixture brace; second failed one new assertion expecting uppercase rather than the existing lowercase wire envelope (fixed with exact parsed status/effect assertions). First GPU package exited 127 due to Windows CRLF launcher; Linux validation uses unchanged Git shell blobs. Next GPU run reproduced the wire-case assertion error; final runs passed. Initial archive-based binding was discarded because export conversion changes some blobs; direct git-show verification of every file passed. Logs/XML remain in `E:\Desktop\AI研究\theta-auto-loop\event-runs\bddce37f-f386-45d9-99c4-d811852acc27`.

APK: not generated; SHA256: not applicable. Phone/device accessed: no. Emulator/visual/API33/R8/physical power-loss/live third-party endpoint acceptance: not performed. Deterministic transport establishes run/ledger behavior, not external-provider compatibility. Historical invocations are not rewritten. CI, independent review and prescribed final emulator regression are pending publication and remain reviewer-owned. No push, PR update/create or GitHub merge was performed by this writer.
