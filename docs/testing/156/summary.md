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
