# S1 插件加载原型验证

当前是 S1 的增量原型，不是完整的插件管理器或可发布版本。**S1 尚未完成**。最新完整验证对应 `93142b3`（PR #7 运行时增量及审查修复，含此前 PR #6 数据 ZIP 与 `c06c789` 受管 scope 故障隔离），结果见 S1_ACCEPTANCE.md；下文的历史通过记录只覆盖当时的代码；后续已接受的本地/JVM/模拟器专项、已执行的 Actions 体积对比及延期真机门槛分层列于本页，不用历史缺口否定后续限定通过。

2026-11-07 数据 ZIP revision-5 的本机增量验证见 [独立记录](DATA_ZIP_ACCEPTANCE.md)。该记录补充当前实现与局部通过范围，不覆盖下文全部 S1 门槛。

2026-11-08 PR #7 合并前的审查修复与完整回归对应 `93142b3`：GPU 服务器上 JVM 357 项、冒烟、生命周期 17/17、签名 7/7 通过，GitHub Actions build 与 paired-keep-size 通过，见 S1_ACCEPTANCE.md。

2026-11-08 PR #6 合并前的审查修复与完整回归对应 `0f5a779`：GPU 服务器 KVM 模拟器上 JVM 177 项、冒烟、生命周期 17/17（含新增 data-zip）、签名 7/7 全部通过，见 S1_ACCEPTANCE.md。

后续入口/工具、设置、自动恢复、回滚选项及 Modern Hook 接入的实现与逐候选证据见 [S1 runtime increment](S1_RUNTIME_INCREMENT.md)。Hook 修复后的第九轮冻结构建共 320 项 JVM 测试（319 通过、1 项既有 Windows 跳过），Debug/R8 Release、lint 和 Hook 样例构建通过；后续 provider/parser 增量为宿主 322 通过、1 跳过，provider 8 项通过。candidate9 的 HTTPS、阻塞来源、长期监听、组合崩溃恢复、登记关闭竞争和执行容量已有专项设备证据；不把这些局部结果外推为最终完整矩阵。J/K 源码已独立审查并修复，真实框架运行、开关传播及目标进程救援仍未验收。

## 当前收口状态（2026-11-08）

- **已接受的限定范围**：本地构建/JVM/静态检查、按 candidate7/candidate9 分别绑定的模拟器专项、LOCAL_ONLY 成对体积实验，以及独立 DATA production-store fixture 的 Android expectedSHA staging 拒绝和 10 次进程死亡恢复。已完成专项不再标作待执行，但不宣称全部 S1 通过，也不把历史 FAILED 或候选身份改写。
- **Actions 已执行**：PR #7 的 PR Check run 42458056673（`93142b3`）中 paired-keep-size 实际运行并通过（`MEASURED_CI`）：production 13,937,590 bytes、去共享库 keep 的对照 2,473,682 bytes，差 11,463,908 bytes。本地成对构建与 candidate9/storage20 的历史设备证据仍各自独立，不互相追认。
- **原有真机门槛继续延期**：G 固定真机帧耗时，J 真实框架远程文件加载/生效及 requestScope，K 目标崩溃循环跳过、全局开关传播、目标重启和框架外救援。宿主恢复、模拟器和 JVM helper 不替代这些门槛。
- **具体证据缺口保留在对应层**：storage20 没有独立导出的底层持久化文件/目录快照，未覆盖 candidate9 主界面的 expectedSHA/publication 切点、Android CODE 发布中断或物理断电。旧专项的 listener host actualExited、私有 tombstone/回滚状态及精确 idle 续期等未观测项仍按下表披露；这些限制不撤销已接受结果，也不扩展成任意包、完整故障矩阵或全量历史重跑的新门槛。

S1_ACCEPTANCE.md 中保留历史实现期缺口；当前进展以本页和 runtime increment 的逐候选证据为准，不修改 DESIGN.md §11 的范围、批准后的 B/C 口径或 S2 归属。

### 独立 DATA Android 存储专项已限定接受

