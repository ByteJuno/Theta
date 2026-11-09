# S1 验收台账

S1 可行性验证的现行门槛已于 2026-11-09 补齐，按候选/场景累计收尾。依据为下列已接受的独立记录，不以单次脚本退出 0 代替整个阶段验收；这不表示一个新 APK 重跑了所有历史矩阵，也不是产品发版。

## 2026-11-09 现行 S1 收尾

| 适用门槛 | 证据归属 |
|---|---|
| A/H：R8、Compose、独立插件及 HTTPS/平台类 | 下文 `93142b3` 完整构建/链接/Probe，及 [runtime increment](S1_RUNTIME_INCREMENT.md) 中 candidate9 的实际 HTTPS 204/TLS 和 18/18 UI 专项 |
| B/C：批准后的冷切换、撤销、卸载及数据选择 | `93142b3` 生命周期、candidate9 冷切换/进程门控/保留数据/明确删除专项；不再要求热卸载或 GC |
| B/I：代码签名、无签名 DATA、异常包、摘要及写入中断 | 历史签名矩阵、DATA ZIP/accepted storage20，以及新 [CODE Host UI / 16 场景](S1_CODE_PUBLICATION.md)；各自精确 APK 身份独立 |
| B/D、D/E：登记关闭竞争、启用/受管协程/工具/Compose 故障、监听释放、超时和实际退出 | runtime increment 的 candidate7/candidate9、RegisterFail focused、长期监听、执行容量及配对 actualExited 专项；本轮 579 项 JVM 通过、1 项既有条件跳过 |
| F：快照、失败/阻塞后冷回滚及 code-only 策略 | candidate7/candidate9 data/prefs/secrets 默认取消、快照、失败/阻塞冷回滚、明确 code-only；精确 24 小时规则为生产 helper JVM 证据 |
| G：CI 体积与真机帧耗时量化 | [物理收尾](S1_CLOSEOUT.md) 中 CI42599041866 冻结配对、PLK110 主屏 ABBA / 2,880 帧 |
| J/K：真实 Hook、scope、同 PID 开关、崩溃隔离及外部救援 | 同一物理收尾中冻结 Host `bafeae5d…d6ed` / Target `4da24b17…11ad`；真实通知回调、三次自然崩溃、同签 v4 恢复、Host 不可用时 manager 撤销 scope |

完整 CODE 发布前中断均未激活 v2；MOVED 后完整签名包按生产冷恢复安全激活，与 DATA selection commit 的语义分别记录。真实断电不在本次进程死亡证明内；Host 不存在可信索引 expectedSHA 的产品输入路由，DATA API expectedSHA 与 Host UI 审核后摘要拒绝分别验证。已披露的单设备、具体 ABI、内部状态采样及未覆盖组合保留，不新增全历史矩阵重跑条件。范围仍为 DESIGN §11、批准后的 B/C 与 S2 排除项；资源租约、跨会话隔离账本及 agent 能力仍在 S2。

以下各日期章节保留当时的结果及未完成状态；现行逐项边界见 [S1_VALIDATION.md](S1_VALIDATION.md)。PR #43 仍 OPEN 待审，不合并。

## 2026-11-08 PR #7 审查修复与完整回归（`93142b3`）

PR #7 = 另一台电脑的 S1 运行时增量 `bf18072`（入口/工具、执行预算、prefs/secrets、崩溃恢复、code-only 回滚选项、Modern Hook 接入及样例）加 lab 审查修复：

- `709400b`/`bc44f82`：`PluginExecutionBudget` 原先在进程单例里每 5 ms 固定轮询取消请求，进程存活期间一直空转。改为取消请求和取消投递完成时派发，只有派发被拒（两个取消线程都忙）才排一次 5 ms 重试。CI push 触发分支恢复为只有 main。`signature-artifacts.py` 兼容 build-tools 37 apksigner 的 `V2 Signer:` 输出（要求 `Number of signers: 1` 且证书摘要唯一），本轮签名套件即用默认的 37.0.0 跑通。
- `bc44f82`：`PluginImportSourceTest.cancelWaiterReachesActiveCloseWithoutHoldingMutationGate` 是测试自身的启动竞争：waiter 协程在 Dispatchers.Default 调度它之前就被取消，`stagePluginCopy` 从未执行、copy 永不取消，复制线程永久阻塞（线程转储：waiter 已完成、copy 未取消）。与 `PluginEntryRuntimeTest` 同 JVM 运行时稳定复现，即 CI push run 的那次失败。改为 `CoroutineStart.ATOMIC`；生产路径的 copy 在已运行的协程内创建，不受影响。
- `93142b3`：PR #7 在 `store.install` 前调用 `prepareInstall`，同版本/低版本重装的提示从 `Install requires a higher version than N` 变成笼统的 `Update confirmation does not match installed version`（拒绝本身正确、数据不变）。恢复具体提示，仍不写 install intent，新增单测。
- `6a5b693`：宿主列表因观察期说明、工具按钮、入口状态和 Hook 面板变长到数屏，生命周期脚本固定“下 3 上 3”的查找会回到起点、到不了顶部的错误提示。改为向上滑到屏幕不再变化再向下，交替进行；`absent()` 同样从顶扫到底。

