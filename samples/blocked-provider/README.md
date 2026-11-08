# Disposable S1 blocked document source

独立安装的 Android 测试 App，**不是导入 Theta 的代码插件**。只新增本模块和根 settings include；不依赖/修改宿主或 SDK。源代码全新编写。无网络、凭据、root、Service、定时后台任务或共享存储写入。

## 构建入口（本次未执行）

在仓库根目录由构建/设备操作者执行：

```sh
./gradlew :samples:blocked-provider:testDebugUnitTest :samples:blocked-provider:lintDebug :samples:blocked-provider:assembleDebug
```

APK：`samples/blocked-provider/build/outputs/apk/debug/blocked-provider-debug.apk`。
包名 `io.github.bytejuno.thetacore.sample.blockedprovider`，Activity `.FixtureActivity`。
采用仓库 AGP 内置 Kotlin、SDK 版本、Java 17 约定；release 同其他样例使用开发签名。单元测试覆盖生成 ZIP 的条目/CRC/身份和 gate 的阻塞、释放、取消、超时；不替代 Android FUSE/SAF 测试，也未在本次运行。

## DocumentsUI 中的文件

启动桌面 **Theta Blocked Provider** 一次。宿主 **Import data ZIP** → 系统文件选择器侧栏 **Theta blocked sources**（必要时展开侧栏，而不是只看 Downloads/Recent）：

| 文件 | 第一次真实 onRead | 独立数据插件 ID 后缀 |
|---|---|---|
| `control-data.zip` | 立即返回 | `control` |
| `delayed-data.zip` | 15 秒后返回，或提前点 Release | `delayed` |
| `blocked-data.zip` | 等待本地 Release；打开起 120 秒到期则 EIO，不自动成功 | `blocked` |
| `prefix-control-data.zip` | 立即返回正数前缀，继续读取完整大 ZIP | `prefixcopy` |
| `prefix-interrupted-data.zip` | 立即返回正数前缀；下一次非空 read 等本地 Reset 或 120 秒到期，返回 EIO；Release 无效 | `prefixcopy`（与同字节 control 共用） |

完整 ID 前缀：`io.github.bytejuno.thetacore.fixture.source.`。版本固定 1。原三个文件仍各含根 `theta-plugin.json` 和 `skills/source-marker/SKILL.md`，小于 4 KiB、STORED ZIP、无代码或外部地址。页面内容分别显示 `SOURCE_CONTROL_V1`、`SOURCE_DELAYED_V1`、`SOURCE_BLOCKED_V1`。

新增 prefix 对共用**同一 ByteArray、同一合法 ZIP、同一独立测试 ID `io.github.bytejuno.thetacore.fixture.source.prefixcopy`**，不与原三个包共用 ID。包含 manifest、`SOURCE_PREFIX_COPY_V1` 标记以及 `skills/source-padding/SKILL.md` 的 512 KiB 固定种子、可打印 ASCII padding。全部 STORED，条目均小于宿主 1 MiB 上限，总 ZIP 大于 512 KiB，超过当前宿主 `PluginImportSource` 的 `DEFAULT_BUFFER_SIZE`（8192），也大于通常 proxy/FUSE callback 缓冲；不靠压缩后的体积碰巧超过缓冲。没有篡改 CRC、摘要或中央目录。首个正数 callback 至多返回 `ZIP.size - 1`，所以不可能一次返回完整 ZIP；正常顺序复制从 offset=0 返回真实 ZIP 前缀。

重复安装需先通过宿主卸载该测试数据包，避免“版本未增加”掩盖结果。prefix 正向和中断轮之间必须卸载 prefixcopy；只卸载四个测试 ID，不清宿主其他数据。

所有查询立即返回元数据；无缩略图、搜索、自动预览、写入能力。每次 open 都创建新 gate；文件 ID 和字节不随 Reset 改变。底层是 StorageManager.openProxyFileDescriptor，`onRead` 在专用 HandlerThread 等待，而非仅在管道写端等待。`READ_ENTER` 是真实读取已到达提供方的标记。

## 本地控制页与证据

- **Release current reads**（accessibility `fixture_release`）：只释放当前打开的原有 blocked/delayed 会话，后续打开仍会阻塞。对 prefix-interrupted 仅记录 `RELEASE_IGNORED_PREFIX_ABORT`，不解除阻塞；其成功对照是单独的 prefix-control 文件。
- **Reset / abort current reads**（`fixture_reset`）：当前会话永久转为 EIO，唤醒等待；不删除日志、不伪造 FD 关闭、不提前归还会话名额。后续打开是新会话。
- 日志视图 `fixture_events` 每 500ms 刷新，最多保留 160 行；仅前台刷新。旋转控制页不重置会话。日志同步写入 logcat tag `ThetaBlockedSource`。
- 每行含 `seq`、单调时间 `t`、fixture PID、`sid`、文件 `doc`、open 调用 UID。以同一 PID+sid 关联，不能把 DocumentsUI 的预读当成宿主读取；记录宿主 UID，出现多次 open 时逐一确认。第三方文件管理器可能预读，不作为本用例路径。

