实现提交 6d44441667bd3657c70b0047f61dcce2eb80a2d4

完整预检通过，agent-core 632 项、app 714 项，各跳过 1 项，零失败、零错误。提交前再次运行预检并通过，单测任务为 UP-TO-DATE。两次预检源码 SHA-256 清单一致。严格配置缓存、双语资源校验、lintDebug 和 storage-fixture 编译通过。 定向覆盖撤销后重新授权、拒绝优先级、风险冻结、SQLite 审计回执和设置重启持久化。

未生成 APK。视觉未验证，小屏、大字体、中英文及明暗主题下的弹窗布局和触控仍待验收。未操作手机或模拟器，未验证 API 33 运行时、R8 或实体断电。当前实现的 CI、独立审核和规定回归待发布后执行。

第 1 轮独立审核（此前退回 0 次）：在 9a8de06613299f12d62e7e0a916a236a639f2d57 上两项探针均失败。合法 MCP 工具键含未配对 UTF-16 代理项时，重启后拒绝规则失配、风险从 ROOT 回落到 WRITE，调用进入 READY；规则读取失败后即使文件恢复可读，刷新仍保留不可用状态且保存失败。修复提交 16e42b2f59dc29b200815afbcfd8e1ca8d92bc4a 使用 Properties 字节流转义保留工具键，并允许重新读取已恢复的规则，同时禁止把失败后缺失的文件当成全新设置。

审核修复的定向验证通过：经 theta-gpu-test 执行 `:app:testDebugUnitTest --tests '*ToolRulesReviewProbe*' --tests '*ToolApprovalManagementTest*' --tests '*ChatApprovalModesAppTest*'`，9 项、0 失败。探针确认重启后仍为 ROOT、REJECTED，恢复可读规则后不可用状态清除且保存成功；文件仍缺失时保持阻断。探针未提交，长期回归用例已补入现有测试。原完整预检结果对应上方实现提交，未冒充本次修复后的完整预检；视觉、设备及最终提交回归仍未执行。