### 完整回归：GPU 服务器

`93142b3` 的 `git archive` 原样源码，环境同下节（`~/Bai/theta-emu/`，KVM 专用 Android 14 x86_64 模拟器 `emulator-5580`，每次 `-wipe-data`），结果目录 `results/20261108-071557/`。

| 检查 | 结果 |
|---|---|
| 构建、lint、JVM 单测 | assembleDebug/assembleRelease 成功；lintDebug 0 errors、16 warnings（版本提示、建议改用版本目录、冗余 SDK_INT 判断，以及 Hook 模块在目标进程里反射 `Application.attach`）；JVM 357 项 0 失败 0 跳过（宿主及 blocked-provider、storage-fixture 样例） |
| 链接预检 | Hello、Probe 及全部 fixture 变体 0 bundled、0 missing |
| Probe 冒烟 | 18/18，完整交互、禁用/重启/恢复/卸载流程 OK（192 秒） |
| 生命周期 | 17/17 一次通过（含 data-zip） |
| 签名 | 7/7 通过（921 秒，默认 build-tools 37.0.0）：signed-upgrade-and-fingerprints、unsigned、byte-tampered、different-signer-update、retained-data-binding、clear-data-uninstall、retained-clear-data-recovery |
| GitHub Actions | PR Check run 42458056673：build 与新增 paired-keep-size 均通过 |

Release 宿主 SHA-256 `866cbc77c8c98fb2661f23c8aee839f7819fe48fef0b2180603d8fafaf8b5a63`，Debug 宿主 `060dd1fe1711e5a02b8e4146a28309dbfcffe64f893173dfa02bc08104d20c42`。

paired-keep-size 首次实际在 Actions 执行（`MEASURED_CI`，非真机、不涉及帧或兼容性）：production 13,937,590 bytes，去掉共享库 keep 的对照 2,473,682 bytes，差 11,463,908 bytes；对照包不能加载外部插件，不是生产替代方案。

中途两次未通过的运行保留在同目录：`20261108-070342`（`6a5b693`，upgrade-and-data 找到的是新提示，签名套件运行时源码目录被替换，整轮作废）与 `20261108-063543`（`bc44f82`，列表扫屏到不了顶部；签名 7/7 用 build-tools 37 已通过）。

本轮不覆盖：G 真机帧耗时、J 真实 LSPosed 框架加载与 `requestScope`、K 目标崩溃循环跳过、开关传播与框架外救援；PR #7 自带的逐候选设备专项证据见 [S1 runtime increment](S1_RUNTIME_INCREMENT.md)，本轮未重跑。完整 S1 仍未完成。

## 2026-11-08 PR #6 审查修复与完整回归（`0f5a779`）

PR #6 = `55a1e57`（另一台电脑开发的数据 ZIP revision-5）加 lab 审查修复 `a62f016`、设备脚本修复 `5cf33e9`/`0f5a779`。

审查修复（`a62f016`）：

- 代码发布恢复原先要求每个旧版本目录只含归档，运行时生成的 oat 等文件会让此后所有导入永久卡在 intent 恢复；改为只要求本 intent 的版本目录精确等于载荷，旧版本只检查不含另一类型的归档。
- 数据更新后只保留当前与上一版归档（尽力清理）；清理中断留下的空版本目录在已有选择时不阻塞后续更新。
- 损坏的 intent.properties（非法转义）原先抛 IllegalArgumentException，点 Retry import cleanup 时宿主崩溃；改为 IOException。
- 数据侧代码残留检查改用与代码侧同一份保留路径列表；删除未用的 forgetMoved。新增 4 项回归单测。

设备脚本修复：数据包一栏在代码包上方，Hello 卡片之后的插件卡片落到首屏外。冒烟脚本的卡片查找改为上下滑动、不可见断言检查整个列表（`5cf33e9`）；受管 scope 两处 20 秒限时等待改为滑动查找，数据确认弹窗的复选框被弹窗正文裁切时先滚动正文再点可见部分（`0f5a779`）。新增设备用例 `data-zip`。

### 完整回归：GPU 服务器（权威结果）

`0f5a779` 的 `git archive` 原样源码；Ubuntu 22.04 x86_64，KVM 加速的专用 Android 14 x86_64 模拟器（`emulator-5580`，default 镜像，每次 `-wipe-data`），JDK 25.0.5、Gradle 9.7.1（发行包取自国内镜像，SHA-256 与 wrapper 声明一致）。工具链与证据都在 `~/Bai/theta-emu/`（结果目录 `results/20261108-031539/`）。脚本的 `S1_INSTABOX=1` 是“一次性专用环境”的声明，这里同样只操作专用模拟器。

