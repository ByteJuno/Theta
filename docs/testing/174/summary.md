# Issue 174 validation

Implementation commit: `eee5c67d84aa333481e70609fd039ae9dc6f1fd8`
Tested tree: `efb9a42b4892d8bbcd1f41483ea073cb67a33e80`
Branch: `auto/174-root-bridge`
APK: not generated; APK SHA256: not applicable.

- Full preflight passed in 150.41s with `--configuration-cache --configuration-cache-problems=fail`: storage fixture source/compile, UI resources (610 paired keys), lint and unit-test tasks.
- App: 827 tests, 0 failures/errors, 4 skipped (3 Linux supervisor cases plus 1 existing platform case). Unchanged agent-core: 663 tests, 0 failures/errors, 1 skipped; final task UP-TO-DATE, initial preflight FROM-CACHE.
- GPU Linux entry: 14 tests, 0 failures, 62.61s. Covers real process exit/status/stdout/stderr, process-group timeout cleanup, output capture bounds, cancellation retention, cold-start zero calls, approval modes, deny/unattended rules, root permanent-grant switch, SQLite BlobStore and cross-session rejection.
- Final local and GPU source manifests match this implementation. No original test assertion was removed or timeout widened.
- Failures retained: targeted-second compiled against `InvocationRecord.effect` instead of `result.effect` and failed before tests; corrected. First GPU run hung because shell builtin kill rejected its arguments; process ownership was verified before stopping only this run's worker/group. Original logs and receipts remain outside Git. System kill fixed the defect and the 14-test retry passed. The initial full preflight belongs to the earlier source and is not final evidence.
- 未生成 APK；未操作手机或模拟器，视觉未验证且本轮无布局改动。未验证真实 Android su、Magisk、KernelSU、APatch、API33 运行、R8 或硬件。Linux 测试只把系统可执行路径及 UID 检查适配为无特权进程，不能充当真实 Root 验收。命令输出捕获每路最多 1 MiB，超出明确截断；捕获部分超过 32 KiB 时走现有会话 BlobStore。故意 setsid/守护进程化逃逸的任意 Root 代码不保证终止；授权/退出证据缺失时保留 worker 和资源租约，外层按未知副作用暂停，而不声称已退出。独立审核、最终 head CI 及正式模拟器回归仍待发布后执行。

Publication resume verification (2026-11-27T17:18:22.389846+00:00): fetched origin/main `40330df3a2758b752f90ac495e1aca69f8483e66`, already included in implementation `eee5c67d84aa333481e70609fd039ae9dc6f1fd8`. Current HEAD and tree remain unchanged. All 558 local preflight source hashes match; the GPU archive matches the same sources, with gradlew using the exact committed LF blob and executable mode. Reused the original passing evidence; no tests, implementation edits or commits were repeated in this resume. Repository has one open PR (#172), no existing branch/PR for issue 174, and capacity for this handoff. Independent PR172 RETURN remains pending.
