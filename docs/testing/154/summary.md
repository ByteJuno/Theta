# Issue 154 validation — RETURN repair

Implementation SHA: `09fcc31e3ce4ced712a5db3a6eb424547e8fda6c`
Tested tree: `6f110d715f20db20c7b39145bbfa7b60d13fac58`
Branch: `auto/154-openai-responses`
Fetched main: `e5015815cfb97bb89b90cf1b0ef6cba503b554a4`
Return: https://github.com/ByteJuno/Theta/pull/169#issuecomment-7219300227

Raw reasoning content and summaries now have independent indexed accumulators, text-done and final-item checks. Missing/changed parts, malformed raw content, post-done deltas and final output mutations fail closed. The full final JSON object, including both channels, encrypted content and additional fields, is preserved for replay. The host's single reasoning display stays on its first nonempty channel; every incoming reasoning event still proves liveness. The real two-request tool-loop regression asserts the complete replay input and final persisted answer.

Official Java SDK was evaluated at `273ed57253318b382c9e3e90a5b6ce4cdb700ed4`. It supports Responses, Java 8+, shared OkHttp and custom HttpClient. At that revision ResponseAccumulator's raw/summary reasoning visitors are empty. Adoption would still require Theta transport lifecycle, ModelEvent/call binding, final consistency and replay integration; this bounded repair retains existing ModelTransport and uses the official typed events as a reference. Verifiable source links are in the PR body; local evaluation is `sdk-evaluation.md`.

Final validation: GPU JVM entry 18/18 passed, GRADLE_EXIT=0, 57.0s, exact staged tree matched on the remote scratch checkout. Final `python scripts/preflight.py` passed in 130.7s with strict `--configuration-cache --configuration-cache-problems=fail`. agent-core 651 tests: 650 passed, 1 platform skip; its successful result from final-r3 was reused unchanged by final-r4. app 797 tests: 796 passed, 1 platform skip, rerun in final-r4. UI resource checks, lint and storage fixture verification/compile passed. Seven new formal tests plus strengthened tool-loop assertions cover raw fragmented Unicode, terminal-only raw content, independent multipart/channel reconciliation, both channel arrival orders, malformed/contradictory finals, complete replay and nonvisible-channel liveness. Original assertions, timeouts and 64 KiB limits remain.

Skipped: SummaryWorkspaceTest link/parent traversal and PluginUpdatesTest snapshotKeepsExecutableBit (Windows platform constraints).

Preserved failures: original implementation GPU baseline had 4 failures / 15 tests; first candidate missed a protocol import; next candidate's new assertion omitted the existing empty initial reasoning delta and was corrected to assert that event too. final-r3 additionally had one unchanged PluginOutputSurfacesTest assertion failure: backup21 show event absent after 8s. Full class independently passed 11/11 without code/assertion/timeout changes, and final-r4 full preflight passed. This intermittent failure is disclosed, not claimed fixed. Evidence is in `E:\Desktop\AI研究\theta-auto-loop\event-runs\8f183c0d-fab3-4d0a-a3d9-2bac6d90415b` and `validation-issue11/issue154-8f183-plugin-failure`.

Limitations: 未生成 APK，未调用真实供应商 API，未操作手机或模拟器，视觉未验证。本轮仅修改 agent-core；原 PR 的协议选择、导入及切换界面仍待当前构建渲染验收。GPU 是既有 GPU 主机上的 JVM 定向测试，不是 GPU 渲染或模拟器验收。SSE 使用合成 fixture/fake transport；API33、R8、硬件 Keystore、实体断电未验证。新提交 CI、独立审核和正式模拟器回归由审核端负责。完整推理回放仍受既有 64 KiB 单事件上限约束，未扩大限制。一次未改动的 PluginOutputSurfacesTest 备用展示 8 秒等待失败，根因未确认；同树该类 11/11 与随后完整预检通过，未宣称修复该偶发问题。

