# Issue 177 validation

Implementation SHA: `bb3768c8d04eb6939077f938733842c91eaf9184`
Tested tree: `3d64cdb56c120ff21c0225c81962fc712bfa7a11`
Branch: `auto/177-data-themes`
Base main: `49097146a429dc3a3a5cb245be928b69a37530e8`
APK / SHA-256: 未生成。

本轮处理 PR #190 第四次 RETURN（IC_kwDOVkrS_s8AAAABr67dzA）。冷启动先收集失效的插件身份，包括无法解析安装记录的目录身份；在主题偏好读写前持久记录全部撤销。恢复和主题核对成功后才接纳数据页面。失效身份与可信管理记录分开保存，不伪造状态或页面能力。恢复成功清理撤销日志，保持其他插件较新选择以及恢复完成后的重新选择。

- 旧实现上新增 4 条回归均失败：归档损坏+偏好提交失败、归档损坏+偏好不可读、安装记录损坏+偏好提交失败，以及其他插件新选择保护。前三项明确因撤销日志为空失败；第四项因偏好失败前已暴露健康插件页面失败。原始结果保存在 `regression-before-fix/`，不是零用例或编译失败。
- 修复后定向 59 项全部通过，包括 PluginThemeUiTest 18 项、数据生命周期 18 项、崩溃隔离 10 项、主题 5 项、偏好存储 8 项。
- `python scripts/preflight.py` 完整通过，严格 `--configuration-cache --configuration-cache-problems=fail`，禁用 build cache；耗时 183.863 秒，configuration cache stored。
- app 941 项：934 通过、7 跳过，100 套件本轮实际执行，0 failures/errors；其中 PluginThemeUiTest 18 项全部通过。
- agent-core 720 项：719 通过、1 跳过，73 套件本轮实际执行，0 failures/errors。
- verifyUiResources、storage-fixture 编译通过；lint 0 errors / 109 warnings。实现 tree 与最终验证 tree 一致，验证后未修改源码。
- 最终验证前 fetch；远端 PR head `3d3753c60f7b4032207524e2ce410be5ce6ef319` 和 main 均已包含，无需合并。提交前再次确认远端 head、OPEN 状态和最新 RETURN 游标未变化。

未生成 APK（APK SHA 未生成）；未操作手机或模拟器，视觉未验证。本轮无布局或文案变化。冻结帧 Robolectric 验证磁盘、实时偏好和重建状态，不代表当前 APK 像素或触摸验收。新提交 CI、正式 S1/S2、中文/英文小屏大字体明暗主题实际渲染、R8、签名及 API33 设备验收仍由独立审核端完成。

所有本轮原始证据位于 `E:\Desktop\AI研究\theta-auto-loop\event-runs\68fcca79-a5c6-4697-ae63-792f5b5cb4be`。完整审核 verdict/log/meta 和失败命令输出、修复前失败日志/XML 均已保留。审核首次零用例运行是探针生成失败，不计为缺陷验证；其后 3 项有效失败保留在原始日志中。此前三次退回和修复证据仍保存在 `33768566-64c9-4d43-b9c9-a198e4611d02`、`fcc5a1d2-4029-482f-89bd-46fad2e67461`、`8f4e0313-ee76-4fe9-aaa1-014658cafd19` 及原 reviewer out 目录。未提交 Probe、日志、XML 或摘要，未删除断言、放宽超时或修改受保护文件。

## 第五轮审核修复

- 审核输入：`0ad775cfa359c3fc718428a4e200f471ea6bb3d7`；此前四次退回记录保持不变。
- GPU 探针修复前执行 2 项、失败 1 项：安全恢复跳过全部数据清单且偏好不可读时，没有撤销日志，恢复读取后旧主题重新成为选中项。
- 修复为安全恢复先持久记录全部主题撤销，再读取偏好；不访问可能损坏的插件清单。恢复成功后清除记录，保留之后用户明确作出的新选择。
- 修复后 GPU 定向测试 54 项全部通过：原有主题界面、主题解析、偏好存储和数据生命周期 49 项，新增正式恢复回归 3 项，未提交探针 2 项。覆盖偏好读写失败、损坏清单、磁盘与实时/重建偏好、日志清理及恢复后的重新选择。原始输出留在审核执行记录，不入 Git。
- 已测试的受版本控制文件 tree：`ce812a07d59911d39a5d17beddc307789d03047a`（不含本段摘要更新和未跟踪探针）。未生成 APK，视觉仍未验证。
