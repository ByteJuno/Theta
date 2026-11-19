# Issue 75 · 内置工具故障隔离

实测源码 `b528dbb23fad2193db965bd1679eb6d45ae35044`，基线 `f9ee70a953f53721eaae32422b61e99e6818002e`。Windows JVM 定向回归耗时 80.5 秒，严格 Gradle 配置缓存成功写入。

| 测试类 | 通过数 |
| --- | ---: |
| HostScreenToolTest | 4 |
| HostToolExecutionServiceTest | 86 |
| PluginExecutionBudgetTest | 4 |
| InvocationLedgerTest | 32 |
| HistorySchemaTest | 1 |

共 127 项通过，零失败、零跳过。屏幕后端由测试闩锁保持运行，宿主通过真实注册入口执行 clock，并用同会话的 blob_read 读回时钟结果。在旧实例仍占用一个槽位时，同一屏幕工具受重启限制，另一会话注册的同资源工具受资源租约限制。覆盖实际退出、SQLite 冷恢复、旧 host 记录、插件级重启、未知副作用、审批和全局容量。

APK 未生成。未进行 Android 设备、API 33、R8 或实体断电验证。最终提交的独立审核、规定模拟器回归及 CI 由审核端执行。
