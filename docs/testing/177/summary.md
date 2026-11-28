# Issue 177 validation

Implementation commit: `f4cf0f9a08c24e8f6e0b9ff02f4aa3c767e1a232`
Tested tree: `26a695bcdaaaedca6b47b0b0c82ba12543e26442`
PR: #190 · branch `auto/177-data-themes`
Base main: `49097146a429dc3a3a5cb245be928b69a37530e8`
Reviewed remote parent: `2ee109731db3d43c7cda616082d5306923072428`

## Scope and return repair

Data plugins provide validated declarative themes with protected safety colors and immediate brand fallback. This revision fixes the verified OWNER RETURN `IC_kwDOVkrS_s8AAAABr44vdw`: theme revocation is awaited inside PluginManager's serialized lifecycle, independent of a Compose frame. Disable/uninstall, quarantine and cold restore clear the durable preference; re-enable retries an unavailable package's revocation first. Preference IO errors propagate, stale-page quarantine guards remain intact, and newer selections for another plugin are preserved under the preference mutex. Committed unavailable notifications also reach existing appearance owners.

## Actual validation

- Final `python scripts/preflight.py` passed in 161.22 seconds with `--configuration-cache --configuration-cache-problems=fail`; cache reused. Committed tree matches tested tree; inputs unchanged during validation.
- app: 928 tests, 921 passed, 7 skipped, 0 failures/errors; 100 suites freshly executed. Includes six real Compose/theme tests and eight preference-store tests, with four added regressions this revision.
- agent-core: 720 recorded tests, 719 passed, 1 skipped; task UP-TO-DATE because inputs were unchanged, not rerun this round.
- 730 paired English/Chinese UI keys verified; lint 0 errors and 109 warnings; storage-fixture source verification and Kotlin compilation passed.
- Focused theme check before final harness synchronization: 20 tests, all passed, 40.11 seconds. Final preflight supersedes this for the committed tree.
- Frozen-frame regression completes actual disable and enable with no clock advance, checks durable brand preference before another frame, restores a manager, then recreates the appearance owner and checks brand colors/editorial style. Additional tests cover failed preference persistence blocking enable and retry, cold restore/uninstall, and preservation of a newer choice.

## Preserved failures and their resolution

- Reviewer original failure and verdict retained in `E:\Desktop\AI研究\theta-auto-loop\event-runs\33768566-64c9-4d43-b9c9-a198e4611d02\reviewer-verdict.json.log` and `reviewer-verdict.json` (no reviewer probe files committed).
- Local red reproduction on old production code: `validation-issue11/issue177-33768566-before-fix`, 1/1 failed; persisted theme remained `test.prompts/ocean` after disable/re-enable before a frame.
- Initial preflight: 928 app tests, 1 failed (old inventory assertion raced uninstall completion after preference revocation moved earlier), 7 skipped. Full log, result and XML retained as `initial-preflight-*` in this round. Test now awaits the real lifecycle Deferred and propagates its failure; original assertions/timeouts are unchanged. Final full preflight passed.
- Earlier issue177 failures remain in `event-runs/2df61b7e-6141-4798-b976-87e745873c8b` and its referenced validation directories; not overwritten or reclassified.

## Limits and handoff

未生成 APK（APK SHA 未生成），未操作手机或模拟器，视觉未验证。冻结 Compose 帧及重建界面的 Robolectric 检查仅证明布局/语义、主题状态与持久化行为，不证明真实字体像素、触摸或当前 APK 视觉。中英文、小屏/大字体、明暗主题的实际渲染以及 S1/S2 正式回归仍由独立审核端在当前构建验证。未运行新 head CI、R8、签名或 API33 设备验收。core 输入未变，720 项为 Gradle UP-TO-DATE 的既有 XML 结果，不是本轮重跑。

APK SHA256: not generated. No protected file changes, phone operations, push, PR creation/update or merge performed by this writer. Independent reviewer owns current-head CI, prescribed emulator regression and merge. Logs/XML and diagnostics remain outside Git; publisher owns the single summary commit.
