# Issue 162 / PR 187 RETURN validation

Implementation commit: `4df313345315b797a1f2d9a43fef4a2d401a7f5d`
Tested tree: `fcb854423d4dffb9c29404a3c773611f50645a16`
Branch: `auto/162-settings-center`
Reviewer commit included: `680d2210c6dd3a1921669baef74b958e2222365c`
Final fetched main: `7df1b70981cc555e5ea529db98a42f4f7f9e701f`

本轮修复 S1 验收脚本的列表搜索：使用实际滚动区域内最多 400px / 800ms 的慢速滑动；按观察到的端点换向，保留 180 秒默认超时及每向 30 步上限。缺少有效滚动区域不盲滑；缺少目标仍失败。absence 检查从顶部逐屏扫到底部，不能把屏外控件当作不存在。失败时保存最近 40 次搜索观察。未修改 app 实现、签名校验、保护文件或原业务断言。

退回证据：OWNER RETURN `IC_kwDOVkrS_s8AAAABr0eoWg`，专用模拟器 run `20261128-064113`，源码 `680d2210c6dd3a1921669baef74b958e2222365c`，`VERIFY_S1_EXIT=1 seconds=603`，`SIGNATURES_SKIPPED smoke`。失败文本为列表底部 Hook 区，随后截图为列表顶部，crash.txt 为空。APK 身份未由本轮独立核验；旧截图只作诊断。原始证据保存在本轮输出目录。

验证：
- Python `scripts/acceptance-observation-test.py`：49 项通过；新增 5 项，覆盖中间控件、原超时、absence 中间页、长列表与无效滚动区域。旧实现的缺陷回归失败已保留；首个测试模型位置调整和 sleep 越界修复的中间失败也保留。滑动模型中的 3 倍增益为压力条件，不是实测设备参数。
- `python scripts/preflight.py`：passed，190.1 秒；严格 configuration cache，资源、lint、storage fixture 来源/编译及 app/core 单测通过。
- App 877 tests，0 failures/errors，7 skipped；agent-core 688 tests，0 failures/errors，1 skipped，Gradle FROM-CACHE（没有声称本轮重新执行）。
- `git diff --check` 通过，源码验证前后哈希及提交树一致。审核员修复和最新 main 均已包含。

限制：未生成新 APK（APK SHA 未生成），未操作手机或模拟器，当前构建视觉未验证。49 项 Python 回归是确定性的搜索/滑动逻辑测试，不代表设备验收；真实设备上该次超时是否完全消除仍须审核端使用发布后的最终 head 运行 S1。原始 680d2210 回归失败、截图和日志仅作诊断，不转移为本提交通过证据。当前提交 CI、正式模拟器回归、API33 运行、R8 与签名验收未在本轮执行；原设置页的中英文、横竖屏、大字体、明暗主题与交互仍需审核端验收。

证据目录：`E:\Desktop\AI研究\theta-auto-loop\event-runs\6784498e-a4a5-4d55-b5b2-62b526725809`。提交只包含两个 Python 文件；docs/testing/162/summary.md 由发布器统一登记。
