# Issue 78：SQLite 会话历史

- 实现提交：`7bd4a56b6602d3d297992b561022bb75c113ae0c`。
- release APK SHA-256：未生成／待审核；没有用旧 APK 哈希代表本提交。
- 正式验收：待审核流水线在最终提交上运行模拟器回归。本地结果不是正式验收结论。

## 本地相关测试

最终定向运行（green-c3）：`SharedHistoryDatabaseTest` 13、`SqliteInvocationStoreTest` 8、`RuntimeDecisionTest` 12、`PluginInvocationTest` 10、`HostToolExecutionServiceTest` 79，共 **122/122 通过**，0 failures/errors/skips。

执行 `:agent-core:cleanTest :agent-core:test --tests '*SharedHistoryDatabaseTest' --tests '*SqliteInvocationStoreTest' --tests '*RuntimeDecisionTest' :app:testDebugUnitTest --tests '*PluginInvocationTest' --tests '*HostToolExecutionServiceTest' --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process`，configuration-cache problems=fail、no-build-cache。

先前定向 red-c1 的 28 项中 12 项失败，覆盖 worker 等待后的过期准入、revision 类型损坏、即时授权消费变化、事务 Error；修复后对应定向运行通过。green-c1 一项测试错误预期二次故障为 StorageFailure，已改为既有 halted 语义的 Blocked，并保留零副作用与 UNCONFIRMED 实例断言。

早期历史专项测试覆盖：15 个真实子进程创建/pending/append 强杀切点、创建重试前提交可见性、真实 SQL chunk 损坏、10,000 次增量追加和有界分页、暂停解码时其他会话及账本继续读写。规则更新前的全量运行仅是早期版本结果，不冒充最终提交验证；不提交原始日志、XML、APK 或中间轮次。

## 关键结论与限制

- 历史与账本共用数据库和 owner，批次/CAS/调用索引原子提交，保留 pending 恢复和检查点语义；不迁移旧文件历史。
- 分页按索引读取，解码不占用全局数据库锁；SQL/写事务仍串行，显式 snapshot 仍可物化完整历史。
- 共享存储故障关闭执行准入；worker 在真正进入工具体前复查超时/取消。不能撤销已经发生的外部副作用。
- 即时 ALLOW 消费若当前变成 ASK，则拒绝且不伪造回执或接管其他 waiter，调用方需重新请求。
- Error 回归是可控注入，不承诺真实 OOM 恢复。
- 本轮不做全量回归、额外 APK 构建或哈希采证；设备/API33/物理断电等试用事项列 PR“试用检查”，由审核并入 #84。
