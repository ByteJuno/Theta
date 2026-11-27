# Issue 156 validation — RETURN: concurrent output cleanup

Implementation SHA: `ae27cf1c010e1c1f7eb22916ee6b43c7559885af`
Tested tree: `47608adef1bc2bc1e4e1d55f16cc8d38784dce9b`
Branch: `auto/156-compose-markdown`
Remote PR head incorporated: `8f2c42d04810d39dbbcfcdd1369560b145a97a72`
Fetched main: `341e7c195324dad4f049b29fbfc6a23545857716`
Run: `e9d38463-1d13-4533-901d-c421c30feb9a`
RETURN: https://github.com/ByteJuno/Theta/pull/171#issuecomment-7221845847

## Repair and evidence

The OWNER RETURN and CI run 46019137840 were verified against the actual PR head. build failed in PluginOutputSurfacesTest.disableDeadlineReportsExhaustionAndRetiresQueuedClear (809 tests, one failure); paired-keep-size passed and the reviewer skipped emulator regression. Full comments, reviews and inline comments were read. Reviewer state identifies this same head as waiting_writer/own_commit. Reviewer commits e736c2653de42d5b35cbc0a8691e786a005e881f and 8f2c42d04810d39dbbcfcdd1369560b145a97a72 were fast-forwarded locally, preserving the Markdown chunk boundary/performance fix, license correction and historical summary.

The failure is a production concurrency defect: independent binding drain removes pendingClears entries while Kotlin Collection.toList checks size. At size one, its optimized path calls iterator.next without hasNext; a concurrent removal throws NoSuchElementException and kills the sole reconcile consumer. Subsequent task completion then never clears the fallback surface. Iterate ConcurrentHashMap's weakly consistent key set directly using hasNext/next instead. No cleanup deadline, resource release semantics, assertion or timeout was weakened.

Original failure reproduced locally at zero-based repetition 10 of the unchanged deadline test (baseline-repeat): expected low clear a, got null; system-err records NoSuchElementException at PluginOutputSurfaces.reconcile:231. The new formal regression retiringQueuedClearsKeepsRoutingLaterTasks also failed on old production code at task17 (regression-red) with the same exception. It runs 100 task cycles with both plugin slots occupied, retires queued clears concurrently, asserts cleanup exhaustion, disable/resource ordering, fallback clearing, no late high clear and continuing routing. Both red logs/XMLs and the temporary repetition harness remain outside Git in the run output.

## Validation on repaired sources

- Targeted suite: 25/25 passed, no skips, 44.3s. Includes all output-surface tests, 12 Markdown regressions, and the temporary harness executing the original deadline test 100 times. The permanent regression executes 100 routing/cleanup cycles. The temporary harness was moved outside the checkout before final preflight and was not committed.
- Final python scripts/preflight.py passed in 149.4s with --configuration-cache --configuration-cache-problems=fail. Configuration cache stored. app: 810 tests, 809 passed, one existing platform skip; agent-core: 633 tests, 632 passed, one existing platform skip, Gradle FROM-CACHE on unchanged inputs. No failed test was retried to claim a green final run.
- UI resources: 606 paired English/Chinese keys. lint, storage fixture source verification and compile passed. git diff --check passed. Source SHA256 manifest unchanged during checks and after commit; tested tree is recorded above.
- Final fetch before preflight found both origin/auto/156-compose-markdown and origin/main already contained in HEAD. Final remote comments/reviews/inline comments showed no newer OWNER or RETURN. PR169 remains separately open and waiting for its review, with unchanged tracking and evidence.
- This commit changes only PluginOutputSurfaces.kt and its permanent test. No protected files, shared host wiring, UI strings, docs/testing summary, raw logs, temporary harness, APK or diagnostic file committed. No push, PR update/create, merge, device action or new scheduler was performed. Existing untracked scripts/__pycache__ and all stashes preserved.

## Limits and handoff

本轮未生成 APK，未操作手机或模拟器，视觉未验证。未进行真实供应商、API33 运行、R8、硬件或实体断电验证。原 Markdown 界面的中英文、明暗主题、小屏/大字体、流式性能、TalkBack、链接打开和代码复制仍需当前构建验收。旧 APK 身份及此前结果仅保留为历史证据；新 head 的 CI、正式模拟器回归、独立审核与合并由审核端负责。

The writer hands publication.json to the existing publisher with state.status=publication_pending. Publisher owns updating this single issue summary, verifying refs again and publishing. The independent reviewer owns new-head CI, formal regression and merge. This local repair is not a claim that PR169 or new-head remote CI passed.

## Historical evidence — original SHA bindings retained

# Issue 156 validation

- Implementation commit: `cb1f0d322a7e0884f0f721bb0aa39f44040fac97`
- Tested source tree: `815e9353b9341bb7b7db4d4780f0fdf11036f8f5`
- Fetched main/base: `341e7c195324dad4f049b29fbfc6a23545857716`
- Branch: `auto/156-compose-markdown`
- Run: `645400ce-0ee6-45ce-b018-d127ba7af0fd`
- Source manifest unchanged across checks and commit: `preflight-final-r1/tested-worktree-sha256.json`.

## Implemented

Pure Compose AST rendering for message bodies, reply journal text and streaming preview; commonmark-java core + strikethrough 0.33.0, BSD-2-Clause with packaged notice. Styles, headings, lists, quotes, separators, code scrolling/copy, guarded HTTP(S) links; HTML literal and images alt text only. Bounded parser nesting, background conflation, stable completed preview and full-text fallback. Stored source Markdown is unchanged.

