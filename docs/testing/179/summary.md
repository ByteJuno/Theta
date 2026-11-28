# Issue 179 validation

Implementation SHA: `3671224deb450de885293265004674e6e06d4900`
Tested source tree: `cab6295a3ca3321440fe1960b828ca9ea5dbc3bd`
Branch: `auto/179-floating-progress`; PR #193 RETURN: https://github.com/ByteJuno/Theta/pull/193#issuecomment-7245642018
Base main: `ff36db923493a523ce5f3936b4846af99fea783e`. Remote PR head before publication: `52cc53bd070da74b72ace273f1c26f7a8e7a4117`.

## Implemented

- Runtime observes ChatSessionOwner from installation, before the first progress event. Saved approvals and late-exit recovery requests are projected read-only from the selected session; no model/tool work is admitted for display.
- Active progress remains execution-ID scoped. Saved entries use session/request IDs; stop refreshes current state before accepting a window action. Old session/request windows cannot cancel newer work.
- Resolving/denying/stopping a pending step or switching sessions removes the window. Accepting uncertainty preserves the ledger's remaining verification action; VERIFIED_CLEAR removes it. No ledger semantics changed.
- Three new integration tests use real ChatSessionOwner, SQLite, HostToolExecutionService and Robolectric WindowManager: cold approval restoration and stale-action rejection; cancellation with late exit; timeout with late exit. They exercise open/switch/resolve/stop and assert no tool/model replay.

## Current implementation checks

- Full `python scripts/preflight.py`: passed in 158.8s, strict `--configuration-cache --configuration-cache-problems=fail`, configuration cache reused, no build cache. Tested tree equals committed tree.
- App: 951 tests, 942 passed, 9 skipped, 0 failures/errors; all 109 suites fresh this round.
- Agent-core: 734 tests (733 passed, 1 skipped, 0 failures/errors), Gradle UP-TO-DATE from unchanged inputs; not rerun this round.
- `:app:verifyUiResources`: 778 English/Chinese keys; lint: 0 errors/113 warnings; storage-fixture source checks and Kotlin compilation passed.
- Required `/opt/homelab-report/theta-gpu-test`: 14 tests, 0 failed, exit 0 in 103.9s. Remote staged tree matched `cab6295a3ca3321440fe1960b828ca9ea5dbc3bd` before testing. Entry did not separately report skips/errors.
- Earlier local targeted run: 13 tests, 0 failures/errors/skips, before adding the timeout counterpart; final preflight/GPU above cover the final tests.
- Final fetch confirmed both PR branch and origin/main are ancestors of HEAD; no reviewer commits or main changes required merging. PR #190 remains independently tracked at its own cursor/head.

## Evidence and limits

未生成 APK（APK SHA 未生成），未操作手机或模拟器，视觉未验证。本轮未改变布局或文案。真实 ChatSessionOwner、SQLite、工具执行器和 Robolectric WindowManager 联动测试验证状态、点击与窗口注册/移除；Linux GPU 测试入口不是当前 APK 的像素、触摸或系统合成器验收。当前实现的 GitHub CI、规定 S1/S2、实际中英文/小屏/大字体/明暗主题、悬浮权限、截屏避让、停止延迟、签名及 R8/API33 设备验收仍由独立审核端完成。agent-core 输入未变，734 项为 UP-TO-DATE 既有 XML，非本轮重跑。

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\ef6c422d-82a8-43d8-8152-aaaabc37dbab`. Original reviewer diagnosis and all failed test attempts are retained locally. Initial harness compilation/session-binding errors and recovery-contract counterexamples were corrected without increasing timeouts or weakening assertions. Prior APK, CI or device evidence is not rebound to this implementation. No testing summary is committed by the writer; the publisher owns the single issue summary and remote publication.

## Reviewer correction (2026-11-28, round 2)

- 本轮基于 `e2e489833e609347fda98522c90d31e412aff872`；此前退回 1 次，历史记录不变。原实现摘要的源码树已与 Git 核对，原有结果只用于原输入。
- GPU 探针复现：旧等待窗口通过主线程检查后延迟处理停止请求，同一会话的新审批被错误改为 CANCELLED；工具和模型执行次数均为 0。根因是后台队列只重新校验会话 ID，未携带请求 ID。
- 修复将请求 ID 传入会话操作队列，并对当前待处理步骤的账本请求重新校验。新增正式回归用例验证旧停止请求被拒绝、当前停止仍生效且无重放；既有断言保留，未放宽超时。
- 首次探针夹具因重复打开会话而两项失败；修正夹具后，两项中停止竞态失败、截屏取消恢复通过。失败原因及结论保留于此，未将夹具错误当作生产证据。
- 最终 GPU：`:app:testDebugUnitTest --tests '*FloatingProgress*' --tests '*ChatSessionOwnerTest.staleNotificationCannotCancelLaterExecutionAndCurrentStopSettles'`，`GRADLE_EXIT=0`，16 项、0 失败。停止探针显示新请求仍为 WAITING_APPROVAL 且请求 ID 不变，工具和模型均 0 次；截屏取消探针显示恢复 1 个窗口。
- 验证的已跟踪源码树（加入本节前）为 `d3b6a857d7ddfc58fd7de57d40cd9e7102349e43`；GPU 另外运行了未跟踪的 `FloatingProgressReviewProbe.kt` 中两项探针，探针文件不提交。未新增依赖，未改受保护文件；本次未构建 APK，视觉仍未验证。
