# S1 验收台账

S1 尚未完成。此台账区分现有证据、待实现能力与需真机的项目；不以单次脚本退出 0 代替整个阶段验收。

## 已确认的实现口径

- dex 为全信任、同进程代码，不提供沙箱或强制终止保证。
- 禁用立即撤销宿主入口；进程内已尝试加载的 ID 不再重新创建实例。更新、再次启用和已加载插件的彻底删除通过重启生效。
- 旧设计中的热卸载和必须 GC 回收不是当前承诺；快照回滚、签名与故障恢复的缺口仍保留。
- lab 开发，InstaBox 在线时按需验证设备测试；不部署常驻调度。GitHub PR Check 跑构建、lint、单元测试和插件静态检查。

## 验收矩阵

| 项目 | 当前证据 / 状态 | 剩余工作 |
|---|---|---|
| A R8 + Compose | Hello/Probe 已设备通过 | 仅证明已覆盖 API，不是任意 Compose 兼容性证明 |
| B 安装、更新、卸载 | 实际同 ID v1→v2、重启切换、保留/删除数据及重装在 `c06c789` 回归通过 | 数据 ZIP 路径、写入中断及更多坏包路径 |
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

## 仍需设备条件

当前仅确认 InstaBox 上 Android 14 x86_64 模拟器可用。G 的真机性能和 J/K 的完整 LSPosed 真机测试不能由此替代。缺少设备证据不意味着这些项目通过，也不应阻止先完成签名、回滚等可继续实现的工程工作。
