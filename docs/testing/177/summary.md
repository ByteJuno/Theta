# Issue 177 validation

Implementation SHA: `8f6a2434ba475f1b8c321143a053b5f24b3b82c2`
Tested tree: `00d713351a98f41f765230d800658229db64fa33`
Branch: `auto/177-data-themes`
Base main: `b6296021a9f42b3662a480af9f41bd155b440354`
Reviewed remote head before repair: `d997120f13727a91896efb467a48a0425e0a8b81`
APK / SHA-256: 未生成。

本轮处理第五次 RETURN `IC_kwDOVkrS_s8AAAABr8vxmg`（审核端累计退回 5 次）。已核对 OWNER 评论、完整 comments/reviews/inline comments、审核端 waiting_writer/head 以及 gpu 专用模拟器 run `20261128-190426` 原始日志。S1 在 API34 R8 host Activity 启动时失败：`java.io.IOException: Linked plugin storage is not supported: /data/user/0`，VERIFY_S1_EXIT=1，签名检查因此跳过。该证据仅属于旧 head。

修复主题撤销日志：仅 canonicalize 可信的 Android filesDir，再用既有 pluginPath 校验所有日志后代；每次操作重新校验日志根目录。未修改共享路径防护、UI 布局文案或宿主入口。已纳入审核员 `08fb7c8035c39a336d03e1fdc639c28b3ca7dfd3` 和其后 main 合并。

- 新增 5 项正式回归：应用目录祖先别名下持久日志；拒绝日志目录链接；构造后替换目录仍拒绝；不读取/覆盖/删除链接意图；PluginManager 构造、正常恢复、挂起撤销冷恢复及安全模式回退。
- 修复前 4 项运行、3 项失败、0 跳过。2 项在别名日志构造处出现相同 IOException；1 项为构造后目录链接拒绝异常类型不一致（PluginPackageException 而非 IOException），不是新增设备故障。原始日志/XML 均保留。
- 修复后定向 58 项全部通过：revocations 4、recovery 4、themes 5、data store 27、theme UI 18；0 failures/errors/skips。
- 完整 `python scripts/preflight.py` 通过，严格 `--configuration-cache --configuration-cache-problems=fail`，禁用 build cache，耗时 189.387 秒，configuration cache stored。
- app 965 项：956 通过、9 跳过，107 套件本轮实际执行；agent-core 727 项：726 通过、1 跳过，75 套件本轮实际执行；均 0 failures/errors。
- verifyUiResources、storage-fixture 编译通过；lint 0 errors / 110 warnings，未在本轮改动文件报告警告。
- 最终验证前 fetch；远端 PR 和 main 均已包含；提交前再次核实 OPEN/head/RETURN 未变。提交 tree 与测试 tree 一致，无验证后源码修改。

未生成 APK（APK SHA 未生成），未操作手机或模拟器，视觉未验证。本轮无布局或文案变化。真实文件系统链接及 Robolectric 检查证明路径、初始化与恢复行为，不证明当前 APK 的启动或像素/触摸验收。新 head 的 CI、规定 S1/S2、R8、签名和 API33 设备验收仍由独立审核端完成；原 S1 失败仅绑定 d997120f13727a91896efb467a48a0425e0a8b81。

本轮原始证据：`E:\Desktop\AI研究\theta-auto-loop\event-runs\6e6590a2-1a18-4374-8bea-f5a026a43aec`。修复前后定向结果：`validation-issue11/issue177-6e6590a2-before-fix`、`validation-issue11/issue177-6e6590a2-targeted`。`diagnosis.json` 记录原 S1 日志哈希及证据边界。完整 preflight 日志/XML/lint 保存在本轮目录。

此前四次退回及修复证据继续保留在 event-runs `33768566-64c9-4d43-b9c9-a198e4611d02`、`fcc5a1d2-4029-482f-89bd-46fad2e67461`、`8f4e0313-ee76-4fe9-aaa1-014658cafd19`、`68fcca79-a5c6-4697-ae63-792f5b5cb4be` 和原 reviewer out 目录。此前主题恢复 GPU 回归与写手验证保持原 SHA 绑定，不转移为当前设备验收。历史零用例探针生成失败仍只记作生成失败。审核员安全模式修复和原摘要历史仍在提交历史中。

写手仅提交 3 个实现/测试文件；没有提交 summary.md、日志/XML/APK 或 Probe，没有删除断言、放宽超时或改保护文件。发布器负责登记唯一测试摘要和推送，审核端负责独立审核、最终回归和合并。
