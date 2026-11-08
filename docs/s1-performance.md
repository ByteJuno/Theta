# S1 G：keep 规则体积与帧耗时证据

范围：DESIGN.md §11 G 的原文是“量化 keep 规则对 APK 体积和界面帧耗时的影响”，测法为“CI + 真机”。§8 指出 keep 共享库 API 可能增大体积并影响 Compose。文档没有规定 APK 增量、p95、掉帧率或样本数量的通过阈值。本工具不补造这些阈值，也不把有数字等同于 S1 G PASS。

本新增工具没有构建、安装或采集任何实际候选包；自动化测试只使用合成 ZIP 和合成 frame rows。不得用它们作为设备性能证据。

## 1. 固定比较对象，不改冻结候选目录

现有 `app/proguard-rules.pro` 保留 SDK、Kotlin、coroutines、serialization、Compose、collection、lifecycle、savedstate 的 public API，规则包含 public/protected 成员。APK 的 debug/release 对比并不能隔离这些规则的代价。

建议在单独 CI job 的两个隔离 checkout 中生成成对 release APK：

- candidate：冻结源码修订、现行 keep 规则、相同 R8 和资源压缩配置。
- baseline：同一修订、依赖解析、JDK/AGP/Kotlin/SDK、签名身份与构建配置；只在这个隔离 checkout 的 proguard 配置中去掉明确列出的共享库 keep 规则。保留 SDK keep，记录原始/修改规则文件、精确 diff、最终 merged R8 configuration 的 SHA-256。不要触碰正在设备验收的 source/app 或替换冻结候选 APK。
- 保存 CI run URL、源码修订、依赖锁/解析报告哈希、工具链版本、完整命令、签名证书摘要与 APK SHA-256。同一个对比 CI run 可包含两个 jobs。旧 APK 若来源不明，只能报告文件大小差，不能归因于 keep。
- 去掉 keep 的 baseline 可能不能加载外部插件。这是实验限制，不是生产候选。帧实验必须跑两者都能正常执行的相同宿主 Compose 场景；如果场景依赖已裁剪 API，记录不兼容，不能拿不同页面替代。插件页面兼容性另属 A/H。
- 文件字节数是签名后 APK 下载体积，不是安装后占用。脚本另报 DEX 压缩/解压字节数；签名、ZIP 元数据和资源变化仍需人工确认。禁止把 debug/release 全部差异称为 keep cost。

工具只消费指定 APK，不自动构建或修改规则。

## 2. 真机采集（操作者显式执行）

安装和启动由既有验收流程负责。本脚本不安装、不 force-stop、不启动 Activity、不注入点击；仅采设备属性、当前已安装 APK 的字节哈希、进程号、display/thermal/battery 原始输出，以及 gfxinfo reset/framestats。

先确认实体 Android 设备，记录操作员、设备型号、固件、刷新率、亮度、温控/电量、插电状态、后台负载、页面/数据集和交互脚本。预热后执行固定场景，AB/BA 交替多轮，保留每轮原始数据，不只挑好结果。两种 APK 使用同一台设备和同一场景；进程重启应发生在采集窗口之前。

```bash
python -I -B /ABS/source/scripts/s1-performance.py collect \
  --serial EXPLICIT_PHYSICAL_SERIAL --package io.github.bytejuno.thetacore \
  --apk /ABS/artifacts/candidate.apk --sha256 EXACT_LOWERCASE_SHA256 \
  --scenario host-plugin-list-scroll-v1 --run-id candidate-01 \
  --seconds 20 --physical-device --output /ABS/evidence/candidate-01
```

所有 adb 命令都带 `-s`，不使用“第一个连接设备”。`--adb` 可指定可执行文件。输出目录必须是新目录。出现 `Frame window open` 后执行事先约定的场景。20 秒仅是默认采样窗口，不是验收阈值；gfxinfo 环形缓冲可能只保留最近一部分帧，输出 frame_count 是实际保留样本，不声称覆盖全窗口。

采集前后均通过 `pm path` 和 `exec-out cat` 对已安装 base APK 求 SHA-256，必须与提供文件一致；不支持 split APK，设备不允许读取安装包时明确 INVALID，不绕过。进程必须存在且窗口前后 PID 相同。设备原始资料可能含设备标识，发布前按项目证据处理规则审阅。