| 检查 | 结果 |
|---|---|
| 构建、lint、JVM 单测 | assembleDebug/assembleRelease 冷构建成功；lintDebug 0 errors、8 warnings；JVM 177 项 0 失败 0 跳过 |
| 链接预检 | Hello、Probe、12 种 fixture 均 0 bundled、0 missing |
| Probe 冒烟 | 18/18，完整交互、禁用/重启/恢复/卸载流程 OK（185 秒） |
| 生命周期 | 17/17 一次通过（2032 秒）：upgrade-and-data、disableThrow、cleanupThrow、cleanupBlock、initThrow、initBlock、interrupted-initialization、interrupted-uninstall、rejected-packages、rollback-init-throw、rollback-init-block、rollback-interrupted、rollback-manual、scopeThrow、scopeInitThrow、rollback-scope-init、data-zip |
| 签名 | 7/7 通过（919 秒）：signed-upgrade-and-fingerprints、unsigned、byte-tampered、different-signer-update、retained-data-binding、clear-data-uninstall、retained-clear-data-recovery |

Release 宿主 SHA-256 `34f9bbfe7069e00bf02dd7b3876bab9fbc98c77bb95c2e2fe56eccc1d4d1a0a5`，Debug 宿主 `282db9869960804711a202940a8a4b5973fde17076c2b672d5bb8741d8bdafa8`。

签名套件第一次在制品预检阶段失败，未进入设备：机器上默认选中 build-tools 37.0.0，其 apksigner 把证书行改为 `V2 Signer: certificate SHA-256 digest`，`signature-artifacts.py` 只认 `Signer #N certificate SHA-256 digest`。按脚本已支持的 `ANDROID_BUILD_TOOLS_VERSION=36.0.0`（与旧格式一致）在同一构建、重新清空的模拟器上重跑，7 项通过。适配 37 的输出格式留作后续脚本改进。

`data-zip` 设备用例：含 classes.dex 的 ZIP 被拒绝；v1 确认页显示该文件的 SHA-256，未勾选不能安装，安装后只读页显示 `skills/demo/SKILL.md`；同 ID 代码 APK 以 Data-owned ID 拒绝且 fixture 未运行；v2 需重新确认，重启后内容为 v2；禁用跨重启保持、再启用可读；卸载后冷启动无数据包，同 ID 代码 APK 仍被拒绝。

### 补充：InstaBox 同一提交（未完成，不作为通过依据）

InstaBox（无 KVM 软件模拟器）上同一提交：冒烟 18/18、生命周期前 12 项、签名 7/7 通过；rollback-manual 期间免费版冷却把整台机器冻结约 2 小时 11 分（模拟器 logcat 16:09:32→18:20:15 UTC 无输出），恢复后首次 uiautomator dump 超时而失败，证据 `/data/work/theta-s1-20261107-145548-evidence/FAILED-rollback-manual`。补跑又遇到冷却，改到 GPU 服务器完成上面的完整回归。

不覆盖：阻塞 ContentProvider、旋转与请求交接的完整 Android 矩阵；数据包写入中断、断电、磁盘满；Provider/MCP 执行等其余见 [数据 ZIP 集成本机验收](DATA_ZIP_ACCEPTANCE.md)。完整 S1 仍未完成。

## 2026-11-07 数据 ZIP revision-5 本机增量验收

本轮实现、源码绑定、原始证据摘要及未覆盖项见 [数据 ZIP 集成本机验收](DATA_ZIP_ACCEPTANCE.md)。JVM 173 项中 172 通过、1 项因 Windows POSIX 执行位条件跳过；Debug/Release 构建、lint 及 14 个 Release 插件静态链接预检通过。数据设备流程 11 项、原生回归 7 项通过；双向类型归属由两轮覆盖 7 项（3+4），原始失败和未重跑项明确保留。

这些局部结果不代表完整 S1 通过；此前完整签名、异常、阻塞和回滚设备矩阵未在这份 APK 上全部重跑。以下 revision-3 段落保留当时的未执行状态，不能代替最新证据。

## 历史：revision-3 导入恢复增量（当时 NOT COMPILED / NOT TESTED）

以下仅为后续 InstaBox 空闲时的设备步骤；本轮未构建、未运行测试、未调用设备。

