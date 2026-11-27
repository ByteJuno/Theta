# Issue 155 — Anthropic native protocol validation

Implementation SHA: `4897af9ab8965c741576fd7484945623b0274c5a`
Tested tree: `7b27795e44e9af2412cfaeef2d04d68441afc349`
Branch: `auto/155-anthropic-protocol`. RETURN: PR #172, `IC_kwDOVkrS_s8AAAABroxSCg`.

Native Anthropic Messages, thinking/signature/redacted replay, tools, explicit cache, pause continuation, models discovery and v3 preset/settings support remain in scope. This revision repairs all four reviewed paths:
- A stopped/truncated tool step can precede a new user run; tool-result and user-text messages remain distinct, thinking order is preserved, and unpaired calls still fail.
- Non-object/malformed tool arguments enter the existing checkpoint flow before wire estimation. Raw history, replay signatures and ledger remain unchanged. Explicit summary continuation completes without repeating tools. Rejected arrays/scalars/malformed JSON and existing null-to-empty execution semantics are covered separately.
- Explicit `invalid_request_error` / `prompt is too long` errors map to `CONTEXT_TOO_LONG` over HTTP and SSE; error text remains redacted and other errors retain their classes.
- Preset parsing uses the same parameter constructor as selection, rejecting invalid reasoning defaults/options as `PluginPackageException` before checkbox callbacks.

Validation on this candidate:
- Local targeted core: 112 tests, 0 failures; app: 22 tests, 0 failures.
- `python scripts/preflight.py` with strict configuration cache passed in 175.1s: core 680 tests (679 passed, 1 platform skip), app 817 (816 passed, 1 platform skip); lintDebug, verifyUiResources (622 English/Chinese keys), storage-fixture source verification and Kotlin compilation passed.
- Existing `/opt/homelab-report/theta-gpu-test` entry on host `gpu`: 134 tests, 0 failures, 119.5s, strict configuration cache. Includes Anthropic, run/context policy, preset and PluginOutputSurfaces regressions.
- Files were hash-checked unchanged through validation. Final fetch confirmed PR branch head 9c5f7e5037b31359cdb26320c28184247e96b432 and origin/main 40330df3a2758b752f90ac495e1aca69f8483e66 are included. Reviewer commits are retained.

Preserved failures: the first local run had one new test expectation failure for null arguments (existing ledger permits normalized empty arguments); corrected that expectation and added explicit no-repeat continuation assertions. The first GPU archive returned 126 before tests because it lost executable mode; the retry used indexed modes plus the unchanged HEAD LF gradlew blob. Neither failure is erased. Prior remote CI run 46032750268 failed the existing plugin close/ranking test; it passes in this round locally and on GPU, but final-head CI remains required. Original reviewer findings and raw evidence are preserved outside Git at `E:\Desktop\AI研究\theta-auto-loop\event-runs\98d3e920-bce5-4cba-8115-aea21b0422ec`.

APK: not generated; SHA256: not applicable. 本轮未生成 APK，未操作手机或模拟器，视觉未验证；真实 Anthropic 接口、API33 运行、R8 与硬件未测。独立审核端仍须复核最终发布 head 的 CI 和正式模拟器回归。当前构建的预设错误展示与中英文/明暗/小屏/大字体设置交互仍需实际渲染验收。GPU 结果为 JVM 单元测试，不是设备验收。原有回放 64 KiB 封装上限、未知服务端工具块保守拒绝等限制继续适用。