可由唯一设备操作者持续采集（替换 SERIAL；本次未执行）：

```sh
adb -s SERIAL logcat -v threadtime ThetaBlockedSource:I '*:S'
```

关键标记：

- `OPEN_BEGIN` → `OPEN_RETURN`：代理 FD 已建立；**不证明宿主读到内容**。
- `READ_ENTER mode=BLOCK` 或 `mode=DELAY`：首个 read 已进入 gate。
- `UI_RELEASE` / `RELEASE_REQUEST` → `READ_GATE_EXIT outcome=RELEASED` → `READ_RETURN`：提供方允许返回字节；**不证明宿主接受或发布**。
- `READ_GATE_EXIT outcome=DELAY_DONE`：15 秒延迟结束。
- `UI_RESET` / `RESET_ABORT` → `READ_ABORT reason=RESET`：reset 错误路径。
- `DEADLINE_ABORT`、`TIMEOUT`：120 秒兜底；该轮若要验证手动释放竞态，应重做，不当作手动释放通过。
- `FD_RELEASE`：框架终于分派代理描述符 release；此时才退还名额并停止对应线程。
- `OPEN_SIGNAL_CANCEL`：仅当 Android 实际传递 open 阶段 CancellationSignal 时产生，不是 Cancel reading 的必需标记。
- `BUSY_REJECT`：提供方 3 个名额占满，fail-fast，无排队。

## 设备验收步骤（尚未执行）

预置：宿主普通模式，无未处理恢复阻塞；仅安装本样例 App。记下宿主及 fixture PID/UID，开始日志录像，确认控制页 `active=0/3`，宿主无四个测试 ID。宿主列表按钮实际名称是 **Import data ZIP**、**Cancel reading**。切换 App 使用最近任务，不 force-stop；需要保持宿主进程，才能证明是取消而不是进程退出。

### A. 有效包正向控制

1. 从宿主导入 `control-data.zip`，观察 `READ_RETURN` 和 `FD_RELEASE`。
2. 确认页应为 **Source fixture control**，无签名数据提示，不走 APK 签名失败分支；显式确认安装，打开只读数据页检查 `SOURCE_CONTROL_V1`。
3. 在宿主卸载此测试包。若正向路径失败，后面的“不激活”不能判通过。

### B. 真实阻塞 read → UI 取消 → 旧读取迟到

1. 导入 `blocked-data.zip`。必须先捕获宿主对应 `READ_ENTER mode=BLOCK`，在 Release 前不应出现该 sid 的 `READ_GATE_EXIT`。可切到 fixture 页查看，再返回宿主；离开前台本身不等于取消。
2. 点击宿主 **Cancel reading**，记录点击到按钮重新可用的时间；在释放前，仍能滚动/操作宿主列表、打开已存在的数据页或切换另一测试插件的启停，记录实际响应，不能只凭动画判断。
3. 从最近任务进入 fixture，点 **Release current reads**；记录相同 sid 的 `RELEASE_REQUEST`、`READ_GATE_EXIT` 和最终 `FD_RELEASE`，返回宿主。
4. 旧 **Source fixture blocked** 确认框不得重现，列表不得增加 blocked ID，现有插件状态不应改变。保持宿主 PID 不变并等待 FD_RELEASE 后继续观察；仅“当时没有崩溃”不够。能再次导入 control 说明槽位恢复；不需要确认安装它。

### C. 取消后新请求交接（强于仅无激活）

1. 按 B1 阻塞 blocked，取消后**先不要释放**。
2. 再导入 `delayed-data.zip`，等第二 sid 的 `READ_ENTER mode=DELAY`；立即点 fixture **Release current reads**（两者都释放，须在新 read 的 15 秒内）。
3. 返回宿主，唯一可见确认页必须为 **Source fixture delayed**；旧 finally 不得使新请求 UI 错乱，也不得弹 blocked 确认。仅明确确认新 delayed，检查只读页 `SOURCE_DELAYED_V1`；blocked ID 不得安装/启用。
4. 若迟于 15 秒，改为记录自动 DELAY_DONE 的顺序并重做手动交接，不把错误时序记通过。完成后卸载 delayed 测试包。

