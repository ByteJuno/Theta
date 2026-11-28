# Issue 178 validation

Implementation: `ea148846f25d84de51a4361993d03f174d1097e6`
Tested Git tree: `cec926a4dd671a3cd434751a08c1e3ae513fa0fb`
Base main: `49097146a429dc3a3a5cb245be928b69a37530e8`; remote PR head before publication: `ae5b2cb18708448d4901b335232925189f5d3b1e`.

## RETURN repair

- Missing/quarantined tool images become explicit request-only placeholders with blob identity and storage reason. Healthy sibling attachments, user images, original error text, call order/IDs and durable history remain intact. No UNKNOWN/resource lease changes.
- The same preparation runs before request admission and summary extraction. Real SQLite reopen/replay tests prove later text runs complete without rewriting original media references.
- Anthropic keeps supported GIF images; Responses uses an explicit unsupported-format placeholder. Both encoding and estimation handle GIF without an exception.
- Cold Linux full-app validation reproduced the previous CI ClosedFileSystemException in Robolectric native font loading (2 ScreenCaptureTest failures). Isolated cold execution passed; Linux test JVMs now restart per test class. All original assertions/timeouts and budgets remain. No workflow/wrapper/protected-file modifications.

## Checks

- Final `python scripts/preflight.py`: passed in 165.0s, strict configuration cache and no build cache; storage fixture checks/compile, UI resources, lint, core and app tests.
- Windows app: 932 tests, 0 failures/errors, 9 platform skips. Core: 727 tests, 0 failures/errors, 1 skip; actual execution in earlier same-round preflight, final UP-TO-DATE with unchanged inputs.
- Exact final tree Linux GPU cold-native-cache validation: app 932/932 passed, zero skips; core media regressions 7/7 passed. Gradle 3m16s; end-to-end command 262.5s. Raw XML retrieved in `gpu-final-evidence.tar` and build script parity checked. Linux scratch only normalized wrapper line endings.
- Lint 0 errors / 108 warnings. `git diff --check` passed. Final fetch confirmed PR remote and latest main are ancestors; no merges necessary.
- Earlier targeted checks: core media 7/7; host screen integration 3/3. New coverage includes partial attachment loss, preserved user media and errors, valid GIF, real SQLite corruption/missing chunks, repeated cold replay, summary data/call identity, and unchanged UNKNOWN isolation.

## Evidence and limits

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\3fa5318a-1d60-4eb3-a551-7ed0eaadbc83`. Reviewer verdict/log/meta, previous-head CI failure, cold GPU failure log/XML and subsequent successful results are preserved outside Git. No evidence logs/XML/APK or this summary committed by the writer.

未生成 APK（APK SHA 未生成），未操作手机或模拟器，视觉未验证。本轮无布局或文案变更。GPU 是 Linux JVM/Robolectric 验证，不是设备 Root/无障碍/剪贴板验收；Anthropic/Responses 只验证本地编码和请求路径，未访问实际模型接口。新提交 GitHub CI、S1/S2、R8、签名、API33 与目标厂商设备验收仍由独立审核端完成。最终 core 为 UP-TO-DATE；727 项在本轮此前完整 preflight 实际执行且 core 输入未变。Linux 按测试类隔离 JVM 增加进程启动开销。
