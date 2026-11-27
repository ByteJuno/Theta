# Issue 159 validation

Implementation commit: 3797d0969a3843563500508109fd6ca2cd22ba9c
Tested tree: c4331e9cb4c0398a824644d709f5249e070592c6
Base main: b26215bd10ce37ede1d714c5e3e84a5cd3c25b5b
Reviewed remote head: 7a1b25c9103723ced8f9498765534aa6a5844e74
RETURN: https://github.com/ByteJuno/Theta/pull/168#issuecomment-7216760139

## Current result

Integrated reviewer fix cd661fd43226effdd27f08c21359607fa242f22e before merging current main. Resolved ChatScreen conflicts by retaining session-bound text/image drafts, picker targeting, accepted-draft receipts and the model-binding persistence gate. Combined both ChatSessionOwnerTest fixtures, keeping injectable image validation, frozen image snapshots and all 69 test methods (61 in the PR parent, 68 in main, one PR-only test). No assertions were removed and no timeouts expanded. Existing authoritative v3 recovery, host-bound credentials, independent image bindings and no-auth key-recovery fixes remain intact.

Full preflight passed in 220.38 seconds, using --configuration-cache --configuration-cache-problems=fail. App: 793 tests, 0 failures/errors, 1 existing platform skip. Agent-core: 633 tests, 0 failures/errors, 1 existing platform skip. Both test tasks executed; configuration cache reused. UI resource verification, lintDebug, storage-fixture source/compile checks passed. SDK test task was UP-TO-DATE / NO-SOURCE. Manifest comparison confirmed the tested source did not change.

The same tree passed 115 scoped JVM tests on TEST_HOST=gpu with zero failures in 99.06 seconds. Scope: ChatEntrySessionsTest, ChatSessionOwnerTest, PluginEntryRuntimeTest, ProviderMigrationRecoveryTest and ProviderConnectionsTest; command also included the existing profile/recovery-name filters. This is JVM/SQLite evidence, not a rendered UI or device acceptance result.

Final fetch before validation confirmed PR head and main unchanged. GitHub comments and PR head were rechecked before commit; no later RETURN or OWNER appeared. The merge commit contains both verified remote parents. No protected files or new testing summary were authored in this round; the main-parent testing documentation is inherited. The writer did not push, update/create a PR or perform the reviewer merge.

## Preserved evidence

Current evidence directory: E:\Desktop\AI研究\theta-auto-loop\event-runs\d5124f1b-c7c6-47c9-9f56-3ebc48590cb4
- preflight-final/command.json, gradle.log, result.json, tested-worktree-sha256.json and XML results.
- affected-final.json, affected-final.log and source archive: the exact staged tree used remotely.
- final-refs.json, commit.log and GitHub/reviewer snapshots.
- pr168.stderr.log: initial TLS handshake timeout; the next read succeeded.
- merge-main.log: original two-file conflicts, followed by verified resolutions.

Previous migration evidence remains at event-runs/529b7f53-d565-42e3-ad15-b5ae3dd70950 and event-runs/7ba19f43-a644-4320-8299-6a59108ccea7. It includes five failing cases from six initial old-code tests, fixture-path failures, reviewer failures and eight added migration/recovery regressions. Reviewer round 3 added two no-auth key-recovery regressions in cd661fd and preserved ProviderClearRefillProbe/ImageOnlyNoAuthMigrationProbe failures and subsequent success outside Git. Those earlier results are historical, not transferred as validation of this commit. Current full-suite and scoped results above validate this round's tree.

## Reviewer audit of 95d46cdf (round 3; two prior returns)

The implementation tree above matches the incoming head except for this summary. The earlier full-suite results are retained for that implementation; they are not relabeled as a full run after the reviewer change.

The GPU-only `ProviderLiveImageSnapshotProbe` passed (1 test): while an image call waited for approval, its connection was changed and deleted. The admitted call still used the original endpoint, model and credential; its snapshot was released after completion. The next run returned configuration-required with effect NONE, without another generation request or an attempt claim.

`ProviderMigrationNamesProbe` initially failed both tests: an imported image connection displayed the hard-coded English name `Images`, and a control-only legacy model displayed `Model`. Migrated editable names now derive from the saved model identifier, falling back to the valid provider identifier. Request model IDs, credentials and bindings are preserved. The probe plus ProviderConnectionsTest, ProviderMigrationRecoveryTest, ProviderProfilesTest and OpenAiSettingsStoreTest passed on TEST_HOST=gpu (49 tests, 0 failures). Existing migration tests retain their assertions; a regression covers the control-only name through cold reopen. Both provider resource tables contain the same 32 keys. Probe source and raw outputs remain outside Git; the two original failing results remain recorded here.

## Limits

APK: not generated. APK SHA-256: unavailable.
未生成 APK，未操作手机或模拟器。视觉未验证：聊天草稿及图片输入、模型切换期间禁用状态、服务商/模型选择器在中英文、小屏、大字体和明暗主题下的实际渲染仍待审核端验证。JVM/SQLite 与合成密钥测试不证明 Android Keystore 硬件行为、设备冷启动、真实生图服务、API 33、R8 或实体断电行为。新提交的 CI、独立审核与正式模拟器回归由发布后的审核端执行。
