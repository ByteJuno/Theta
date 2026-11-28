# Issue 175 validation

- Implementation commit: `bcdd7a25f14100fd489a7bbd3727409fc147b73d`; tested tree `591ef53c4016d71601c8b44d7f7730b27a96c421`.
- Branch: `auto/175-web-tools`; incorporates main `a89fa03f69c3ac45c347d179072419f5f01164d3` and existing remote PR head `82828e14bd464d1c3c3c104229fcd394f710872d` without overwriting reviewer work.
- RETURN comment: https://github.com/ByteJuno/Theta/pull/184#issuecomment-7232371379 . Four confirmed findings addressed: DataStore storage, invalid-key clear/refill cold recovery, accurate IPv4 subnets, cancellable bounded DNS.
- Final strict preflight passed in 190.53 seconds. App: 860 tests / 853 passed / 7 skipped / 0 failures, fresh execution. Agent-core: 688 / 687 passed / 1 skipped / 0 failures, FROM-CACHE, not a fresh run. Lint, paired resources and storage-fixture passed. 17 web tests included and passed; targeted run also passed before final boundary refinements and main merge.
- Test worktree hash manifest unchanged during final validation and verified immediately before commit. No raw logs, XML, APK or diagnostic trial files committed.
- DataStore release POM confirms Apache-2.0; uses already pinned 1.1.7. Legacy encrypted file, zero-byte/oversize damage, failed-write preservation, explicit clear/replacement, invalidated and transient key cases exercised across closed/reopened DataStore scopes.
- DNS timeout/cancel uses unresolved asynchronous test resolver; native Android query/FD cancellation source reviewed at fixed AOSP commit and compiles, not device-run. HTTPS, peer/DNS checks, redirects, 2 MiB limits and output BlobStore verified by JVM loopback tests.
- APK: not generated; SHA-256: not applicable. 未生成 APK；未操作手机或模拟器；视觉未验证。本轮未改界面布局，原搜索设置的中英文、小屏、大字体、明暗主题、保存/返回/损坏替换/清除仍待实际渲染验收。Android DnsResolver 取消及数值地址路径已核对平台源码并通过编译，但未在 Android 设备上运行；DNS 取消/超时测试使用不返回结果的异步解析器契约，不能替代原生网络验收。Keystore 恢复使用实际 SecretCipher/AES-GCM 与合成密钥源，未进行 Android Keystore 真机测试。真实 Brave/Tavily/SearXNG、API33 运行、R8、最终 CI 和审核端正式模拟器回归尚未验证。

## Preserved failure history

- 上一轮四次编译/HTTPS fixture 失败和本次审核四项复现保留在原 event-runs、state-before.json 及 review-verdict.json；未改写原始证据。
- targeted 首次 JUnit 初始化失败：测试表达式推断出非 void 返回类型；明确 runBlocking<Unit> 修正，保留断言与失败 XML。
- 两次 Android DNS 源码地址 404 已保留；随后成功取得固定源码提交 4416f38e4f624ed9562eb9ce7d33c4c0d5dc9bcf。
- 最终同步 main 前进触发祖先检查失败；首次 preflight 意外继续运行但源代码未变且通过，保留为 pre-merge 证据。随后 git merge --no-commit --no-edit origin/main 无冲突，合并树重新通过完整 preflight 后才提交。

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\f9e55450-3ed5-4531-afc5-cc650c5209f8`. PR #172 merge independently verified at `a89fa03f69c3ac45c347d179072419f5f01164d3` and recorded completed; its evidence is not reassigned to PR #184.

## 第二轮审核修复（2026-11-28）

- 审核输入 `ac10e333b0e9273d65059ae2b2c702a2abdd3fc1` 与上述实现提交仅测试摘要不同；已核对实现树哈希，采信已有检查，未重跑完整预检。
- 修复提交 `ec075d160e3e9187f5ddaf5c987384729617c7c1`：SearXNG 地址原带 `q`/`format` 时改为替换保留参数；内部生成的搜索 URL 不再因中文百分号编码超过原始 URL 输入限制而被拒绝。原始输入长度、HTTPS、凭据和公网地址限制保留。
- 既定 GPU 入口运行 `:app:testDebugUnitTest --tests '*WebToolsTest*' --tests '*WebBoundaryProbe*'`：14 项通过，0 失败，`GRADLE_EXIT=0`。永久回归包括两个 GET 搜索后端经真实 TLS 回环完整发送 2000 字中文、保留 SearXNG 语言过滤参数、拒绝超限原始 URL；探针未提交。
- 修复前失败证据：探针先有一次 Kotlin 尾随 lambda 语法编译失败（0 用例），修正后执行 3 项、2 项失败。1000 字中文查询生成长度 9057 的 URL，在 DNS 前返回 ADDRESS；保留参数为 `q=[old, new query]`、`format=[html, json]`。修复后探针到达 DNS，参数分别只有新查询和 JSON 格式；DNS 探针故意返回回环地址，仍被公网检查拒绝。
- 实际 jsoup 1.21.2 依赖包的 `META-INF/jsoup/LICENSE` 和内嵌 POM 均核对为 MIT；OkHttp 测试依赖 5.5.1 的 Apache-2.0 证据沿用 `docs/testing/106/summary.md`。DataStore 沿用已有 1.1.7。
- 未生成 APK、未运行设备、视觉未验证；此前设备与渲染覆盖限制保持。最终提交的正式模拟器回归仍由外层脚本执行。
