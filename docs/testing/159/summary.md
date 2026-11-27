# Issue 159 validation

Implementation commit: e3497c5d6222ef5e5b2cba76567aad710ec6c841
Tested tree: 18e4bf6662d6c7c58716e15969f545f12e8d6fc8
Base main: 2cd1b6d78f8fe44aa2937f0d666129718df71073
Reviewed remote head before this repair: c9c2c414c2f080fb8fc9962feadcd28b61988578
RETURN: https://github.com/ByteJuno/Theta/pull/168#issuecomment-7213492679

## Change and evidence

New v3 writes use an authoritative atomic record separate from the legacy input, preserving the migration/recovery distinction even when v3 cannot decrypt. Recovery cannot revive old image credentials or bindings. First legacy migration remains independently recoverable. Last-provider deletion and explicit clear preserve the no-import boundary. No protected files or host UI wiring changed.

Full preflight passed in 167.53 seconds with --configuration-cache --configuration-cache-problems=fail. app 750 tests: 749 passed, 1 existing Windows skip. agent-core 633: 632 passed, 1 existing Windows skip; FROM-CACHE. UI resource parity, lintDebug and storage-fixture source/compile checks passed. All 54 provider tests passed, including 8 new tests and the prior 12 ProviderConnectionsTest regressions. Prior cold SQLite image-approval and unauthenticated HTTP-header regressions remain in the full suite.

Initial red run on the old production code: 6 tests, 5 failed, proving legacy image resurrection after deletion, unbinding, replacement, last-provider deletion and explicit clear. First candidate run: all 8 new tests and 12 prior connection regressions passed; 3 other fixtures still targeted active.bin after migration. Those fixtures now target the authoritative record without dropping assertions or changing timeouts; the final full preflight passed.

Evidence is retained outside Git at E:\Desktop\AI研究\theta-auto-loop\event-runs\529b7f53-d565-42e3-ad15-b5ae3dd70950. Reviewer failure: reviewer-failed-test.log and reviewer-verdict.json.log. Local initial failure: theta-auto-loop/validation-issue11/issue159-529b7-red. Intermediate fixture failure: theta-auto-loop/validation-issue11/issue159-529b7-green. Final evidence: preflight.log, preflight-result.json, TEST-io.github.bytejuno.thetacore.provider.ProviderMigrationRecoveryTest.xml and final-ref-check.json. Previous round evidence remains at event-runs/7ba19f43-a644-4320-8299-6a59108ccea7 and its referenced validation runs.

## Limits

APK: not generated; APK SHA-256: unavailable. No phone or emulator accessed. 本轮未生成 APK，未操作手机或模拟器。验证为 JVM 文件存储与合成 AES 密钥证据；Android Keystore 失效、设备冷启动、真实生图请求、API 33、R8 和实体断电未验证。视觉未验证（本轮无 UI 改动）；原 PR 界面的实际渲染仍由审核端覆盖。当前实现的 CI、独立审核和正式模拟器回归待发布后执行。

Fetch before final checks confirmed remote PR branch and origin/main are ancestors. GitHub head/comments were rechecked before commit and were unchanged. This writer only committed implementation/tests; the existing publisher owns summary commit/push/PR publication and the independent reviewer owns acceptance/merge.
