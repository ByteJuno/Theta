# Issue 177 validation

Implementation: `787abc7dab2650ef37a4d1e1bd5221c583f4bf44`. Exact tested Git tree: `169bc85437c5d8a2de0166d72513a22b2de0db10`. Branch: `auto/177-data-themes`. Includes latest main `ff36db923493a523ce5f3936b4846af99fea783e` and every remote PR commit through `a857d0de5ae8f2e5f9e015aab80b082199c2719f`.

Explicit plugin-theme and palette choices now enter PluginManager's lifecycle mutex. Pending revocations must finish before a fresh choice commits. Selection rechecks the active package and validated archive; stale/disabled choices and selection before safe-mode restore are rejected. A failed recovery reports save failure and retains its durable intent. Existing pre-read journaling, safe-mode restore, Android ancestor aliases and descendant link rejection remain intact.

Two formal UI regressions cover: update removes ocean and retains forest, revocation IO fails, an attempted choice reports failure, retry commits forest, cold restore and recreated appearance state retain forest; stale choices after disable and before/after safe-mode restore are rejected. The first old-code run stalled on frozen test time; the wait dispatcher was corrected without changing timeout or assertions. The corrected old-code run reproduced the actual forest-to-null failure. Both are retained outside Git.

Final validation AFTER main merged:
- Full `python scripts/preflight.py` with `--configuration-cache --configuration-cache-problems=fail --no-build-cache`: passed in 245.4 seconds. App 974 tests: 965 passed, 9 skipped; agent-core 734 tests: 733 passed, 1 skipped. All 110 app and 76 core suites freshly executed; zero failures/errors.
- Existing `/opt/homelab-report/theta-gpu-test`, TEST_HOST=gpu, exact staged tree above, strict configuration cache and no build cache: GRADLE_EXIT=0, TESTS total=37 failed=0, 102.3 seconds. Entry does not separately expose skips/errors. Covers theme UI, revocation journal, recovery and preference state/store.
- UI resources: 772 English/Chinese keys. Storage fixture source verification/compilation and lint passed; lint 0 errors, 111 warnings. Git whitespace and tree identity checks passed.
- Pre-merge evidence also passed: local targeted 37/37, GPU 37/37, full preflight. These remain separately bound to tree b5b153cef71cd2cd800b7c4fbc83f2856af6bf7b; final counts above are from merged code.

All six RETURN records remain preserved. Reviewer raw verdict/log/meta and this round's failed test/transport logs remain in `E:\Desktop\AI研究\theta-auto-loop\event-runs\f29c50e0-ca8a-4908-abb6-09b306880ed6` or `validation-issue11/issue177-f29-*`. The initial GPU archive was rejected by the tree-identity guard before tests (CRLF conversion); a raw-byte archive matched exactly and passed. The first main-merge command failed for missing Git identity without changing the merge; the corrected command merged without conflicts. No probe sources, raw evidence, or newly authored docs/testing summary were committed by the writer. Existing main summaries were retained by the required merge.

APK: not generated; APK SHA: not generated. 未生成 APK（APK SHA 未生成），未操作手机或模拟器，视觉未验证。本轮无布局或文案变化。冻结帧 Robolectric 和 Linux GPU 入口证明选择、撤销、磁盘及重建状态，不证明当前 APK 像素、触摸或设备启动。新 head 的 GitHub CI、规定 S1/S2、签名、R8 与 API33 设备验收仍待独立审核端完成。旧 S1 失败仍仅绑定 d997120f13727a91896efb467a48a0425e0a8b81。

## 第 7 轮独立审核（2026-11-28）

- 原审核 head 为 `19c8a586f47b07fda2223edcf3a156dbf5c1b1e3`，与上述受测实现仅有本摘要差异；此前 6 次退回记录保持不变。
- GPU 探针实证两项失败：更新移除 ocean 且回退写入失败后，旧页面延迟回退会清除后来重新选择的 ocean；偏好文件路径变成目录时，恢复抛出的 IllegalStateException 不在 MainActivity 的存储错误捕获范围内。原始失败输出留在审核工具记录中，未入 Git。
- 修复提交 `726f71030f9120aaa516537a205d01af03fcbbf3`：UI 自动回退绑定观察到的提交版本，同名显式重选也使旧回退失效；路径异常报告 IOException，保持原有路径拒绝、撤销意图及恢复门控。新增 4 项正式回归覆盖旧页面延迟回退、同名重选、旧组合状态及存储修复后的恢复重试。
- 允许的 GPU 入口在首轮修复上通过 42 项相关测试；补齐组合时捕获版本及对应回归后，在上述修复提交上通过 43 项（41 项正式测试、2 项未跟踪探针），GRADLE_EXIT=0，失败 0。范围为 PluginThemeUiTest、PluginThemeRecoveryTest、PluginThemeRevocationsTest、ThemePreferenceStateTest、ThemePreferenceStoreTest 和 ThemeReviewProbe。
- 未重跑既有全量结果，未增加依赖、超时或修改受保护文件；原有断言保留，路径异常断言随修复改为 IOException。探针未提交。未生成 APK、未操作设备，视觉仍未验证；最终提交的 CI 与模拟器回归由外层脚本执行。