真机判断同时要求人工实体设备确认、序列号非 emulator、qemu 属性非启用、型号/硬件/固件无常见模拟器标记。空 qemu 属性在实体设备上正常，缺失整个属性字段则不充分。模拟器即使传 `--physical-device` 也只能产生 UNVERIFIED，不满足 G 真机门槛。硬件属性和 attestation 是来源记录，不是防伪认证。

## 3. 离线分析

每个采集目录包含 `capture.json`、`framestats.txt` 和辅助设备输出。已有外部 framestats 可离线导入，但须保留真实 SHA-256、时间、设备属性、APK 绑定和 PID/场景来源；缺证据不要填写臆造值。缺失必要绑定会 INVALID，缺少物理来源或构建归因会 UNVERIFIED。

创建如下 manifest，路径相对 manifest 目录；多轮 captures 按实际追加。示例占位值必须替换，尤其 64 位哈希必须是真实值。

```json
{
  "schema": 1,
  "only_keep_rules_differ_attested": false,
  "matched_frame_conditions_attested": false,
  "baseline": {
    "apk": "artifacts/baseline.apk",
    "sha256": "BASELINE_APK_SHA256",
    "build": {
      "source_revision": "EXACT_REVISION",
      "toolchain": "EXACT_TOOLCHAIN_OR_REPORT_HASH",
      "dependencies": "RESOLVED_DEPENDENCIES_HASH",
      "variant": "release; minify=true; shrinkResources=true",
      "signing_identity": "CERTIFICATE_SHA256",
      "ci_run": "CI_COMPARISON_RUN_URL",
      "keep_rules_sha256": "BASELINE_RULES_SHA256",
      "effective_r8_config_sha256": "BASELINE_MERGED_R8_CONFIG_SHA256"
    },
    "captures": ["baseline-01/capture.json"]
  },
  "candidate": {
    "apk": "artifacts/candidate.apk",
    "sha256": "CANDIDATE_APK_SHA256",
    "build": {
      "source_revision": "EXACT_REVISION",
      "toolchain": "EXACT_TOOLCHAIN_OR_REPORT_HASH",
      "dependencies": "RESOLVED_DEPENDENCIES_HASH",
      "variant": "release; minify=true; shrinkResources=true",
      "signing_identity": "CERTIFICATE_SHA256",
      "ci_run": "CI_COMPARISON_RUN_URL",
      "keep_rules_sha256": "CANDIDATE_RULES_SHA256",
      "effective_r8_config_sha256": "CANDIDATE_MERGED_R8_CONFIG_SHA256"
    },
    "captures": ["candidate-01/capture.json"]
  }
}
```

只有核实隔离 build diff 后才将 `only_keep_rules_differ_attested` 设为 true；核对两组 display/thermal/battery、预热与交互后才设置 `matched_frame_conditions_attested`。数值不会自动证明实验控制成立。

```bash
python -I -B /ABS/source/scripts/s1-performance.py analyze \
  --manifest /ABS/evidence/manifest.json --output /ABS/evidence/s1-performance-evidence.json
python -I -B /ABS/source/scripts/s1-performance-test.py
```

报告输出 APK 精确字节差/百分比、DEX 字节差、每轮 frame_count、mean/p50/p90/p95/max，以及两组“逐轮统计值平均”的差；不会把平均 p95 伪称合并样本 p95。帧耗时定义为 `FrameCompleted - IntendedVsync`，纳秒转毫秒，不是 CPU-only 时间或端到端输入延迟。`Flags != 0` 排除并计数；无效/截断/unfinished 行明确报告，不能静默得 PASS。只在存在有效 `FrameDeadline` 时报告 deadline miss，不硬编码 16.67ms 或假设 60Hz。

状态：

- `INVALID`：APK/hash/帧格式/进程绑定损坏或缺失，退出码 2；不能用于性能结论。
- `UNVERIFIED`：可读取测量，但缺失构建控制、真机或可比场景证据。退出码 0 仅指分析执行完成。
- `MEASURED`：输入通过本工具的完整性与来源检查，仍不是 PASS。`acceptance_verdict` 始终为 `UNVERIFIED`，由验收负责人审阅数值、波动、来源与适用性之后另行记录 G 结论。没有隐含通过阈值。

既有 build/lint/unit 通过、模拟器 UI 顺滑、合成 frame 单测通过，都不替代 CI 成对 keep-cost 实验与实体设备帧证据。
