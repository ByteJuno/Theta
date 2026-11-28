# Issue 177 validation

Implementation SHA: `9c2c41f346a0b731b4e7ed11d897f6f1d5957690`
Tested tree: `3421ffd7046b3390f0ed17406a454fc5f902da5e`
Base main: `49097146a429dc3a3a5cb245be928b69a37530e8`; remote PR head before handoff: `81b578b8db1f62dc502991910a27d64340e35515`.

This RETURN fixes theme revocation across install/update and cold recovery. Pre-install reconciliation uses old inventory; post-install reconciliation validates the exact selected theme. Failed preference writes propagate, committed package inventory stays accurate, replacement retries revocation before activation, and cold restore clears missing/invalid theme keys. Existing valid themes and newer other-plugin selections are retained.

Validation on the implementation tree:
- 23 targeted tests passed (including 10 PluginThemeUiTest cases), zero skipped/failures/errors.
- Required `python scripts/preflight.py` passed in 236.37s with strict `--configuration-cache --configuration-cache-problems=fail`.
- app: 932 tests, 925 passed, 7 skipped; core: 720 tests, 719 passed, 1 skipped. Both tasks executed this round; zero failures/errors.
- UI resource check and storage-fixture source/compile tasks passed. Lint: 0 errors, 109 warnings.
- Source tree unchanged between validation and commit. No protected files or new dependencies changed.

Failure evidence is preserved outside Git in `E:\Desktop\AI研究\theta-auto-loop\event-runs\fcc5a1d2-4029-482f-89bd-46fad2e67461` and the named validation-issue11 runs. The reviewer recorded two failed GPU cases on `81b578b8db1f62dc502991910a27d64340e35515`; the same two missing durable-selection clears failed locally before the fix. The first quarantine attempt stopped at an invalid retained ZIP; its evidence is retained, then the fixture restored original archive bytes (matching the reviewed scenario) and reproduced the target assertion. No assertions or timeout limits were weakened. Both historical RETURN records remain on PR 190 and in state history.

APK SHA: 未生成。
未生成 APK（APK SHA 未生成），未操作手机或模拟器，视觉未验证。冻结 Compose 帧的 Robolectric 测试只验证布局/语义、主题状态及持久化，不证明当前 APK 字体像素或触摸效果。本轮无布局或文案变更。当前提交 CI、规定的 S1/S2 模拟器回归、中英文/小屏/大字体/明暗主题的实际渲染、R8、签名与 API33 设备验收仍待独立审核端完成。
