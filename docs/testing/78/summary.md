# Issue 78：SQLite 会话历史

- 实现提交：`7bd4a56b6602d3d297992b561022bb75c113ae0c`。
- release APK SHA-256：未生成／待审核；没有用旧 APK 哈希代表本提交。
- 正式验收：待审核流水线在最终提交上运行模拟器回归。本地结果不是正式验收结论。

## 2026-11-17：主线冲突整合复测

- 验证代码提交：`d22dfce93c55c4c88a4f8db84b3b31b54ca75a38`，父提交为 PR 原 head `d1e3918` 和 main `2d7cdc0`。后续仅更新本摘要；没有合并 GitHub PR。
- 将主线 #86 的摘要工作区与 #87 的历史/账本一致性校验接入 SQLite。摘要路径按数据库和会话隔离，活动 run/spool 保持 owner；拒绝关闭时不丢失账本绑定。保留异常打开时的锁释放、Android 可信存储根解析以及不安全路径拒绝测试。
- 本轮两组定向测试共 **256 项：255 通过、1 跳过、0 failures/errors**。不是历史测试累计，也不是全项目回归。
  - 核心整合组：`SummaryWorkspaceTest`、`HistoryLedgerConsistencyTest`、`ContextPolicyTest`、`FileRunAccountingTest`、`SqliteOwnerCloseTest`、`SqliteOpenFailureTest`、`SharedHistoryDatabaseTest`、`StepCallIndexTest`，119 项中 118 通过、1 跳过。
  - 历史/宿主组：`FileRunHistoryTest`（SQLite 测试适配器）、`HistoryStorageRegressionTest`、`HistoryProcessTest`、`HistoryPerformanceTest`、`SqliteInvocationStoreTest`、`RunRecoveryTest`、`AndroidInvocationOwnershipTest`、`AndroidInvocationStoreTest`、`HostToolExecutionServiceTest`，137/137 通过。
- 两组均通过 `:agent-core:test` / `:app:testDebugUnitTest` 的 `--tests` 过滤执行，使用 `--configuration-cache --configuration-cache-problems=fail --no-build-cache --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process`。严格配置缓存均成功保存。首次命令因 PowerShell 拆分未加引号的 `-P` 参数而在选任务阶段失败，无测试执行；修正参数引号后完成以上两组。
- 唯一跳过为 `link plus parent traversal cannot clean a different active session`：Windows 在符号链接之前解析 `..`，不支持该 POSIX 路径身份场景；其余路径拒绝及 Android 入口 JVM 测试通过。
- `HistoryProcessTest` 实际强杀 15 个 JVM 子进程，覆盖 create/pending/append 的五个写入切点；包含重试前提交可见性检查。不是物理断电测试。
- Windows 11 / JDK 25.0.4、SQLite WAL FULL、未清 OS 缓存：10,000 条独立追加共 10,324.08 ms（均值 1.032 ms），重开 3.402 ms，最近/更早 50 条分别 3.560/3.481 ms；追加不解码旧消息，重开解码 1 条，每页解码 50 条。数据是本机微基准，不代表 Android 延迟或整个运行循环的有界内存。
- 本轮未生成或安装 APK，未运行模拟器/真机/API33/R8 验收；不沿用旧 APK 的结果。正式最终提交验收仍交审核流水线。原始日志、XML 和暂停前改动备份仅留本地，未提交。

## 本地相关测试

PR92 2026-11-11 退回修正：仅修改 siblingStorageFailureInterruptsWaitingForSlowFirstCall 的测试前置同步。fast 有界等待 slow 已进入后才返回；fast 夹具 deadline 为10秒，等待上限5秒，原有2秒 step 结束断言、slow RUNNING、fast UNCONFIRMED及entered断言均保留。生产代码不变。

修正后目标用例重复两轮各30/30通过；随后 HostToolExecutionServiceTest 全类79/79通过，均0 failures/errors/skips。重复Probe直接调用原方法，验证后已删除，原始日志/XML未提交。本轮没有重跑未修复版本，不声称复现原CI具体内部断言。定向命令为 :app:testDebugUnitTest --tests 对应临时重复类或 HostToolExecutionServiceTest，沿用严格配置缓存、no-build-cache、rerun-tasks、Kotlin in-process。

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