### D. Activity 旋转/销毁

1. 阻塞 blocked，已捕获 READ_ENTER 后，在**宿主前台**旋转屏幕；记录 Activity 确实重建、宿主 PID 未变。ROM 锁定旋转或未发生重建不算覆盖。
2. 新 Activity 列表应能操作，不保留旧读取确认；进入 fixture Release，返回宿主，旧请求不得出现确认/安装，记录同 sid 的终态。
3. 单独重复一次取消与旋转相邻的操作（先取消后旋转），再次检查。若系统杀死了宿主或 fixture，本轮作废，不能用重启代替取消边界。

### E. 延迟、Reset、资源边界

1. 导入 delayed，不取消、不释放，约 15 秒后出现 DELAY_DONE，正常确认内容可读取；此为延迟正向路径。
2. 导入 blocked，READ_ENTER 后点 Reset；宿主应退出读取并报告读取失败，不应进入确认。后续 control 可导入。若此前已取消，则旧错误也不得覆盖新 UI。
3. 不手动释放的一轮：120 秒出现超时/读取错误，而不是有效包确认。
4. 可连续做两次 blocked→Cancel 而不释放，观察宿主是否仍保留两个读取槽位；只有实际未退出时，第三次导入才应显示宿主 `Import sources busy; close imports or restart`。这依赖本机 FUSE 行为，若关闭已结束 read，不能伪称覆盖了槽位耗尽。fixture 自身上限是 3，避免先于宿主 2 槽位触顶。Release/Reset 后等 FD_RELEASE，再试 control。

每轮保存：宿主/fixture PID、host UID、选择文件、sid、取消/旋转/释放的时序、界面录像/截图、最终插件 ID/启用状态、日志终态。host 与 fixture 的时间戳可以关联，不把单个 provider 标记当作宿主状态证明。未达到 READ_ENTER、进程被系统回收、超时先触发、选到同名 Downloads 文件均标为无效轮。

### F. 正数 ZIP 前缀 → 源复制 EIO（新增；未执行）

1. 控制页确认 `active=0/3`，宿主不存在 prefixcopy ID。宿主 Import data ZIP → Theta blocked sources → **prefix-control-data.zip**。应见同 sid 的 `PREFIX_FIRST_RETURN offset=0 bytes=N returned=N total=T`（`0 < N < T`）、后续 `PREFIX_READ_RETURN`、`PREFIX_EOF returned=T`、最终 `FD_RELEASE`。确认页标题 **Source fixture prefix copy**；确认安装并检查 `SOURCE_PREFIX_COPY_V1`。卸载 **prefixcopy**，等待槽位归还。正向失败则中断轮不作通过。
2. 导入 **prefix-interrupted-data.zip**，此轮**不要点击宿主 Cancel reading**。从最近任务打开 fixture 控制页，确认宿主 UID 对应同一 PID+sid 的 `PREFIX_FIRST_RETURN offset=0 bytes=N returned=N total=T`，随后 `PREFIX_WAIT offset=... reads=1 returned=N total=T`。必须满足 `0 < N < T`；不能只有 OPEN_RETURN，也不能把 DocumentsUI 的非零 offset 预读计作顺序导入成功。首次读取为非零 offset 时，标记只证明该范围被返回，不证明 ZIP 前缀；重新选择并按宿主 UID 取证。
3. 可点一次 **Release current reads**，应仅见 `RELEASE_IGNORED_PREFIX_ABORT`，不能出现完整 ZIP/确认页。然后点本地 **Reset / abort current reads**：同 sid 应出现 `RESET_ABORT` → `PREFIX_GATE_EXIT outcome=RESET` → `PREFIX_READ_ABORT reason=RESET reads=1 returned=N errno=EIO` → 最终 `FD_RELEASE`。`SourceReadAborted` 在 provider 边界映射成真实 `ErrnoException(..., EIO)`，不是返回 EOF 或坏 ZIP。若 120 秒先到则为 TIMEOUT，不能计作手动 Reset 路径。
4. 返回宿主：应退出读取并报告源读取失败，无 prefixcopy 确认/安装/启用；保留已有插件状态。再次导入 prefix-control，验证读取与槽位可恢复。不依赖私有文件、反射、数据库、内部 API 或宿主状态注入。结果以可见宿主 UI/状态加提供方日志关联；单个 callback 返回只证明提供方交付，不单独证明宿主缓存已落盘。FUSE 缓存/预读可能使实际 read 返回顺序变化，必须保留真实时序，不夸大证据。
5. 单独重做 deadline 轮，不 Reset/Release；源 gate 使用创建时单调时钟剩余寿命，主线程 watchdog 也会 abort，即使 watchdog 延迟，后续读取自身检查 deadline。必须得到 TIMEOUT/EIO，而不是成功；Reset/超时**不归还名额**，直到真实 FD_RELEASE。可另外测试先 Cancel 再 Reset，但那是取消覆盖，不可替代本轮未取消的 EIO 错误路径。

