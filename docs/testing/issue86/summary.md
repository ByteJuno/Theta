# Issue 86：摘要临时文件恢复清理

- 实现提交：`2b9c319ec921050399136ee2a436764f036cba60`。
- release APK SHA-256：未生成／待审核流水线，不使用旧包哈希。
- 正式模拟器结果：待审核流水线在最终提交上运行，本地测试不是正式验收。

## 相关测试

最终定向运行：SummaryWorkspaceTest 13（12通过、1跳过）、ContextPolicyTest 64、FileRunHistoryTest 9、FileRunAccountingTest 4；合计 **90项，89通过、1跳过，0失败/错误**。

执行 `:agent-core:test --tests '*SummaryWorkspaceTest' --tests '*ContextPolicyTest' --tests '*FileRunHistoryTest' --tests '*FileRunAccountingTest' --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process`，严格 configuration cache、no-build-cache。

首次红测2/2失败，证明未使用会话目录且重开未清理。路径修正红测13项中4失败、1跳过；其中 linked workspace 用例实际观察到拒绝前发生恢复副作用。一个 linked-session 红测最初使用错误的 Windows 链接夹具，其 AccessDenied 失败不作为该副作用的复现；修正夹具后最终验证拒绝时哨兵不变、不创建锁与accounting目录。

## 关键结论与限制

摘要使用会话专属 summary-tmp；复用会话独占锁，只清理名称匹配的直属普通文件，不递归、不跟随链接。活跃 run/spool 阻止关闭。原始路径的点组件和链接祖先在创建、开锁、恢复写入前验证，history/lock/workspace 使用同一目录身份。

中断恢复通过预置遗留文件模拟 finally 未执行，不是真实强杀。真实协程超时取消清理通过。唯一跳过是 POSIX 风格 link/../session 身份分歧复现：本机 Windows 实测未产生该分歧；跨平台点组件拒绝及其他链接测试通过。

假定目录由宿主私有，不承诺抵御恶意并发路径替换；不扫描历史全局临时目录。未做全量回归、APK构建、设备/API33或产物哈希验证。原始日志/XML留本地，不进Git；设备试用事项交PR“试用检查”并入#84。