## Actual checks

- Targeted JVM: 11 tests, 11 passed, 0 skipped; 69.2s (`targeted-r1/result.json`). Every stream prefix, open fence/emphasis, nested list numbering, HTML/image boundary, unsafe schemes, Unicode/abnormal input, long code/copy source, fallback and references.
- Full preflight: passed, 158.3s (`preflight-final-r1/result.json`). Strict `--configuration-cache --configuration-cache-problems=fail`; configuration cached successfully.
- App: 808 tests, 807 passed, 1 known platform skip; core: 633 tests, 632 passed, 1 skip, restored by Gradle FROM-CACHE on unchanged inputs. No other PR's implementation results are attributed to this head.
- UI resources: 606 bilingual keys; lint and storage fixture compile/source checks passed. New nonblocking UseKtx suggestion only.
- Debug APK generated: `E:\Desktop\AI研究\theta-auto-loop\repo\app\build\outputs\apk\debug\app-debug.apk`; SHA-256 `7159135ebc55c5473e849ccb496a46dd2a05b5df5da3f63e60078de06c14bfcf`; 55713756 bytes. Packaged commonmark license verified against source. APK is not installed or release/R8 tested.
- `git diff --check` passed; only eight implementation/license/resource/test files committed. No protected files, summary, logs, probe files or APK committed. Prior untracked scripts/__pycache__ preserved.

## Limits and remaining acceptance

视觉未验证：本轮未操作手机或模拟器，没有当前 APK 的截图、TalkBack、点击/复制、主题/语言/小屏/大字体和流式帧率验收。专用正式模拟器回归由独立审核端执行；android-lab 当前文档仅列出个人手机与禁止本轮使用的 5582/5588。API33 真运行、R8、真实供应商流、硬件及实体断电未测。长消息超过 256 Ki 字符或 AST 超过 2000 节点时保留全文并提示纯文本回退；超长单行代码按 4096 UTF-16 单元分段显示，复制按钮保留原代码。常规 Markdown 未包含表格或语法高亮。

Independent reviewer owns current-head CI, code review and formal emulator acceptance. Required visual scenarios: two languages, light/dark themes, small/landscape/split windows, large font, selectable text/links, copy feedback, no-browser fallback, code scrolling, deep nesting and unfinished streaming Markdown. Unit tests and DEX packaging do not prove these outcomes.

## Preserved observations

- No test/build failures in this implementation round.
- Initial PowerShell read used legacy encoding and produced truncated display; reread continuation as UTF-8, parsed complete state, and preserved full state snapshot. `rg` unavailable; used Python/PowerShell fallback.
- Initial read-only source inventory had a relative-path ValueError; corrected before implementation.
- Guessed upstream FencedCodeBlockTest path returned HTTP404; retained commonmark-parser-test.txt and used actual upstream parser source instead.
- PR169 new RETURN `IC_kwDOVkrS_s8AAAABrmUhfA` observed at its unchanged head. Its pending review is separate; last handled cursor remains unchanged for the next scheduler event. No issue156 evidence moved to PR169.

## Independent review, round 1 (2026-11-27)

- Reviewed head: `8037f8f25b8c2a3267398a4772e4634e80ccd3db`; prior writer returns: 0. Its only change after the validated implementation above was this summary, so the original preflight evidence was reused without rerunning the full suite.
- Reviewer fix: `e736c2653de42d5b35cbc0a8691e786a005e881f`, tree `2eff852400dd3b2b1c41a18d58cc45d0841c5412`. Targeted tests ran on the same source before committing. The review probe stayed untracked and is not part of this tree.
- GPU probes read `META-INF/LICENSE.txt` from both actual 0.33.0 dependency JARs. Both contain the BSD-2-Clause notice credited to Atlassian Pty Ltd. Corrected the packaged notice's wrong Robin Stocker attribution; the remaining license text matches.
- Preserved failing evidence: the boundary/performance/license probe run had 3 tests, 1 failure (`Spurious empty Text at lineLength=4096`, Gradle exit 1, reported build duration 30s). A 4096-character code line plus its terminating newline yielded display chunks of lengths `[4096, 0]`. Code display now consumes that terminating newline with its preceding chunk, without adding an empty Text. Regression coverage also preserves intentional blank lines, surrogate pairs, source text and the 4096-unit displayed-chunk bound.
- The same run measured newline-free chunking at 13/55/220/875 ms for 1/2/4/8 Mi characters. The old backward search rescanned the entire preceding prefix for each chunk, including during the synchronous initial plain-text render. Restricted searches to the current chunk. A focused post-fix run, needed because the combined output omitted the larger measurements, reported 0/1/3/10 ms respectively and passed 1/1 tests. These are GPU JVM timings, not device frame measurements.
- Post-fix GPU command: `theta-gpu-test :app:testDebugUnitTest --tests '*MarkdownDocumentTest*' --tests '*MarkdownReviewProbe*'`; 16 tests passed, 0 failures, Gradle exit 0 (12 committed regressions and 4 untracked probes). Deep quote/list/emphasis/image inputs completed without exceptions. `git diff --check` passed.
- No protected files or existing assertions/timeouts were changed. No raw evidence or probes were committed. No push, merge, external comment or device operation was performed.
- 视觉仍未验证。The APK hash and full preflight results above identify the original writer implementation only, not the reviewer fix. Current final-commit APK/CI and formal emulator acceptance remain with the outer review script; this review does not claim rendering, clipboard or browser-launch acceptance.
