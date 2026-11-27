# Issue 159 validation

Implementation commit: `8f252ffe3d26f32f481261264c2e5f4eb8ab2e06`
Branch: `auto/159-provider-model-bindings`; PR #168 RETURN: https://github.com/ByteJuno/Theta/pull/168#issuecomment-7212857614

This return fixes per-record recovery of legacy chat/image settings, preserving readable providers, prompts and credentials. Image selection now reads the latest chat binding under the storage lock. v3 storage has a bounded 2 MiB ceiling while legacy codecs retain 256 KiB limits, accommodating both legacy records and v3 metadata expansion. Cold pending image calls without their original in-memory snapshot stop before any request or attempt claim; a new explicitly requested run uses the current binding. Prior attempted calls retain their durable no-replay result. No-auth generation omits Authorization; keyed requests remain covered. All three schema compatibility labels use paired English/Chinese feature resources.

Final `python scripts/preflight.py` passed in 132.33 seconds with `--configuration-cache --configuration-cache-problems=fail`. app: 742 tests, 741 passed, 1 existing Windows skip; agent-core: 633 tests, 632 passed, 1 existing Windows skip. UI resource parity (599 keys), lintDebug, storage-fixture source verification/compile and static protected-file/evidence checks passed. Agent-core results from the unchanged candidate were reused by the final Gradle invocation; app full suite reran successfully. Source tree: `043232c0eea75aa25d21c177e1689787d60f6d5d`.

Seven new regressions cover partial recovery in both directions, restored storage aborting replacement, near-limit v2 migration, missing cold-run snapshots, independent image selection, real SQLite cold approval followed by a new run, and anonymous HTTP headers. ProviderConnectionsTest: 12 passed; ChatSessionOwnerTest: 61 passed; ImageAttachmentsTest: 5 passed; OpenAiImageClientTest: 8 passed. Tests use synthetic credentials and local HTTP endpoints.

Retained failures outside Git: initial targeted run reproduced four failures (record loss, cold fallback, capacity and empty Bearer). First repaired run exposed a JUnit non-void test declaration, corrected without changing assertions. First full preflight had one existing oversized-history recovery timeout with its original 10-second limit. The unchanged test passed alone in 3.08 seconds and the subsequent full preflight passed. Neither timeout nor assertions were relaxed. Raw logs/XML remain in this event directory and `validation-issue11/issue159-7ba19-*`; reviewer verdict is retained locally. These earlier failures are not represented as passes.

APK: not generated; SHA256: unavailable. 本轮未生成 APK，未操作手机或模拟器。视觉未验证：兼容级别文案在小屏、大字体、中英文及明暗主题中的实际显示；设备上的配置迁移、冷启动审批、真实生图服务、API 33 与 R8 运行行为未验证。SQLite 冷恢复、凭据和请求头结果仅为 JVM 测试证据。新提交的 CI、独立审核与正式模拟器回归由发布后的审核端执行。

The writer did not push, create/update a PR, merge, or commit this summary. The existing publisher owns publication, and the independent reviewer owns final acceptance.