新增日志每个读取会话最多 12 条正数 read 标记、1 条截断标记、1 条 EOF、1 条 gate enter/exit、1 条错误；不记录 ZIP/文本数据。Runtime 保留原 160 行 UI ring、3 会话/线程、120 秒上限。Release/Reset 点击及元数据/open 事件仍遵循原日志规则。

**范围区分**：本轮仅制造 `ContentResolver` 源复制阶段的中途 EIO；它不执行完整源文件后的 durable/atomic publication 中断，也不是 digest mismatch 测试。合法控制字节相同只用于排除包本身无效；无宿主私有状态后门，不能宣称覆盖原子发布或摘要校验。Android FUSE/SAF 行为、宿主实际临时文件字节数、安装状态仍须设备证据，单元测试不能替代。

新增单元测试：`PrefixArchiveTest` 通过 ZipFile 中央目录、条目/CRC/UTF-8、共享字节/身份检查；`PrefixReaderTest` 覆盖 8 KiB 控制复制、首读大缓冲仍不完整、短目标缓冲、非零/重复 offset、空读/EOF/非法范围、首读前 abort、后续 read 阻塞、Release 无效、Reset/close 唤醒、永久失败、超时与日志上限。close 的单元模型验证唤醒，但真实 Android onRelease 仍可能排在阻塞 read 后，不能据此宣称宿主 close 会立即终止远端 read。

交给构建操作者的新任务入口（本次禁止构建/adb，**未执行**；当前 frozen build 的旧副本不含本改动）：

```sh
./gradlew :samples:blocked-provider:testDebugUnitTest --tests 'io.github.bytejuno.thetacore.sample.blockedprovider.*'
./gradlew :samples:blocked-provider:lintDebug :samples:blocked-provider:assembleDebug
```

产物仍为 `samples/blocked-provider/build/outputs/apk/debug/blocked-provider-debug.apk`；不修改宿主 APK/build 文件。只有以后从包含本次 sources/tests 的副本构建才可验证新增文件。无需远程控制 API，装入夹具后仅通过上述系统选择器和本地 Activity 两个按钮操作。

## 边界与清理

- 宿主当前调用 `ContentResolver.openInputStream(uri)`，没有向该调用提供 CancellationSignal。其逻辑取消使用独立 close 线程，并不等于远程 onRead 收到取消。`OPEN_SIGNAL_CANCEL` 可能完全不出现，不能据此判宿主失败。
- Android proxy/FUSE 的 onRelease 与 read 共用对应 Handler，release 常在阻塞 read 返回之后才可见。`FD_RELEASE` 证明释放回调到达，不证明取消瞬间 read 停止。关闭、内核缓存/预读、OEM 行为可能改变顺序；只能据真实标记判断。
- Release 返回有效旧字节是刻意的迟到结果测试。Reset/超时则协作地退出并返回 EIO。不能据此声称宿主能强杀任意阻塞代码、JNI、open 或 close；本模块不模拟永不返回的 Binder open/任意 close 回调，APK 签名分支也不覆盖。
- 最多 3 个打开会话、3 个专用回调线程、3 个主线程 watchdog；没有等待队列。阻塞等待最长 120 秒。客户端始终不关闭 FD 时，已经空闲的线程/名额最多保留 3 个，Reset 也不会谎称已关闭；此时后续 open 明确拒绝。成功代理 FD 只有真实 onRelease 退还名额，或用户结束 fixture 进程（代理分配失败则原有 OPEN_RETIRED 退还未分配名额）。无无限增长的线程/日志内存/文件。
- UI 控制只来自当前 Activity 按钮，不接受 exported intent extras、广播或 provider.call 控制命令。Provider 使用系统 MANAGE_DOCUMENTS 权限及 SAF URI grants；只读。
- 结束：Reset，关闭宿主待导入/确认，等 active=0；卸载四个测试数据 ID，再卸载 **Theta Blocked Provider**。若客户端保持 FD 导致 active 不清零，记录后在系统 App 信息强停该一次性 fixture；这仅是清理，不是取消通过证据。

本模块只补设备夹具，不改变 S1 验收状态；真实取消/旋转矩阵仍须由独立设备操作者执行。
