# Issue 177 validation

Implementation commit: `35cca9e66f7e6e579d1229bf90e6d2acb2bd2a66`
Tested Git tree: `6cd1b7bc6ee3da134bb621363b8cc874d8f5173f`
Branch: `auto/177-data-themes`
Base/latest fetched main: `49097146a429dc3a3a5cb245be928b69a37530e8`
APK SHA-256: not generated.

## Implemented

Data plugin `themes/*.json` parsing with per-entry isolation, bounded metadata/colors/corners/styles, WCAG 4.5:1 checks, protected error roles, actual dynamic-palette validation, exclusive persisted appearance selection, exact-selection lifecycle revocation and brand fallback. Chat saved/streaming/retained text, user bubbles and reasoning/tool chips use theme tokens. Theme reading waits for the host restore decision, including safe mode. New English/Chinese strings are paired in plugin_theme_strings.xml. No protected files changed.

## Checks

- Final preflight passed in 140.3s, strict `--configuration-cache --configuration-cache-problems=fail`, cache reused. The committed tree equals the tested tree.
- App: 924 tests, 0 failures/errors, 7 skips (917 passed). All app suites executed in final preflight.
- Core: 720 tests in unchanged-input XML, 0 failures/errors, 1 skip (719 passed). Task UP-TO-DATE, not rerun this round.
- UI resources: 730 English/Chinese pairs. Lint: 0 errors, 109 warnings. Storage fixture source verification and compileDebugKotlin passed.
- Eight new tests cover valid light/dark themes, malformed JSON and values, forbidden safety overrides, WCAG calculations, runtime palette rejection, durable selection and stale-fallback protection, disable/uninstall/replacement/quarantine, actual corrupt-archive rejection, safe-mode startup, and Compose selection/revocation.
- Compose semantics/layout cover English 360dp light and Chinese 320dp dark at 2x font scale. This is not pixel or device acceptance.

## Failures retained and resolved

1. Initial targeted run: 13 tests, 1 failed because ZIP destination parsing rejected duplicate JSON keys before per-theme isolation. Theme parsing was deferred to readThemes; subsequent 13-test run passed.
2. Initial Compose run: two loading timeouts; Robolectric main-loop work was not pumped. Fixed synchronization without increasing timeouts.
3. First two full preflight attempts: 924 app tests with one quarantine UI timeout each. State diagnostics showed stale rendered choices. The test now isolates preference storage, drives snapshot/frame notifications and exercises actual PluginManager corrupt-archive quarantine rather than directly mutating inventory. Final preflight passed all non-skipped tests. Earlier timeout cause was not conclusively reduced to preference storage alone; all original failures are retained.

Local evidence: `E:\Desktop\AI研究\theta-auto-loop\event-runs\2df61b7e-6141-4798-b976-87e745873c8b`; targeted runs under `validation-issue11/issue177-2df61b7e-*`. Raw logs/XML are outside Git.

## Limits and reviewer handoff

未生成 APK（APK SHA 未生成），未操作手机或模拟器。当前构建视觉未验证：Robolectric 仅证明 Compose 布局、语义、回调和持久化，不证明真实字体像素或触摸。英文 360dp 浅色、中文 320dp 双倍字体深色场景有布局交互覆盖；editorial/bubble/compact 完整聊天视觉、中英文/明暗主题下实际截图、安装确认及 S1/S2 节点契约仍待独立审核端当前构建验收。未执行新提交 CI、正式模拟器回归、R8、签名或 API33 真机测试。core 测试输入未变，Gradle 为 UP-TO-DATE，沿用 720 项 XML 结果，非本轮重新执行。

No docs/testing/177/summary.md was committed by the writer. No push or PR creation was performed. The existing publisher owns summary publication and remote PR verification; the independent reviewer owns merge gates.