- APK 与 data 各导入一次并取消确认；删除空的 cache/plugin-import-sources 根目录后，在同一 PID 再导入，应重新建目录。
- 使用阻塞 open/read/close 的测试 ContentProvider；点 Cancel reading 后两个导入按钮立即恢复。重复取消再选，两个未退出的 worker 占满共享配额后拒绝第三次，不增加线程/文件；释放 provider 后恢复。
- 读取时旋转/离开界面，旧 request 的 finally 或迟到完成不得清除新 request 的 reading 状态、显示旧确认、删除新 stage。确认安装与旋转竞争必须只由持有 mutation gate 的安装接管 stage。
- 故障注入实际 APK/data stage 删除失败（包括本地 input.close 原异常）：检查原错误及 suppressed 清理错误；反复导入不得新增 stage。修复存储后点 Retry import cleanup，原路径可重试删除；已发布 plugin.apk/plugin.zip 不得被删除。
- 持有实际 manager mutation gate 时取消尚无 stage 的来源等待/排队，不应等 gate 才结束。取消时已交接 stage 的后台清理只处理旧 request，失败仍保留有界 ticket。
- 已启用 ZIP 被损坏/删除后先点 View data content：内容入口撤销，管理卡显示隔离原因、durable ID/version/digest，Disable/Uninstall 仍可用。冷启动坏 ZIP 同样保留卡；禁用先提交 false；非法包不得启用。混合 code/data 目录拒绝删除 code 文件。
- 本轮新增 PluginRevisionThreeTest 及更新已有测试都是回归源码，不代表 JVM 或 Android 调度验证通过。

## 已确认的实现口径

- dex 为全信任、同进程代码，不提供沙箱或强制终止保证。
- 禁用立即撤销宿主入口；进程内已尝试加载的 ID 不再重新创建实例。更新、再次启用和已加载插件的彻底删除通过重启生效。
- 旧设计中的热卸载和必须 GC 回收不是当前承诺；快照回滚、签名与故障恢复的缺口仍保留。
- lab 开发，InstaBox 在线时按需验证设备测试；不部署常驻调度。GitHub PR Check 跑构建、lint、单元测试和插件静态检查。

## 验收矩阵

| 项目 | 当前证据 / 状态 | 剩余工作 |
|---|---|---|
| A R8 + Compose | Hello/Probe 已设备通过 | 仅证明已覆盖 API，不是任意 Compose 兼容性证明 |
| B 安装、更新、卸载 | 代码 APK 同 ID v1→v2、重启切换、保留/删除数据及重装在 `c06c789`、`0f5a779` 回归通过；数据 ZIP 拒绝代码载荷、确认摘要、v1→v2、启停跨重启、卸载保留归属及跨类型拒绝在 `0f5a779` 设备通过（Android 14 模拟器） | 写入中断、断电/磁盘满及更多坏包路径；阻塞来源与旋转交接的 Android 矩阵 |
| C 运行实例回收 | 已按重启方案验证撤销入口、进程内不重复加载、重启删除；旧设计已明确修订 | 不承诺原进程 GC 卸载；要求变更不等于旧 C 通过 |
| D 异常恢复 | 初始化、onDisable、cleanup 历史用例通过；受管 scope 初始化/运行期故障及升级首启故障在 `c06c789` 设备通过，宿主 PID 保持 | Compose/线程崩溃后的自动恢复、工具调用边界 |
| E 非协作超时 | 初始化/清理阻塞、迟到登记拒绝及界面响应的初版用例通过 | 严格时间窗口复验已通过；资源预算、入口 ready 仍缺。工具资源租约和冷启动隔离记录已挪到 S2 |
| F 更新回滚 | `dataDir` 快照、首次启动失败/卡死/被中断后的冷启动回滚、手动回滚设备通过；观察期到期、快照失败取消和回滚重入有单元测试。受管 scope 增量补充损坏记录隔离、期限检查与恢复回执等修正，`c06c789` 全量回归通过 | `prefs`/`secrets` 快照（接口未实现）、不做快照继续的选项、观察期内主动提示 |
| G 性能量化 | 未完成 | 同条件 APK 体积对照及固定真机帧耗时 |
| H 共享库不重复打包 | Hello/Probe/12 种 fixture 的链接预检本轮通过，静态检查已进 PR Check | 预检不等于完整 ABI 证明 |
| I APK 签名 | 真实未签名、字节篡改、换签、同签升级与指纹、保留数据绑定、清除数据恢复 7 项设备通过（Android 14 模拟器） | 不含私有存储篡改、签名轮转、多签名；厂商系统未验证 |
| J Hook 远程加载 | 未实现、未验证 | Modern API 最小链路、requestScope、完整框架真机 |
| K Hook 救援 | 未实现、未验证 | 崩溃循环跳过、全局开关及框架外恢复的真机证据 |

## 2026-11-07 完整验证（`c06c789`）

PR #4 = `6ba24a4`（另一台电脑开发的受管 scope 故障隔离）加 `c06c789`（审查修复）。InstaBox Android 14 x86_64 软件模拟器，日志 `/data/work/theta-s1-20261107-014234.log`，证据 `/data/work/theta-s1-20261107-014234-evidence/results.json`、`/data/work/theta-sig-20261107-014234/device/results.json`。`VERIFY_S1_EXIT=0`（4815 秒），`SIGNATURES_EXIT=0`（3245 秒），两份报告都没有证据采集错误和未运行用例。宿主 Release APK SHA-256：`f6a78d2448ba9b91144760987a532cde29c358c762f0dc9b24d24b052b8a7240`。

