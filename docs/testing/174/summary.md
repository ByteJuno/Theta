# Issue 174 validation

Implementation SHA: `fbc80d0117e901e1f01f9d6f7694346888bd7816`
Tested Git tree: `b7ce5c44309e1f181db72fecb91aa09dcf71d64f`
PR: #183; branch: `auto/174-root-bridge`.

Root supervisor receipts now live in the app-private no-backup directory. Failed ready, launch or group receipt writes stop the command before execution. Missing/invalid/unreadable receipts never prove exit or no effects; only a valid clean or explicit not-started receipt permits release after the client and output readers exit. Unknown execution remains conservative even after other receipt loss.

Validation:
- Full `python scripts/preflight.py` passed with `--configuration-cache --configuration-cache-problems=fail`; 215.0 seconds. App: 834 tests, 0 failures/errors, 6 skips (5 Linux-only supervisor tests and 1 existing platform skip). Agent-core: 663 tests, 0 failures/errors, 1 skip, restored FROM-CACHE on unchanged code. Lint, UI-resource and storage-fixture checks passed.
- Existing `/opt/homelab-report/theta-gpu-test` entry: 21 Root tests, 0 failures, 58.0 seconds, including real POSIX directory/write failures and a live command group after client death/receipt deletion. Ledger test checks UNKNOWN/UNCONFIRMED until explicit simulated exit evidence.
- Final fetch: both remote PR branch and origin/main already included. Source hashes unchanged during both validations; exact tested sources committed.
- Initial failures retained locally: first test compile rejected Redirect.DISCARD; next baseline had 4/18 failures on unchanged production code. No assertions or timeouts weakened. Reviewer diagnosis retained in reviewer-verdict.txt.

APK SHA256: not generated.

未生成 APK；未操作手机或模拟器，视觉未验证（本轮无 UI 改动）。真实 Android su、Magisk、KernelSU、APatch、API33 运行、R8 和硬件未测。GPU 为 Linux JVM/POSIX 测试，适配系统路径及 UID 检查，不等于真实 Root 验收。无法取得有效退出或明确未启动回执时保留 worker/资源租约和 UNKNOWN；可能需要后续诊断，不能据客户端死亡宣称子进程已退出。故意脱离进程组的 Root 代码仍不保证终止。最终发布 head 的独立审核、CI 及正式模拟器回归待发布器与审核端执行。

Local evidence: `E:\Desktop\AI研究\theta-auto-loop\event-runs\8a9ec8e0-dc82-4592-8b4c-c878923a6922`. Raw logs/XML/tar and interim evidence are not committed.

## 第二轮独立审核

- 审核输入：`f1866cc0151f118662389aa3a62659efe8e85094`；此前退回 1 次。已核对上述实现提交及 Git tree，审核输入仅追加测试摘要，原测试结果可复用。
- GPU 临时探针复现参数膨胀：合法的 16,004 字符命令直接执行返回 0；嵌套转义后监督脚本为 210,601 字节，启动报 `E2BIG`。修复提交 `781081701193479f0fa90140c8082293b6a8a04e` 将命令作为子 shell 的位置参数传递，避免重复转义。
- 修复后由既定 GPU 入口运行 `:app:testDebugUnitTest --tests '*RootSupervisorTest' --tests '*RootBridgeTest'`：14 项、0 失败，包含新增长引号命令的完整输出与实际退出断言；原断言和超时未改。
- 其他探针保留真实结果：模拟 su 在运行监督脚本前拒绝时，实例保持 `UNKNOWN/UNCONFIRMED`，另一会话被资源隔离阻止。这符合上一轮无正面凭据不得释放的要求，未据此要求放宽。宿主启动前已死亡的自然调度探针 20 次均未执行命令，其“应能复现竞态”断言失败；只延迟取消监听 500 毫秒的调度探针观察到命令执行及清理完成。PROTOCOL 3.7 不承诺宿主死亡自动终止外部操作，此观察未作为独立缺陷或合并阻断。探针均未提交，原失败不改记为通过。
- 本轮未新增依赖或界面文字；未生成 APK，未运行模拟器、真机或视觉验证。最终提交的 CI 和模拟器回归仍由发布脚本执行。
