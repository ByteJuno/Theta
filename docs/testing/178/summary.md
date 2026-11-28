# Issue 178 validation

- Implementation SHA: `084c250a46aabf0b42c57b5b03ca18822b0f71f4`
- Branch: `auto/178-screen-tools`; PR #191 RETURN: https://github.com/ByteJuno/Theta/pull/191#issuecomment-7240677243
- Exact tested Git tree: `b1ad7eee5b8bd1a2a90c71ee4c7155c1a8b074aa`; fetched main: `49097146a429dc3a3a5cb245be928b69a37530e8`; observed PR head before publication: `a17ee69b8b26d990b9406c3f4c245d546e57a35f`.
- APK and APK SHA: not generated. No phone/emulator operations. Visual acceptance: not performed.

The RETURN fixes initialize PNG sampling at 1, preserve screenshot media inside its original ToolResult, and admit the complete outgoing user/tool image request against the existing memory ceiling before encoding. Anthropic and Responses encode native tool-result images; unknown/Chat/Gemini tool-media capability uses explicit text placeholders. No synthetic USER message is added. SQLite history and frozen content retain media references; legacy text-only history remains readable and ledger agreement still checks text/call/error identity. User images and the 64 MiB memory ceiling are unchanged. Existing Root-first/Accessibility routing, Unicode input, screen leases and UNKNOWN semantics remain covered.

Validation:
- Final `python scripts/preflight.py` passed, strict configuration cache stored, 1275.6s including the first 165 MB native test-library download. App: 931 tests, 922 passed, 9 platform skips, 0 failures/errors (103 suites freshly executed). Core: 723 results, 722 passed, 1 skip; UP-TO-DATE in final pass. Core's unchanged code was actually tested in this round's earlier preflight: 723 results, 0 failures/errors, 1 skip.
- Final exact-tree Linux GPU run used `--no-build-cache --configuration-cache --configuration-cache-problems=fail`, completed in 163.8s: entire app 931/931 passed with zero skips, and 3/3 ToolResultMediaTest cases passed. Native PNG cases exercised 48x64 and 3600x4800 PNGs through RootScreenBackend's real decoding/encoding path with injected process output; JPEG bounds/original coordinates, no fallback and cleanup asserted. This is not real su/device execution.
- Core tests cover native tool-result wire structure/order/call ID, text fallback independent of user-image support, SQLite reopen/legacy encoding, and 3,500,000-byte user image + 700,000-byte screenshot pausing BEFORE encoding. Normal captures complete, unsupported tool media keeps the original user image. Full original source history remains intact.
- 733 paired UI keys, lint 0 errors/109 warnings, storage-fixture source verification and Kotlin compile passed. No protected-file changes; no testing summary, logs, XML or APK committed by this writer.

Test-runtime repair: native tests failed in a combined suite on Robolectric 4.17 with FileSystemAlreadyExistsException in DefaultNativeRuntimeLoader.maybeCopyFonts, while isolated cases passed. Updated test-only dependency to 4.18.1 after confirming upstream fix source: https://github.com/robolectric/robolectric/commit/a52004a71eca5ec6c144108e6d436cb8a4e330ba . No assertions removed, timeouts expanded, or pixel mocks substituted.

Failures retained outside Git:
1. Original reviewer GPU diagnostics and old-head CI failure (native font JAR filesystem conflict).
2. First compile: recursive HistoryCodec inference required an explicit return type; fixed.
3. First targeted test: synthetic history lacked its authoritative ledger record; rebuilt fixture through real ledger output/actual-exit. Assertions retained.
4. First two GPU wrapper attempts exited 127 before tests because tracked gradlew contains CRLF. Linux scratch alone normalizes line endings; repo wrapper unchanged.
5. Combined 4.17 native tests reproduced the font filesystem collision. Isolated native tests passed but were not used as final combination evidence.
6. A later disposable GPU helper run still reported two native initialization failures without full stack retention; preserved, not promoted to a pass. Preserved-directory 4.18.1 combined checks and final exact-tree, no-build-cache FULL app run then passed. Final full XML is retained for independent review.

Evidence directory: `E:\Desktop\AI研究\theta-auto-loop\event-runs\a294c861-e9fe-40f8-821e-8fa0923353e4`. Primary final evidence: preflight-final.log/preflight-result.json/preflight-xml, gpu-exact.log/gpu-exact-result.json/gpu-exact-counts.json/gpu-exact-evidence.tar. Prior failures and reviewer evidence remain alongside; these artifacts are not committed.

Limits: 未生成 APK（APK SHA 未生成）；未操作手机或模拟器，视觉未验证。GPU 是 Linux JVM/Robolectric 验证，真实 PNG 编解码通过不等于实际设备 screencap、Root 权限、剪贴板粘贴或无障碍回调通过。Anthropic/Responses 只验证本地编码结构，未调用实际模型端点。当前提交的 GitHub CI、S1/S2、R8、签名及 API33 设备验收仍由独立审核端完成。Windows 最终 app 9 项平台跳过；GPU 完整 app 931 项零跳过。最终 core 为 UP-TO-DATE，723 项在本轮较早 preflight 实际执行且 core 输入未变。

Publication handoff only: writer commit is local and not pushed. Scheduler owns the unique testing summary and push/PR update; independent reviewer owns current-head CI, prescribed regression and merge. PR190 tracking remains unchanged.
