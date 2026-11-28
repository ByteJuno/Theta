# Issue 175 — web tools validation

Implementation commit: `8c81ac814e5d8130cc57f3748b08216f812e2b72`
Base: `baa65b8c7bd6d8bc392d1ddc79c373d7e33d12c3`
Tested tree: `b3e231cc3c6e85e5194888b0173aaa8925d502a2`
APK: not generated; SHA-256: not applicable.

Implemented public HTTPS web_fetch, per-hop DNS validation and address pinning, credential-free redirects, 2 MiB bounded downloads, jsoup text extraction, existing session BlobStore retrieval, SearXNG/Brave/Tavily search, encrypted atomic settings and bilingual full-screen settings entry under Plugins and diagnostics. Existing tool approval and deny rules remain the authorization owner.

Validation:
- `python scripts/preflight.py` with strict `--configuration-cache --configuration-cache-problems=fail`: passed in 149.3s. Source manifest unchanged during validation.
- `:app:testDebugUnitTest`: 845 tests, 0 failures/errors, 7 skipped; fresh execution. `:agent-core:test`: 663 tests, 0 failures/errors, 1 skipped, restored FROM-CACHE (not a fresh rerun).
- `:app:lintDebug`, `:app:verifyUiResources` (637 paired keys), `:samples:storage-fixture:verifyStorageFixtureSources` and `:samples:storage-fixture:compileDebugKotlin`: passed.
- 10 web regression tests freshly passed in the targeted task (21.1s), then included in full app suite. Real local TLS with certificate/hostname verification and test-only socket routing; production address policy remains enabled. Covers private/mixed DNS and rebinding rejection, redirect validation, separate pooled connections, TLS hostname rejection, declared/chunked/gzip size limits, no 503 follow-up request, cancellation, all three backend wire formats, encrypted settings/corruption preservation/failed write retention, large body archive/readback and cross-session denial.
- New jsoup dependency `org.jsoup:jsoup:1.21.2`: MIT, verified official Maven POM and https://jsoup.org/license .

Preserved original failed runs in this event directory: targeted (OkHttp non-null ProxySelector compile error), targeted2 (MockWebServer ByteString API compile error), targeted3 (missing whitespace in HTML fixture), targeted4 (pool fixture incorrectly expected rejection; OkHttp separates DNS policies, corrected fixture asserts distinct actual connection indexes and unchanged request count). No production assertion was removed, no deadline increased.

Limits: 未生成 APK，未操作手机或模拟器，视觉未验证。设置页正常保存、返回、损坏替换/清除，以及中英文、小屏、大字体、明暗主题与键盘遮挡仍待审核端实际渲染验收。未调用真实 Brave/Tavily/SearXNG 服务；本轮使用本地 HTTPS 和合成响应。Android API33 运行、Android Keystore 实机、R8、CI 与正式模拟器回归未验证。私网自建 SearXNG 暂不开放：所有网络目标统一要求公网 HTTPS。

Raw logs, XML and source hashes remain outside Git in `event-runs/e347eedb-d5ab-4972-bf72-32208ccb4961`. This summary binds only the implementation commit above; independent review, published-head CI and formal regression remain required.