- 构建、lint 通过；单元测试 93 项：PluginFaultBoundaryTest 16、PluginLifecycleTest 11、PluginRegistrationsTest 8、PluginSignaturePolicyTest 20、PluginUpdatesTest 38。
- 链接预检：Hello、Probe 和 12 种 fixture 均 0 bundled、0 missing。
- Probe/Hello 设备流程通过。
- 生命周期 16/16：上面 `95fb4ed` 的 13 项，加 scopeThrow、scopeInitThrow、rollback-scope-init。
- 签名 7/7，用例同 `95fb4ed`。
- 迟到登记断言有实际效果：scopeInitThrow 用新 ID 登记，在 worker 被取消后仍执行，事件为 `late-register:rejected`，全部证据中没有 `late-register:ACCEPTED`。

这一轮把 `S1_TOTAL_TIMEOUT` 临时放宽到 7200 秒作保险。生命周期阶段实际约 4400 秒，没超过默认 5400 秒，所以仓库默认值不变，但余量已不大，再加用例要先看这个值。

审查修复中，排队的故障处理不覆盖用户操作、同进程删除后清掉 `deferred` 这两条在 PluginManager 内，没有单元测试，也没有专门的设备用例，靠代码审查和既有回归兜底。

## 2026-11-06 完整验证（`95fb4ed`）

InstaBox Android 14 x86_64 软件模拟器，日志 `/data/work/theta-s1-20261106-153456.log`，证据 `/data/work/theta-s1-20261106-153456-evidence/results.json`、`/data/work/theta-sig-20261106-153456/device/results.json`。`VERIFY_S1_EXIT=0`，`SIGNATURES_EXIT=0`，两份报告都没有证据采集错误和未运行用例。宿主 APK 13,674,845 字节，SHA-256 前缀 `90de2fbf9395`。

- 构建、lint 通过；单元测试 61 项：PluginLifecycleTest 11、PluginRegistrationsTest 8、PluginSignaturePolicyTest 20、PluginUpdatesTest 22。
- 链接预检：Hello、Probe 和 9 种 fixture 均 0 bundled、0 missing。
- Probe/Hello 设备流程通过，用时 499 秒。
- 生命周期 13/13：upgrade-and-data、disableThrow、cleanupThrow、cleanupBlock、initThrow、initBlock、interrupted-initialization、interrupted-uninstall、rejected-packages、rollback-init-throw、rollback-init-block、rollback-interrupted、rollback-manual。回滚用例里的坏版本先把计数器改成 99，回滚后读到原值才通过。
- 签名 7/7：signed-upgrade-and-fingerprints、unsigned、byte-tampered、different-signer-update、retained-data-binding、clear-data-uninstall、retained-clear-data-recovery。

此前 `2621606` 一轮生命周期 8/9、签名 5/7，缺的项都是撞上 2400 秒总时限，不是断言失败。两个总时限已提到 5400 秒，这些用例在上面一轮重跑通过。

## 2026-11-06 故障测试记录

基线为 `406d667` 加未提交的 fixtures/测试脚本改动。完整测试插件共七种场景，不包含 Compose 故障；不得沿用另一份候选八场景实现的覆盖声明。

1. `/data/work/theta-s1-20261106-175319.log`（InstaBox）：构建、单测、链接、Probe 回归通过；upgrade-and-data、disableThrow、cleanupThrow 通过；cleanupBlock 因测试在回调已返回后仍要求未返回而失败，整体退出 1。保存的事件顺序正确，不能把整轮标绿。
2. `/data/work/theta-s1-20261106-182145.log`：修正上述断言后，cleanupBlock、initThrow、initBlock、interrupted-initialization、interrupted-uninstall、rejected-packages 六项通过，退出 0。前三项未在此轮重跑，报告明确列为 not_run。
3. `/data/work/theta-s1-20261106-185614.log`：补强观测后，cleanupBlock、initBlock、interrupted-initialization、interrupted-uninstall 四项通过，退出 0。断言要求超时状态在阻塞返回前出现、force-stop 完成后再次确认未返回，且等待函数不接受超过观察期限的成功。报告 `/data/work/theta-s1-20261106-185614-evidence/results.json` 无证据采集错误，明确列出其余五项未重跑。此次复用与前轮相同的宿主/fixture APK，未重跑构建和单元测试。

设备报告记录 APK SHA-256。各轮日志与 evidence/results.json 独立保存；源码后续变更必须重新核对，未验证项保持未验证。

## 2026-11-06 签名增量：实现与阻塞

后续：InstaBox 恢复后读到 194728 任务构建成功，单元测试 39 项全部通过。下面保留当时的记录，设备结果见上面的完整验证。

