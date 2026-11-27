# Issue 159 validation

Implementation SHA: `daa9e776675d168e0319d9d17f7f5e4785e94eb6`
Tested Git tree: `e4d414da7582acf0e0176620ab48dae548084947`
Base / fetched origin/main: `2cd1b6d78f8fe44aa2937f0d666129718df71073`
Branch: `auto/159-provider-model-bindings`

## Scope

Encrypted v3 provider/model/binding catalog and UI; v1/v2 migration preserving independent credentials, prompts, parameters and shared/separate image configuration; legacy control characters in model IDs; lock-protected recovery re-read; quick-switch persistence gate and retryable errors; per-run frozen chat and image configuration. Includes #145 recovery, switching and migration defects. New bilingual strings are paired in providers_strings.xml. No protected files, signatures, wrapper or testing summaries changed in the implementation commit.

## Verification

- `python scripts/preflight.py` with `--configuration-cache --configuration-cache-problems=fail`: PASS, 215.7 seconds.
- `:app:testDebugUnitTest`: 736 tests, 0 failures/errors, 1 skip (`PluginUpdatesTest.snapshotKeepsExecutableBit`, Windows POSIX permission limitation).
- `:agent-core:test`: 632 tests, 0 failures/errors, 1 skip (`SummaryWorkspaceTest.link plus parent traversal cannot clean a different active session`, Windows symlink limitation).
- Seven `ProviderConnectionsTest` regressions pass: legacy whitespace ID; independent v2 credential slots/prompts plus shared/separate image migration; multiple models and frozen slots; recovered versus unreadable replacement; blocked/failing/retried selection; endpoint/protocol isolation; same-model distinct image parameters and image-only installs.
- `:app:verifyUiResources`, `:app:lintDebug`, `:samples:storage-fixture:verifyStorageFixtureSources`, `:samples:storage-fixture:compileDebugKotlin`: PASS.
- Earlier targeted provider/preset/credential tests: 61/61 passed before the additional migration edge-case test. The full preflight above supersedes that candidate coverage.
- Initial compile/resource invocation failed on three hardcoded UI text expressions. They were corrected into resources/helpers. Original failed log: `validation-issue11/issue159-b0127-compile/gradle.log`; subsequent compile/resources passed.
- Final fetch verified origin/main already contained in HEAD; no existing remote branch for this new issue. Capacity was 1/2 (owner-managed PR164); no remote push, PR creation or merge performed by this round.

## Artifacts and limits

APK: not generated; APK SHA256: not applicable.
No APK was generated. Actual rendered UI (small screen, large fonts, English/Chinese, light/dark themes), live model discovery and image generation, device migration/cold start, API 33 and R8 runtime behavior were not verified. CI, independent review and formal emulator regression remain pending publication. No phone or emulator was accessed.

Raw local evidence: `E:\Desktop\AI研究\theta-auto-loop\event-runs\b0127f26-4937-4187-ad45-80227764cc1f` (preflight log/result, module test manifests, new regression XML); compile and targeted logs under `validation-issue11/issue159-b0127-*`. These raw files are not committed. UI pattern reference: Android Now in Android SettingsDialog, blob `b2758e286e198dea6ec9eeae6e2309497662d54b`; adopted full-row checkbox semantics and scrolling settings groups, no copied source.
