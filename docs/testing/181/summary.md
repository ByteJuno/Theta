# Issue 181 validation

Implementation SHA: 5fd302eaaacf098a3d99386c718ab90de3ba82be
Tested source tree: 861326ff13bd8702083a54e6033c61966b2acbdf
Base/final fetched main: b6296021a9f42b3662a480af9f41bd155b440354
APK: not generated; APK SHA: not generated.

Implemented shared SQLite schema 9 memories/settings, bounded CRUD and literal search, READ/WRITE tools through existing approval, a 4,000-character new-run prompt section, and bilingual full-screen management. Default enabled; disabling stops AI memory tools and future-run injection but retains manual management. Limits: 2,000 UTF-16 content characters, 64 category characters, 500 entries, 512 KiB combined content/category/source UTF-8 bytes. Ranking uses pinned first then update recency; no access-frequency telemetry. Exact normalized category/content deduplicates; explicit edits cannot overwrite another entry.

Validation on the recorded tree:
- scripts/preflight.py: PASSED, 149.97s, --configuration-cache --configuration-cache-problems=fail, --no-build-cache.
- :app:testDebugUnitTest: 938 tests, 929 passed, 9 platform skips, 0 failures/errors; all 106 suites executed in final preflight.
- :agent-core:test: 733 tests, 732 passed, 1 platform skip, 0 failures/errors; executed in initial-preflight this round, final UP-TO-DATE with identical core inputs.
- :app:verifyUiResources, :app:lintDebug (0 errors, 109 warnings), :samples:storage-fixture:verifyStorageFixtureSources and compileDebugKotlin: PASSED.
- Twelve memory tests passed, including version 8 migration retaining history, ledger and document bytes; durable settings; capacity rejection preserving shared storage; literal keyword matching; prompt budget; frozen WRITE definition/approval gating and disabled recheck; cold-session prompts; English and Chinese Compose CRUD/confirmation paths.

Evidence: event-runs/2bf8cd66-f352-4be1-a7e8-0f5ba6c8bf7c; targeted logs under validation-issue11/issue181-2bf8-targeted-r3. First two targeted attempts stopped at resource validation/test compilation; initial full preflight failed on a test-only FrozenDefinition field name after core tests passed. Original logs/results retained. Final remote review inspection detected a new PR190 RETURN and stopped its unchanged-cursor assertion before commit; saved separately and queued using that PR's cursor/pending_review_event. No PR190 fix or evidence was mixed into this implementation.

Limitations: 未生成 APK（APK SHA 未生成），未操作手机或模拟器；视觉未验证。Robolectric 覆盖英文 360dp 浅色和中文 320dp 双倍字体深色的布局、语义、增删改查与开关操作，不代表当前 APK 像素或触摸验收。没有调用真实模型接口。新提交 CI、独立 S1/S2 模拟器回归、签名/R8/API33 设备与实体断电验证仍待审核端完成。core 最终为 UP-TO-DATE，733 项在本轮前次完整 preflight 实际执行且 core 输入未变化。

## Reviewer round 1 (0 prior returns)

Reviewed head: 18447ec6cbb1daf800409c50e341b82e0c34445e. Its implementation tree matches the original evidence above; only this summary was added afterward.
Fixed source commit: 631b7be3a370dbaf288b7ae9df19440e910eca0b; tracked tree: e70eb64bbfd2dc27c1717ed8c114a1b263fde948. Tests used these tracked contents plus two untracked Probe test files, which are excluded from commits.

- Before the fix, the GPU search probe failed: saving decomposed `cafe\u0301` normalized it to four UTF-16 units, but searching the original five-unit input returned zero matches; the normalized query returned one. Search now uses the same normalization as saved content and categories.
- Before the fix, the GPU Compose probe failed: an invalid multiline category produced a storage/restart error, and that error remained after a corrected filter successfully displayed the saved entry. Filter validation now reports input errors, and fresh loads clear filter errors without clearing mutation feedback.
- After the fixes, `/opt/homelab-report/theta-gpu-test :agent-core:test --tests '*memory.*'` passed 8 tests (7 committed regressions and 1 untracked probe), zero failures. Original and normalized search inputs both returned one match.
- `/opt/homelab-report/theta-gpu-test :app:testDebugUnitTest --tests '*memory.*'` passed 8 tests (7 committed regressions and 1 untracked probe), zero failures. The corrected filter displayed one entry with zero remaining storage errors; the committed regression also checks input-error classification and removal.
- Both original failing probe runs are retained in the review tool output. No assertions were removed and no timeouts were increased. Only this summary is committed as evidence. No new APK, rendered visual acceptance, model API call or physical-device result is claimed.