- 12 个签名实现/测试文件经原始 SHA-256 核对后应用到 lab，覆盖平台验证、完整当前 signer 集合、确认后绑定、发布前及加载前复验、显式清除数据恢复。首次发布失败可即时显示恢复入口；清理异常不覆盖原始安装异常。单元测试使用 fake verifier，只验证策略，不证明密码学验证正确。
- InstaBox 同步 62 个文件后启动构建，返回 PID `3398450`；原日志 `/data/work/theta-s1-20261106-194728.log`，退出文件 `/data/work/theta-s1-20261106-194728.exit`。请求包括 assembleDebug、assembleRelease、lintDebug、testDebugUnitTest。
- 监控请求遇到 HTTP 409；后续读取仍失败。服务明确返回 `machine_cooldown`，冷却至 `2026-11-06T13:59:13Z`。监控退出 1 不等于 Gradle 退出 1；尚未取得构建退出码、日志和测试报告，结果保持未知。恢复访问后先读取原任务，不重复启动或同步覆盖可能仍在使用的源码。
- 三份真实签名设备验收脚本已放入 lab 的 scripts/，独立静态复核未发现已确认逻辑缺陷；依赖 helper 已存在。尚未同步这三份脚本到 InstaBox，未生成签名样本或执行设备测试。
- 文档已协调原 B/C 与重启模型，保留原验收历史及全部其他未完成门槛。本轮未提交或推送，不转移测试到 lab，也未建立常驻调度。

## 2026-11-07 受管 scope 增量：实现时记录

以下保留实现阶段的未验证状态，当时补丁尚未提交，后来提交为 `6ba24a4`（PR #4）。最终结果以上面的 `c06c789` 完整验证为准。

原始补丁基线 `95fb4ed8bc1a1838a925fbb06713ffdf50d9344b`；文档已协调到 `a5e1d8bfd7a7498051059e20b9e4bac05d004d49`，该提交只更新四份文档，非文档目标原始哈希及新增路径不存在均已核对。保留上文 `95fb4ed` 完整验证证据和 `2243d80` 的现行 E 要求；这些证据不证明本地增量通过。本轮仅本地暂存修改与内容哈希核对，lab 只读，未构建/编译/单测/链接检查/设备测试、未触发 CI、未提交或推送。此前六项修正及全部非文档补丁内容保持不变。

- 私有 fault boundary + CoroutineExceptionHandler：原子记录首个非取消异常，立即关闭登记，异步交给 Main 上串行管理路径；先尝试持久化禁用，再取消 scope 和复用有界清理。初始化等待同时观察 fault，不等待持锁的宿主回调；运行实例身份校验、重复故障和关闭后故障幂等。登记通知只在 Main 更新 Compose，延迟通知读取最新快照，不恢复旧入口。
- onEnable 返回后的短内存仲裁决定首启故障还是运行期故障；仲裁前的故障不能写更新成功标记。仲裁成功后必须完成两项持久化并再次检查故障才发布 active/Running，IO 期间到达的故障不暴露页面，按运行期禁用且不自动回滚。失败更新保留首启回滚语义；resumeAfterRollback 通过 RECOVERED 持久回执恢复旧版启用意愿，状态写失败可冷启动重试，确认后才删除回执。存储不可写时不能承诺下次启动仍已落盘禁用，错误必须可见。
- 本轮六项审查修正另含：只记录异常类名（不运行插件异常格式化）；持久化后报告且 finally 关闭；成功删除/验证替换才解除损坏隔离；已接受回滚跳过可选资格刷新并保证关闭。新增竞争、恶意异常、恢复切点及删除重装源码测试均未执行。锁竞争用例连接 Manager 共用的 postPluginOperation 与同一把竞争 mutex，并检查处理前 worker 已观察故障、释放后仅处理一次及异常身份；这是共享调度/存储 helper 单测，不声称完整 Manager/Android Main/Compose 集成覆盖。既有设备场景均保留，真实断电和界面端到端验证仍缺。
- F：损坏记录按 ID 隔离，其他插件继续加载、故障插件仍可卸载；UI 与动作共用期限规则，过期新请求被拒绝，已接受请求与安全模式快照保留不受影响。
- 新增 3 个 flavor：scopeThrow、scopeInitThrow、scopeInitThrowV2，保留此前 9 个 flavor 和全部设备用例；verify-plugins.sh 的链接检查枚举同步。新增单元源码测试实际 boundary、publication、worker、更新/状态存储与恢复 helper，不是独立仿真状态机。
- 待 InstaBox 执行：构建/lint/单测、全部 12 个 fixture 链接检查、既有生命周期/签名回归，以及 lifecycle-acceptance.py 的 scopeThrow、scopeInitThrow、rollback-scope-init。新增设备断言包括故障前后 PID 相同、20 秒观察界限、入口撤销、Hello 页面可用、不重复实例化、自动禁用后冷启动不执行；自动禁用持久化与手动开关断言分轮，避免混淆证据。记录 APK 哈希、日志、PID 与 results.json，未执行项继续 not_run。
- 不新增公开 SDK，不承诺 Compose 组合期、插件任意线程/native 崩溃隔离；Default 也不构成阻塞隔离。遵守 `2243d80`：S1 E 仍需超时判定、实例实际退出及资源预算证据，工具资源租约/调用账本与冷启动隔离记录移到 S2，不记为 S1 通过。

