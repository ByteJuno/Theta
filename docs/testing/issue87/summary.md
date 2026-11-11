# Issue 87：历史与账本一致性校验

- 实现提交：`1d46660f397a8cfe3fa72b809a03af9f0e4055a0`。
- 基线：`5e10c87222d03c8752e80e158e41671b94e8c772`。
- release APK：本轮未构建；SHA-256 未生成，待审核流水线。未做产物哈希核对。

## 定向测试

通过现有 runner 执行 `:agent-core:test`，仅过滤以下六类；strict configuration cache、no-build-cache、rerun-tasks、Kotlin in-process。最终63项全部通过，0失败/错误/跳过，configuration cache复用。

| 类 | 通过 |
| --- | ---: |
| HistoryLedgerConsistencyTest | 7 |
| StepCallIndexTest | 6 |
| InvocationLedgerTest | 30 |
| LedgerStorageIndexTest | 7 |
| FileRunHistoryTest | 9 |
| UnknownLegacyProjectionTest | 4 |

覆盖缺行、部分步骤、额外调用、结果/binding/参数/名称不一致；fresh loop重试及持久历史重开仍拒绝，chat请求和bridge submit/cancel/await未发生，两侧未改动。已提交pending缺行拒绝并保留证据；合法未提交intent沿既有路径恢复CANCELLED/NONE，不执行工具。索引覆盖重开、排序、提交前/后故障及迟到证据。

确定性访问计数：0或4000个无关步骤下，对目标三调用步骤查询20次均访问60个成员。仅证明步骤查询不扫描无关步骤，不是整个run复杂度或墙钟性能保证。注册、策略检查、完整历史读取/严格内容比较等开销仍存在。

独立只读复核未发现本范围阻断项；git diff --check通过。中间一次新增JUnit测试返回非Unit导致初始化错误，已修正，非产品缺陷证据。

## 结论和边界

当前Android生产有工具账本owner，但没有会话/AgentRunLoop owner或会话导入路径；规范明确不迁移旧文件。未找到正常宿主路径产生此类不一致。测试是内核边界故障注入，不证明生产迁移故障。

步骤索引保留完整成员、顺序、终态及结果严格校验；只在持久化确认后发布新成员，结果读取当前记录。缺失账本不会由历史伪造，不新增忽略错误、自动重放、恢复UI或暂停协议。

未跑全量回归、模拟器、真机、APK或API33运行验收；新增生产代码仅静态检查API兼容。正式模拟器结果只认审核流水线在最终提交上的运行。后续真实会话接入的试用检查交审核并入#84。原始日志/XML和中间轮次不进Git，仅保留此摘要。
