# Issue 178 validation

- Implementation SHA: `ef0328d795209cb68e78c39eea761dc2acee7bde`
- Branch: `auto/178-screen-tools`; base/fetched main: `49097146a429dc3a3a5cb245be928b69a37530e8`.
- Tested Git tree: `79bf4e7f62f58ca60e5e0e1596d8b1f19d544e40`. Commit contains the exact tested tree; no testing summary committed by the writer.
- APK: not generated; APK SHA: not generated. Device/visual acceptance: not performed.

Implemented nine built-in screen tools alongside unchanged screen_info, Root-first routing with safe Accessibility fallback, standard disabled-by-default Accessibility service, bounded image/tree/app data, Unicode clipboard/set-text paths, session-scoped image storage and model request media projection, paired strings and overlay capture hooks. All tools share the existing screen lease; writes retain UNKNOWN on cancellation/timeout until confirmed actual exit. Root clipboard replaces the clipboard and pastes at selection; Accessibility replaces focused editor text.

Validation on this exact tree:
- `python scripts/preflight.py` passed in 148.1s with strict configuration cache reused.
- `:app:testDebugUnitTest`: 930 tests, 922 passed, 8 skipped, 0 failures/errors; executed this final preflight. Screen suite: 14 cases, 13 passed, 1 Windows native-graphics skip. Includes real host SQLite/ledger resource and session-blob integration, model codec image bytes/size-estimator check, Unicode and backend routing.
- `:agent-core:test`: UP-TO-DATE, unchanged inputs; existing 720 tests / 719 passed / 1 skipped, not rerun this round.
- `:app:verifyUiResources`: 733 paired English/Chinese keys.
- `:app:lintDebug`: 0 errors, 109 warnings.
- Storage fixture source verification and `:samples:storage-fixture:compileDebugKotlin`: passed/UP-TO-DATE in preflight.
- No protected-file changes; no diagnostics, APKs or temporary fixtures committed.

Failures retained outside Git:
1. First targeted run: test compile references used wrong ledger/protocol fields; corrected to current APIs and identified missing image-to-model projection.
2. Second run: Windows native Bitmap library UnsatisfiedLinkError; no image assertion had run. Native test remains active on supported hosts and is explicitly skipped on Windows, with a separate platform-independent size-budget check.
3. Third run: new JUnit method inferred a non-void result; corrected the test return type.
4. First full preflight: media codec test generated a second random call ID instead of using the persisted binding. Fixed the fixture to use the original binding; all assertions retained. Final preflight passed.
Evidence: `E:\Desktop\AI研究\theta-auto-loop\event-runs\b4914cdc-dc1c-4346-a725-e8946dd22b36` and sibling `validation-issue11/issue178-b4914cdc-*` directories.

Limitations: 未生成 APK（APK SHA 未生成）；未操作手机或模拟器，视觉未验证。Root screencap/uiautomator、app_process 剪贴板 Binder/UID 与 ROM 限制、真实中文粘贴、无障碍手势/截屏回调及服务断开行为仍需当前构建设备验收。Windows 原生图形库不可用，原生 Bitmap 编码测试 1 项明确跳过，需 Linux 审核环境执行；JVM 尺寸预算测试不能替代像素验证。未运行新 head CI、S1/S2、R8、签名或 API33 设备测试。core 输入未变，720 项为 Gradle UP-TO-DATE 的既有 XML 结果，不是本轮重跑。

Required reviewer continuation: current-head CI and prescribed S1/S2/signature checks, Linux native bitmap test, then authorized emulator checks of Root and Accessibility capture/tree/input/action paths including callback cancellation and Unicode. No real phone was accessed. PR190's separate RETURN remains queued, independent of this issue.