## 2026-11-07 受管 scope 增量验证结果

基线 a5e1d8b 加未提交的 14 文件补丁及 Kotlin 显式类型修正。仅在 InstaBox 执行；71 个非文档源码文件与 lab 全部一致，额外 pycache 为测试生成文件。本轮未提交或推送。

- 原 021922 轮因不存在 testReleaseUnitTest 退出；022053 轮因 PluginManager 递归类型推断编译失败。修正 publication 属性及 publicationSnapshot 返回类型后，022445 快速编译退出 0；失败证据保留，没有用旧报告充当新结果。
- 构建日志 `/data/work/theta-s1-20261107-022522.log`：退出 0，2m21s，1385 tasks executed；assembleDebug/Release、lintDebug/Release、testDebugUnitTest 通过。新鲜 XML 87 项（Lifecycle 11、Updates 36、SignaturePolicy 20、Registrations 8、FaultBoundary 12），零失败/错误/跳过。Release 单测任务不存在，未执行。
- 链接日志 `/data/work/theta-s1-20261107-022809.log`：退出 0，Hello/Probe 与全部 12 个 fixture 通过。
- 设备日志 `/data/work/theta-s1-20261107-022858.log`、报告 `/data/work/theta-s1-20261107-022858-evidence/results.json`：退出 0；scopeThrow、scopeInitThrow、rollback-scope-init、rollback-init-throw、rollback-init-block、rollback-interrupted、rollback-manual 共 7/7 通过，evidence_errors=[]。两项 scope 故障 PID 保持断言为 true。
- 本轮未重跑九项旧生命周期用例：upgrade-and-data、disableThrow、cleanupThrow、cleanupBlock、initThrow、initBlock、interrupted-initialization、interrupted-uninstall、rejected-packages；也未重跑完整 Probe 交互或真实签名套件。历史通过不能外推为当前补丁完整回归。
- Release APK SHA-256：`b5cc88e3e74ccfee3589dced53524c7e26be01733f64754e08df404ce12adc9e`。
- lab 证据归档：`/home/ubuntu/ai-work/theta-tools/managed-fault-verification-20261107-021814`，含失败及成功日志、退出码、产物清单、PID 和源码差异说明。

本增量不证明 Compose/任意线程/native 崩溃隔离、真实断电、prefs/secrets 快照或完整 S1 完成。helper 单测与这七项设备断言分别记录，不互相替代。这一轮没覆盖的旧用例已在上面的 `c06c789` 完整验证中补齐。

## 历史：2026-11-07 数据 ZIP 集成候选（当时未编译 / NOT RUN）

固定基线 `4eb39fb883646831395090f032c3c26f59bf7177` 的独立归档副本；不代表已合入当前分支，不沿用上面的 APK 或测试结果。基础四文件先按本地 patch-manifest.json 核对；集成另扩展共享 ZIP 校验器的内部内容访问回调，并修正单进程锁说明。原基础目录不变。

- 两个显式导入入口；ACTION_VIEW 先选 APK/data。数据确认显示名称、ID、版本/API、provides/declares、全部完整 URL 和 host、摘要及作者未验证提示。每次无签名更新都需对当前 staged digest 重新确认；APK 失败不回退。
- 独立 `plugin-data-catalog/<id>/selection.properties` 保存已确认选择、摘要、启用及删除意图；code/data store 暂存、发布和状态操作共用单例 manager mutex。revision-2 外部 URI open/read/close 完全在锁外，进程级两槽立即准入、无等待队列，最多两条 copy 和两条 close daemon；APK 256 MiB/data 16 MiB 上限。调用者取消可停止等待，关闭立即通知 active/迟到 stream；提供方或内核不响应关闭时占用槽位到真正结束，耗尽则拒绝后续导入，不声称能杀死 native IO。IO 不在 Main，无跨进程锁。
- 发布文件不等于接受版本；状态提交失败保留旧选择，孤立新版本不自动激活。恢复复验所选包和摘要，损坏记录按 ID 隔离。data ID 卸载仍保留 ownership；代码导入、加载、恢复和删除拒绝 data-owned ID。混合归属不自动清理，没有显式释放入口。
- 数据独立列表，启用/禁用不用 dex 一次加载门控；safe mode 不发布数据页。禁用只要求归属和可写生命周期记录，不读取已损坏/删除的 ZIP；先保存 disabled 再撤销页面，返回卡片只复用匹配的内存 manifest，没有匹配项就不生成卡片。启用和内容读取仍完整复验。卸载先保存意图、撤销页面，再删包，冷启动继续未完成删除。
- 宿主只读页面绑定不可变选择身份；更新/禁用/卸载撤销旧页面，不偷偷切版本。每次读取使用同一严格 ZIP 校验器，16 MiB 压缩、32 MiB 展开、1 MiB 单项、512 项；Compose 只留路径和当前单项，文本分块，不抽取文件、不执行、不连网、不初始化插件私有数据。
- Provider/MCP 仅元数据，schemas 尚不完整；没有 provider engine、MCP 连接/认证、persona 应用、locale override、Skill frontmatter/执行或新公开 SDK。DESIGN 的“下一次运行生效”依赖未来 agent，当前仅撤销并替换宿主内容页，不声称 agent-run 语义已经实现。
- 新增真实文件系统回归源码 PluginDataLifecycleTest：确认/摘要变化、孤立发布、状态提交失败、禁用持久化、同进程重启用、安全模式选择谓词、恢复损坏隔离、删除失败重试、跨类型归属及内容限制。它不是完整 Android manager/UI 集成测试。

