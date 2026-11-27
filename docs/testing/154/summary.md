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