Only verified implementation files were committed. No test summary was committed by the writer, no push/PR write/merge was performed. Existing publisher owns publication; independent reviewer owns CI, formal emulator regression and merge.

## Historical initial implementation evidence (not rebound to this SHA)

# Issue 154 validation

Implementation SHA: `cba1a82ae6ba079cefd0176b1b8d648361680fb4`
Tested tree: `8eff75616d908110c7aa5ce2cba3947000d42ee2`
Branch: `auto/154-openai-responses`
Base/final fetched main: `e5015815cfb97bb89b90cf1b0ef6cba503b554a4`

Implemented native OpenAI Responses streaming and v3 provider routing, protocol selection/template import, Chat-only compat, schema-profile forwarding, request admission estimation, native tool call/result identity, complete reasoning replay, and bidirectional checkpoint handoff. Shared the existing OpenAI HTTP/SSE lifecycle rather than adding a network stack.

Validation: `python scripts/preflight.py --gradle-arg=--configuration-cache --gradle-arg=--configuration-cache-problems=fail --gradle-arg=--max-workers=4 --gradle-arg=-Pandroid.overridePathCheck=true --gradle-arg=-Pandroid.builder.sdkDownload=false` passed. Final preflight took 103.2s. agent-core: 644 tests, 643 passed, 1 platform skip. app: 797 tests, 796 passed, 1 platform skip; Gradle reused this result in the final run because app inputs were unchanged. UI resources (603 paired keys), lintDebug and storage-fixture verification/compile passed. 14 new test methods cover wire fixtures, parallel deltas, exact reasoning replay, native two-request tool loop with one ledger execution, checkpoint preservation in both protocol-switch directions, v3 persistence/binding/quick switching, templates, schema forwarding, key isolation, cancellation, malformed streams, error redaction and limits. Source hashes matched throughout validation and committed tree matches validated tree.

Skipped cases: [{"class": "io.github.bytejuno.thetacore.agent.run.SummaryWorkspaceTest", "test": "link plus parent traversal cannot clean a different active session"}, {"class": "io.github.bytejuno.thetacore.plugin.PluginUpdatesTest", "test": "snapshotKeepsExecutableBit"}]

Failures retained: initial resource scan rejected literal protocol names in UI; replaced with existing enum values. First final preflight failed one new tool-loop expectation (bare text versus the existing status/effect/message ledger envelope); corrected to assert all envelope fields, then core suite passed. All original logs/XML remain at `E:\Desktop\AI研究\theta-auto-loop\event-runs\fac83cbd-5562-43b0-bfdb-2c40fadf179a` and `validation-issue11/issue154-fac83-*`. No failing assertion was removed.

Limitations: 未生成 APK，未调用真实供应商 API，未操作手机或模拟器。视觉未验证：服务商协议选择、Responses 下兼容项隐藏、模板导入及模型切换的中英文、小屏、大字体、明暗主题实际渲染与交互待审核端验证。SSE 测试使用按官方文档构造的协议 fixture / fake transport，不冒充线上录制或真实服务验收。完整 reasoning 回放当前受既有 64 KiB 单事件上限约束，超限明确失败，不截断；未新增大载荷外部存储。未知服务端输出类型拒绝处理，extraBody 按可能执行服务端工具保守分类。Responses 不支持停止词，配置停止词时明确拒绝。API 33、R8、硬件 Keystore、实体断电、CI 及正式模拟器回归本轮未验证；发布后的独立审核端负责 CI 与正式回归。

Protocol references checked: https://developers.openai.com/api/reference/resources/responses/methods/create ; https://developers.openai.com/api/docs/guides/reasoning ; https://developers.openai.com/api/docs/guides/migrate-to-responses . Fixtures are synthetic, not live API captures.

Writer did not push, create/update a PR, commit a testing summary, or merge. Existing publisher owns publication and independent reviewer owns CI/regression/merge.
