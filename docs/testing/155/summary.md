# Issue 155 validation

Implementation source: `6fabaf5b39963e484a71c8d0f5e681320483d4b1`
Tested tree: `c25102637f5b8b69f450da3ecb3980f533480298`
Branch: `auto/155-anthropic-protocol`
Latest included main: `baa65b8c7bd6d8bc392d1ddc79c373d7e33d12c3` (PR183 merge verified separately).

This RETURN assessed official anthropic-java 3.14.0 (source b0349e817c4cb805a2906b019d4ac0caa66d02e6), published POMs/licenses, resolved transitive dependencies and actual public-client loopback behavior. Candidate maxRetries(0) still sent 2 HTTP requests for 503 Retry-After:0 with OkHttp 4.12.0 and 5.5.1; public streaming dropped ping/unnamed events; default thinking toParam lost unknown fields while explicitly copying additionalProperties preserved them. Cancellation is supported according to source, not experimentally certified here. Retain the existing bounded adapter; no SDK dependency added. Committed rationale links exact upstream source and defines necessary custom scope. Three permanent tests guard these native transport/replay contracts; no production behavior, assertion or timeout was weakened.

Validation on unchanged candidate files:
- New Windows boundary tests: 3 passed.
- GPU after merging main: 63 tests, 0 failed (Anthropic, transport, Root and output-surface paths), 63.8s.
- Full preflight after merging main: passed in 185.6s; strict configuration cache reused; lint, UI resources and storage fixture passed.
- App: 843 tests, 0 failures/errors, 7 Windows platform skips. Core: 688 tests, 0 failures/errors, 1 platform skip executed earlier this round; identical core sources reused UP-TO-DATE by the final preflight.
- Final static preflight and git diff checks passed. Upstream branch and latest main are ancestors. Reviewer additions retained.

Preserved evidence: all previous RETURN comments, reviewer-original-failure.xml / reviewer-original-run.log and failed permanent latch regression in event-runs/2d139b81-c130-489c-828e-b3b32fcc04db; earlier failure pointers remain in state-before.json. This round retains the candidate harness compilation failure, initial network/clone failures, missing-identity merge failure, candidate successful experiments and post-main validation under event-runs/ddc7fff9-d0a0-424d-a9b1-963a550aa727. No raw logs or experimental SDK source are committed. The writer authored no docs/testing summary; the main merge includes only its existing upstream summary.

APK: not generated; SHA256: not available.
Limitations: 未生成 APK；未操作手机或模拟器，视觉未验证。本轮没有界面改动，原有 reasoning 提示的中英文、小屏、大字体与明暗主题仍待审核端验收。真实 Anthropic API、Android API33 运行、R8 与硬件未测。GPU 和官方 SDK 实验均为 Linux JVM；SDK 取消能力仅核对源码。Standard Webhooks 1.2.0 缺少发布产物许可证元数据，若未来引入需核对精确版本许可来源。最终发布 head 的 CI、独立审核及正式模拟器回归仍待发布器和审核端。

Independent review, final-head CI and formal emulator regression remain required. This local handoff does not claim publication or merge of PR172.