- revision-2 新增 PluginImportSourceTest 源码，调用生产两阶段 copy/stage helper 和真实 APK/data store：阻塞读取时同一 mutation gate 仍可用、调用者取消、迟到 open、阻塞 close 和准入上限、外部/本地 close 抛错、pending stage 清理、异常 suppressed、交接前取消及字节上限。PluginDataLifecycleTest 增加 ZIP 损坏/删除后的禁用持久化与生产页面 membership helper、无缓存 manifest、失败提交不撤销。它们均不是已经通过的测试，也不等同完整 Android Manager/Compose 端到端覆盖。
- 临时副本直到消费者释放才删除；失败删除保留准入槽，避免反复失败累积无限文件。独立 cache 子目录在进程首次使用时清理上次遗留；永久阻塞可保留最多两个有上限的副本直至重启。stagePluginInput 在 stream.close 前记录暂存所有权，任何关闭/校验失败均消费暂存，清理异常保留为 suppressed。

**本轮验证：NOT COMPILED / NOT TESTED；构建、lint、JVM/设备测试全部 NOT RUN。InstaBox 被占用，未调用。** 仅源文件阅读、哈希及补丁格式检查，不宣称 B/I 验收完成。

### 后续 InstaBox 设备验收步骤（独立于 JVM 源码测试；本轮未执行）

1. 在新的 InstaBox 副本应用候选补丁并核对清单；运行构建/lint/完整 JVM 回归，确保 PluginDataStoreTest、PluginDataLifecycleTest 和 PluginImportSourceTest 被实际发现。记录命令、退出码、新鲜 XML、源码及 APK SHA-256，不复用历史通过。
2. 制作 v1/v2 data ZIP（根 manifest，skills/hello/SKILL.md、persona/lang JSON、含完整路径和查询串的 provider/MCP URL），用两按钮和外部 ACTION_VIEW 验证选型。取消、换包、旋转、后台销毁及大文件 staging 中退出，检查合作提供方退出后 staging/cache 无遗留；安装期间销毁不得删除 manager 正消费的 stage。分别注入阻塞 open/read/close 和 close 抛错；同时操作另一插件，确认共享门不被占用；两槽耗尽后立即拒绝新导入，不新增线程/任务；迟到 open 关闭且不发布，只有真实退出后恢复容量。不可取消提供方只允许保留文档声明的有界资源，不以“超时成功清理”记账。
3. 确认页逐项核对所有字段和每个完整 URL/host；不勾选不可安装。v1 安装后进入独立列表/只读页，核对文本；故意通过 APK 按钮导入 ZIP，必须失败且无 data fallback。
4. 禁用时已打开的页消失；同一 PID 再启用可查看。另在激活后分别破坏/删除选中 ZIP，禁用仍持久化 false 并撤销旧页，重新启用必须失败。force-stop/冷启动保留禁用；force-stop 后携带 skip_plugins=true 启动，启用意愿可保留但不发布页。不要把已有进程收到恢复 intent 当冷启动。
5. v2 必须新确认；保留旧选择的禁用意愿。已打开 v1 页撤销；重新打开才看 v2。故障注入选中状态提交失败，旧选择保持；冷启动绝不因更高目录自动激活。恢复时分别破坏一个状态和一个 ZIP，其他 ID 正常。
6. 卸载先观察 intent/页撤销，再中断删除并冷启动恢复；完成后归属仍保留。尝试同 ID 代码包及 rejected-code 清理，不得删 data 归属/包。植入混合 APK/ZIP 树只隔离，不自动删除。
7. 超限/坏 CRC/重复路径等坏包无活跃页；打开最大允许单项观察内存和交互响应。确认无网络、dex 加载、Skill 执行、locale/persona 应用或私有 dataDir。记录 PID、前后状态、路径、日志和 NOT RUN 项。
8. 重跑既有代码签名/生命周期/回滚设备回归，确认原生全信任及重启模型没有变化。UI 异步竞态、实际磁盘满/atomic move、断电与内存峰值仍需专门证据。

## 仍需设备条件

当前仅确认 InstaBox 上 Android 14 x86_64 模拟器可用。G 的真机性能和 J/K 的完整 LSPosed 真机测试不能由此替代。缺少设备证据不意味着这些项目通过，也不应阻止先完成签名、回滚等可继续实现的工程工作。