依据本批独立只读核验结论，接受 `storage20-20261108T045759120033Z`，不是只接受其四项 JVM 测试。索引为工作区根下 `device-control-ascii-retry/storage20-android-evidence-index-20261108.json`（10–26、180–289 行），原始运行在同目录 `runs/storage20-20261108T045759120033Z/commands.jsonl`；完整边界与源码位置见 [runtime 存储专项](S1_RUNTIME_INCREMENT.md#accepted-android-data-storage-fixture--2026-11-08)。本次文档整合不重跑设备或构建。

- 104/104 索引文件 SHA-256 匹配；冻结 APK、原构建输出与运行副本均为 3,601,058 bytes / `260e7ca12b9cd918e79b2b08a0feae67cdc4c90b2e42bd598fc427745143b231`，安装成功且安装后哈希匹配（原始命令 29/43 行）。`build-work` 181/181 匹配；Debug/Release 编译输入各 14 个生产文件＋2 个 fixture 文件，测试输入 1 个文件，4 tests / 0 failures/errors/skipped，lint 3 warnings。不计 controller 陈旧的 327 项统计。构建日志 `evidence/twentieth-storage-fixture/build.log`（23–26、82–108 行）SHA-256 为 `659c8e4eaf8e6428594dbfba7da079a86b75c9f6373d7b2f87394ad3f0602758`。
- 10 个 mismatch 均先用同一合法 v2 ZIP 完成结构/正确 expectedSHA staging 正控，再只改 SHA 首字符，精确拒绝为 `Data ZIP index digest mismatch`；正控不是发布/激活证明。五个 publication hooks（INTENT、TEMP_CREATED、PAYLOAD_READY、PARENT_READY、MOVED）× enabled/disabled 齐全；40 个唯一 requestId、10 个唯一 runId 与启动、返回 JSON 及切点身份相符，149 个 guard 均 VERIFIED。
- 十次 CRASH 后、RECOVER 前的外部 `pidof` 均 exit=1/空 stdout（原始命令 113、211、309、407、505、603、701、799、897、995 行），随后均为 COLD 且 PID/processToken 变化。fixture 自行 `Process.killProcess`，没有外部 kill、force-stop 或 reboot。恢复实际读取持久化 selection/ZIP，10 组均保留 v1、原 enabled、相同 selected/confirmed SHA、pendingDelete=false；仅两个 MOVED 留下已发布但未选择的 v2，其余八组无 v2。MOVED 在原子移动之后、selection 提交之前，不是更新已激活。
- 底层状态仍是经源码核对、跨进程执行的 fixture 文件读取断言：未直接导出 baseline.properties、selection.properties、v1/v2 ZIP 或前后目录清单，不称为独立 raw filesystem verification。保存的 cutpoint.properties 有 Windows 文本写入的额外 CR；解析属性/索引哈希匹配，但不是 stdout 字节副本，原 stdout 在 commands.jsonl。核验时当前 source 为 179/181，差异仅本次两份状态文档，全部编译输入仍匹配；`source_matches_frozen_build=true` 只指冻结时，嵌套 `device_status=NOT_EXECUTED` 只指构建期，不否认后续 Android 执行。
- candidate9 前后哈希仍为 `8d2dcb55ede9fda5c774d47a2008d44f63ea81894cf703c8b3aae2e3fc488606`（命令 15/1044 行），PID 仍 21094（22/1030 行）。这是独立 DATA production-store Android 集成，不是 host UI、CODE 或物理断电验收；仅一个模拟器、固定合法包、既有 v1 上更新 v2，不外推首次安装/任意包。原 `storage20-20261108T045336962503Z` 的 `results.json` 第 2 行与 `commands.jsonl` 第 1 行仍为 FAILED：aapt Unicode 路径 `Illegal byte sequence`、exit=1、尚未安装；ASCII 路径成功重试没有覆盖它。

## 执行位置

- GitHub Actions：Debug/Release 编译（含 R8）与 lint。
- InstaBox 在线时按需进行设备验证（免费版冷却会冻结长任务）；GPU 服务器有 KVM，可在 `~/Bai/theta-emu` 跑专用模拟器；不可用时直接使用本机构建、单测、lint、静态检查与专用可丢弃 Android 模拟器，不以其在线为前置条件。
- osaka-lab：开发、同步源码及发起 InstaBox 任务，不在 lab 运行 Gradle。

GitHub Actions **不自动触发 InstaBox**。InstaBox 不保证在线，测试按需执行，不部署常驻调度，也不将其在线状态设为每次提交的前置条件。`scripts/verify-s1.sh` 是统一验证入口；离线或源码有变化但未复测时明确标记未验证。不要把一次人工触发的通过结果当作后续提交的测试状态。InstaBox 凭据保留在部署环境，不进入仓库或 PR 日志。

## 在 InstaBox 执行

需要 JDK 25、Android SDK platform `android-37.0`、Python 3、jq、unzip，以及已启动的专用 Android 模拟器。先加载环境并进入同步后的仓库根目录，再执行：

```bash
ADB="/var/tmp/emusdk/platform-tools/adb -s emulator-5554" TEST_TIMEOUT=240 bash scripts/verify-s1.sh
```

脚本会卸载 `io.github.bytejuno.thetacore`，清空该应用的数据，再安装 debug 签名的 R8 Release 宿主。**不要对日常使用的设备执行。**

通过远程 exec API 调用时，应在 InstaBox 后台启动任务，将标准输出与退出码写入本次运行独有的文件；API 请求超时不能视为构建失败或成功，也不能据此盲目重复启动。

## 验证内容

1. Debug/Release 编译、lint、`testDebugUnitTest`。
2. Hello Debug/Release APK 清单和入口类检查。
3. Hello 与 Probe 的共享库重复打包、类型与成员链接预检。
4. 模拟器安装 R8 宿主，在宿主内加载 Hello 和 Probe。
5. Probe 共享库检查、按钮、对话框、菜单交互。
6. 强制结束并重启宿主，从磁盘恢复 Probe。
7. 禁用 Probe，验证扩展移除、`onDisable → cleanup:second → cleanup:first`，重启后仍禁用。
8. 新进程启用插件；同进程关闭后重新启用只保存意愿，重启前不创建第二个实例。
9. 恢复模式冷启动跳过已启用插件，普通冷启动重新加载。
10. 卸载已加载插件，重启后完成删除。

注册表有 8 个 JUnit 测试，覆盖重复 ID、关闭后拒绝登记、逆序清理、异常隔离、幂等撤销以及登记/关闭和撤销/关闭竞争。另有 11 个生命周期测试，覆盖状态持久化、初始化中断标记、损坏状态、进程内加载门控、不跟随符号链接的删除、版本拒绝与安装失败清理、关闭顺序和非协作回调超时。

链接检查是预检而非完整的 JVM/DEX 链接证明：当前沿继承链走出已解析类时会保守放行，也不完整检查访问权限和调用种类。必须结合实际设备执行，不能把 `0 missing` 等同于所有插件 API 兼容。

## 证据与结果

保存完整运行日志、最终退出码及本次源码提交 SHA。JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`；设备测试失败时在 `build/emulator-test/` 保存截图和 logcat。每次修改源码后需要重新验证，不能复用旧构建的成功标记。

## 2026-11-06 生命周期增量验证记录

以下保留当时的执行记录，未在本次重新核验日志或重跑。记录只覆盖所列源码指纹和场景，不能外推为基线 `406d66734d19c587dc5f6265fefb02720c5682b0` 全部通过，更不能作为签名补丁验证证据。

- 执行位置：InstaBox，经 osaka-lab 手动触发；没有部署自动调度。
- 基线提交：`3f5eea6` 加本轮未提交改动，不是该提交单独的验证结果。
- 54 个非文档源码文件两端 SHA-256 一致；排序后的源码指纹 JSON 的 SHA-256：`ea8dae13c4983c65077b3c4959749c98d37ec2532d2457391524f5dd2fb00768`。
- 日志：InstaBox `/data/work/theta-s1-20261106-170519.log`，对应 `.exit` 为 `0`。
- Debug/Release/R8 构建、lint：通过，Gradle 用时 1 分 59 秒。
- 本次实际执行 JUnit：生命周期 11 个、注册表 8 个，共 19 个，0 失败、0 错误、0 跳过。
- 链接预检：Hello 54、Probe 9017 个引用，均为 0 bundled、0 missing。
- Probe 18/18，通过按钮、弹窗、菜单、重启恢复、禁用登记撤销、关闭回调顺序、禁用持久化、进程内禁止二次加载、恢复模式和卸载后重启删除，设备流程用时 393 秒。
- 尚未端到端验证：真实升级包切换、损坏包恢复和非协作插件初始化；相应单元测试不能替代设备验证。

## 生命周期增量

已批准原生 dex 采用全信任、同进程执行；SDK 是协作约定，不提供隔离或强停。持久化启用意愿与运行实例分开；宿主串行处理状态变更。禁用先保存状态，再关闭登记、撤销页面等宿主注册、取消作用域，并在独立任务中调用 `onDisable` 和逆序 cleanup。当前原型记录的关闭总等待上限为 5 秒，初始化等待上限为 10 秒；这些是等待上限，不是回调必定完成或原生代码已经停止的保证，也不代替执行资源预算验证。

同一 ID 在当前进程尝试加载过后（包括失败或超时），重启前不再创建第二个实例。重新启用只保存下次冷启动意愿；升级写入新的版本路径，保留旧运行实例，下次冷启动使用新包；同版本和降级拒绝。卸载已加载插件先保存删除标记（tombstone），撤销注册、取消作用域，下一次冷启动在加载前删除包；数据默认保留，用户明确勾选才删除。未加载插件可立即删除，但已尝试 ID 的进程内门控不解除。不承诺热卸载、native 库卸载或类加载器 GC；GC 观察不作为停用成功或再次实例化的条件。

长按桌面图标选择 **Plugin recovery** 可请求恢复启动。必须先在 Android 应用设置中强行停止应用，确保是冷启动；已运行进程不会因此回收现有代码。开发验证也可使用：

```bash
adb shell am start -W -n io.github.bytejuno.thetacore/.MainActivity --ez skip_plugins true
```

恢复模式本进程不自动加载插件。上次初始化标记未完成时，下次启动保守禁用该插件并说明原因未知；普通后台进程被系统回收不会因此直接归罪插件。

## 原始验收范围与已批准变更

DESIGN.md 第 11 节保留 A–K 原始验收表和全部补充断言。旧 B 要求“不重启完整跑通安装 → 使用 → 卸载 → 装新版本”，旧 C 要求“卸载后旧类加载器能被回收”，旧第 5/6 节允许普通 dex 原地启用新版本；这些与已批准的进程内单次尝试、重启激活决策冲突，现明确取代，不记作通过。

现行 B/C 检查持久化意愿、登记撤销、作用域取消、有界等待、重启前不二次实例化、新路径暂存更新和冷启动激活、卸载 tombstone 与明确的数据选择。GC 仅供诊断，不是验收门槛。F 的快照/回滚要求仍保留，并遵守冷启动恢复和同进程不二次实例化；其余验收项没有因生命周期决策而删减，也未新增里程碑或 API。

## 现行 S1 逐项证据与限定缺口

下表区分历史局部证据与尚缺内容，不把局部通过、要求变更或仅有实现等同于整项完成。历史完整矩阵见 S1_ACCEPTANCE.md；当前增量及精确运行标识见 [S1_RUNTIME_INCREMENT.md](S1_RUNTIME_INCREMENT.md)。candidate7、candidate9 与历史提交的证据分别保留，不相互追认。

| 原验收项 | 保留的门槛与证据缺口 |
|---|---|
| A/H | candidate9 配合诊断 Probe 已记录实际 HTTPS 请求/最终 URL、204、TLSv1.3，以及其后 18/18 UI 结果，前后宿主 hash 一致；该调用链已验证，不代表所有平台类和插件 API 的完整兼容性。历史 R8/Compose 与共享库预检记录保留，`0 missing` 仍仅为预检。 |
| B/C（修订后） | candidate9 最小矩阵已独立核实 V1→V2 冷切换：同 PID 更新后仍运行 V1，明确冷启动后运行 V2，计数保持 3；加载后卸载公开 pending、冷启动卡片/页面消失、保留数据重装仍为 3。计数证据为 XML 0107，0108 仅列表顶屏。candidate9 已补验普通 fixture 明确勾选删除数据，删除前计数5、冷启动重装后为0；不外推为整机或所有secrets擦除。历史 `c06c789` 记录独立保留；私有 tombstone 文件及设备写入/删除中断未直接观察。不再要求 GC/热卸载通过。 |
| B/I | candidate9 的 V1/V2 apksigner 完整指纹与三次导入 UI 一致；截断 APK、畸形 ZIP 均明确拒绝，各自冷启动后完整列表无新激活。另已复核可读ZIP容器/各entry CRC正常但签名失败的APK拒绝，以及可读data ZIP内单entry CRC错误的拒绝；冷启完整列表无新激活。prefix-provider已验证返回部分数据后EIO、冷启无激活及合法正控恢复，但不是宿主目的文件fsync或publication中断证明。独立storage-fixture已通过4项JVM测试，且 Android expectedSHA staging 拒绝与五切点×enabled/disabled的10次进程死亡恢复已限定接受（storage20索引及上文专项）；它是独立 DATA production-store 集成，不是 candidate9 主界面/CODE/物理断电验证，持久化状态为源码核对的跨进程fixture断言而非独立底层文件快照。`0f5a779` 的 data-zip 设备用例另验证拒绝代码载荷、摘要确认、更新、启停与卸载归属；历史 `c06c789` 签名矩阵及 revision-5 数据 ZIP/双向类型归属记录独立保留，不将本轮同signer结果称为签名轮换验收。 |
| B/D | candidate9 的 RegisterDisable/ RegisterFail 专项已验证有界竞争中的逐 identity 结果、已接纳 cleanup 各执行一次及关闭后拒绝。RegisterFail 新 focused run 补齐完整 owner 区段两次无残留页面采样；旧屏外快照仍不算通过。没有声称覆盖所有内部锁交错或 GC。 |
| D | candidate9 已验证真实 Compose 正文崩溃、旧 PID 消失、新进程隔离、明确启用后只读恢复非 seed prefs/secrets。candidate9 新补受管scope运行期、首启故障：宿主同PID响应、完整owner区段撤页，冷启动保持禁用；首启冷态具体为 Previous initialization interrupted; disabled。升级首启scope故障冷回滚V1/0已复核。candidate7工具专项独立保留；candidate9已补initThrow/initBlock故障撤页与冷态禁用，以及22.621秒内三个FAILED/actualExited调用、随后第四次准入隔离。进度1–5→SUCCEEDED/actualExited及同call取消→CANCELLED/actualExited已复核；约5秒进度测试不证明空闲超时续期。手动恢复入口不等于自动崩溃循环验收。 |
| D/E | candidate7 有 READY、超时及迟到 ready 不复活专项。candidate9 同实例监听在事件间静默超过 60 秒、ready 后超过 600 秒仍收到事件；停用后 unregister/finally 及外部广播负控已验证。监听页面隐藏后 host STOPPED/actualExited 未观察，不据此推断执行槽释放。 |
| E | candidate9 已验证每插件 2 槽、全进程 8 槽饱和拒绝及单槽实际释放后复用；另有同一 callId 的 TIMED_OUT 保持、actualExited false→true 直接 UI 配对。独立导入配额也已复核：两次部分读取在UI取消后仍阻塞，第三次busy拒绝且捕获区间无新增来源打开；provider Reset/EIO/FD_RELEASE后，同一观测PID成功接纳完整导入，续跑读取既有marker并正常卸载。后续单次成功仅证明至少恢复一个可用槽，不声称精确释放时刻或两个槽全部恢复；原标题字符匹配失败仍保留FAILED。没有覆盖混合 entry/tool 或多个 manager 的设备组合，不把这些披露自动扩成新门槛。历史非协作初始化/清理证据保留。screen 资源租约、跨会话调用和外部未知状态账本仍属 S2（DESIGN.md 第 13 节第 7 条）。 |
| F | 快照/恢复与 code-only 选项已有实现；candidate7 已验证 prefs/secrets 默认取消、快照手动回滚、首次启动失败回滚和明确 code-only 保留当前值。历史 `c06c789` 卡死/中断恢复记录独立保留。candidate9 已独立复核手动冷回滚（V2计数5→V1快照旧值4）、首启失败后冷恢复V1/4，以及首次成功运行后观察期起点跨页面重开和冷启不变。损坏数值99没有独立实测，仅有fixture的 data:damaged 标记；预激活 not-started 提示未暴露。两轮FAILED及跨轮记录间隙保留，无公共update token，不能声称连续监控。candidate9 已补首启阻塞后冷回滚V1/4，以及初始化中force-stop后的冷回滚V1/4；后者不是磁盘断电。24小时精确边界、过期动作拒绝及已接受回滚过期后继续恢复已在最新生产helper JVM测试通过，不声称真实等待24小时的设备验收。已激活包下次启动前损坏走拒绝包路径，不自动回滚。 |
| G | 最新隔离干净成对构建及证据复核已完成：production 13,921,206 bytes，comparison 2,473,682 bytes，差11,447,524 bytes；冻结输入和归一化merged R8配置仅差精确七条keep。对照不是兼容生产替代方案。实验production与candidate9非相同二进制，单方法无用Unit spill差异有静态等价分析，设备结果不转移。Actions 实测（run 42458056673，`93142b3`）：production 13,937,590 bytes、对照 2,473,682 bytes，差 11,463,908 bytes；真机帧测量仍缺，按原G门槛延期。 |
| J | 真机 Hook 远程文件加载并生效、`requestScope` 作用域申请仍未验证。 |
| K | 真机目标 App 崩溃循环跳过、远程偏好全局开关传播、目标进程重启及框架外救援仍未验证；Theta 手动恢复入口不替代 Hook 救援链路。 |

## 其他原型限制与非承诺

- 复杂依赖管理原本不在第一版范围，不是本轮新增 S1 门槛。热卸载和强制 GC 已由批准决策排除，不再作为“以后必须补齐”的承诺。
- 非协作代码的真正终止，以及任意后台线程崩溃的可靠归因不保证；宿主受管协程与 Compose 冷启动恢复门槛仍保留。
- 等待已被手动撤销但仍在运行的 cleanup；当前仅保证同一登记不重复调用。
- 损坏包会报告并跳过，不会自动修复；初始化失败后不在同进程尝试旧版本。`c06c789` 的更新首启失败冷启动回滚已有上文设备证据，完整验收仍未完成。
- 受管 scope 分开首启仲裁与 UI 发布：onEnable 返回后先在短内存锁内仲裁；此前的子任务故障不写更新成功标记，保留首启回滚语义。仲裁成功后的故障按运行期故障禁用，不自动回滚数据；持久化失败时没有任何成功记录落盘，并发故障仍按首启处理；只有 updates.started 和 initializing=false 均落盘且第二次故障检查通过，才发布 active/Running。IO 期间不持 boundary 锁。取消不计故障，重复或关闭后故障不重新清理。
- 故障诊断只取异常类名，不调用插件可覆盖的 toString/message/cause/stackTrace；宿主自己的 `PluginPackageException` 是 final 且对插件不可见，保留原文，否则超时、签名和 API 级别等失败原因会只剩类名。持久化在诊断之前，关闭在 finally 中。回滚恢复保留 RECOVERED 回执，先恢复启用意愿再确认删除回执；状态写失败留待冷启动重试，不再次搬移数据。成功删除或已验证安装才解除 recoveryBlocked，进程 attempted 门控不变。已接受回滚不再读取可选 UI 资格，直接 deferred/禁用入口/关闭。
- 受管 scope 的单元测试覆盖：共享 production scheduler 的真实 mutex 竞争与 worker 故障观察、首启仲裁/持久化/发布次序与并发故障、恶意 Throwable 诊断与一次清理、恢复回执切点/状态写失败/普通回滚、删除后同进程重装、回滚接受后刷新故障隔离。它们是 production helper/存储级单测，不是完整 PluginManager、Android Main/Compose 页面或设备端集成；设备场景已在 `c06c789` 全量回归通过，真实磁盘断电仍未验证。没有引入旧 snapshot-foundation。这些测试不覆盖 Compose、任意线程、JNI 崩溃或独立有界执行资源。
- F 相邻修复：损坏更新记录仅阻止对应 ID，跳过其 UI 再读取且保留卸载；满 24 小时后拒绝新请求，即使 UI 缓存仍为可回滚。安全模式保留快照，已接受的请求仍执行，不改变默认时长。
- 超时初始化若一直不返回，其关闭 worker 也可能持续等待；登记已关闭，但不能保证 `onDisable` 和后续资源清理执行完成。
- 安装确认弹窗的暂存文件与配置变更恢复仍是原型级处理。
