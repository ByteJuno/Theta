# Issue 161 validation

The implementation validation below was supplied by the writer. The reviewer found a
contradictory resource-check failure on that exact implementation; see the correction
and targeted verification at the end of this summary.

Implementation SHA: `8006c2853cde97a7229f2a4c5a4c256774559be0`
Tested tree: `fbbff12fd6bbef2733b8fc67c79dac1b92d82280`
Base/main: `d0b9ea933436e36dd6dac90be2d2a4781f748b0c`
APK SHA: not generated.

- Implemented theme tokens and dynamic/brand themes; app-only chat state/actions; reading flow, drawer, model/persona sheet and integrated composer; typed deletion identity guard; bilingual feature resources and vector icons; shared acceptance navigation.
- Full preflight passed in 174.17 seconds with --configuration-cache --configuration-cache-problems=fail. Fresh app: 847 tests, 7 skipped, zero failures/errors. agent-core: 688 tests, 1 skipped, UP-TO-DATE (not a fresh run). Resource parity 638 keys, lint and storage fixture compilation passed.
- Targeted 22 tests and navigation observation 44 tests passed. Full app includes stale drawer deletion protection. Validation hashes match the committed implementation tree.
- Main advanced from a89fa03f69c3ac45c347d179072419f5f01164d3 to d0b9ea933436e36dd6dac90be2d2a4781f748b0c; fast-forwarded before final validation. The protected protocol change was inherited from main, not authored here.
- Material3 stable 1.5.0 explicitly selected to expose public MotionScheme.expressive API; 1.4.0 failure and official source evidence retained. No new extended icons or SDK ABI changes.

## Limits

未生成 APK（APK SHA 未生成）；未操作手机或模拟器，视觉未验证。需在当前构建验证中英文、小屏/横屏/分屏/大字体、明暗/动态/品牌主题、减弱动画、抽屉可达与删除确认、模型/人设保存失败重试、图片预览/旋转恢复及审批/未知副作用强制展开。44 项导航为 Python 观察夹具测试，不是 S1/S2 设备回归；22 项定向单测中的草稿恢复为真实 SQLite/save-restore 契约，不等于屏幕旋转交互验收。Material3 1.5.0 已编译和 JVM/lint 验证，但第三方插件页面运行兼容性、API33 运行、R8、CI 及审核端正式模拟器回归仍待验证。会话搜索覆盖已加载本地预览，可用更多会话继续加载；不具时间前缀的旧会话归入更早。

## Preserved original failures

- compile-1/compile-2: UI resource checker rejected inline capsule formatting and technical preference/regex strings; moved user text to paired resources and non-UI preferences to feature helper, without changing checker.
- compile-3: BOM-selected Material3 1.4.0 hides MotionScheme expressive API. Official stable 1.5.0 sources confirm public API; app pins 1.5.0 and compilation passes.
- targeted-1: test fixture used nonexistent bindingValue; corrected to actual BindingValue.copyOf(callBinding(...)); no assertion removed.
- navigation-1: old unrelated host-readiness mocks did not intercept newly factored open_plugins, causing failures/hang. Terminated only verified own test PID 45132 (parent 6464), preserved logs/exit 4294967295; scoped mocks updated while direct/drawer/recovery tests exercise navigation. navigation-2: 44 passed.
- Initial commit preparation expected an incorrect fixed path count (31 vs 30); stopped before staging. Exact manifest/path scope checked, no source change or test failure; note preserved.

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\e6473590-27e9-4404-b9ee-e345a63c0c61`. PR184 review cursor and original validation remain separate; trusted reviewer head alignment has no effect on this issue evidence.

## Reviewer correction and verification (2026-11-28)

- Reviewed head: `69582fdf252fd03a93197600442365863e30c261`, round 1, zero previous returns. Its only difference from the writer's implementation SHA was this summary.
- Original app probe attempts failed before running any tests; diagnostic retry identified `:app:verifyUiResources`. An agent-core probe invoking the real `UiResourceCheck` then failed (1 test, 1 failure) on color literals in `ThetaTheme.kt`, beginning with line 28, `#B3C5FF`. This contradicts the writer's preflight result above. Moved the unchanged color-scheme factories into the non-composable theme helper; no checker, assertion or timeout was relaxed.
- Material3 `SheetState` plus the real model-selection controller reproduced dismissal during persistence: the sheet became `Hidden` while the route's open flag remained true, and stayed hidden after saving and another open action. The dismiss callback now always removes the sheet; persistence remains owned by the route. The corrected probe reached `Hidden` with the flag false, reopened to `Expanded` after a simulated save failure, and successfully retried (2 attempts, no remaining error). This is a JVM state-transition probe, not a rendered interaction test.
- Resolved POMs for `androidx.compose.material3:material3:1.5.0` and `material3-android:1.5.0` both declare Apache License 2.0.
- Final GPU run: `:agent-core:test --tests '*ThemeResourcesProbe*' :app:testDebugUnitTest --tests '*MaterialSheetProbe*' --tests '*ChatSurfacePolicyTest*' --tests '*ProviderConnectionsTest.selectionBlocksUiAdmissionUntilPersistenceAndSurfacesRetryableFailure'`; Gradle exit 0, 7 tests, 0 failures. The real resource verifier passed. Probe files remain untracked and are not included in this PR.
- Verified tracked source tree before this summary update: `9be060dac66633d3f4dcc0e4bcff780b010ca92d`. No APK generated; visual, device and final emulator regression coverage remain as stated above. No full-suite or lint rerun is claimed for the reviewer changes.
