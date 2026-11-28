# Issue 176 validation

Implementation SHA: `025f9493e9566c3d617d894c0ad32ad5ccb625a7`
Tested tree: `d3c1e34a16ab6ef19382e00d9fc6e8e7f687a093`
Base: `00ee9e91db4eef817008de09fec2bb3731f2b304`

- Added OpenDocument attachment selection, up to 4 documents alongside images, name/size preview and removal, persistent per-session drafts, admission receipts and compact sent-document labels.
- PDFBox-Android 2.0.27.0 (Apache-2.0) extracts real PDFs in a private parser process. Limits: 5 MiB/file, 100 pages, 200,000 UTF-16 units, 4,000-unit excerpt, 24 MiB PDF scratch backing store and 30-second worker watchdog. No OCR or encrypted PDFs. Known code extensions with generic MIME still require valid UTF-8 and no binary controls.
- Full UTF-8 text and session ownership commit atomically into the existing chunked SQLite BlobStore; blob_read enforces session ownership and bounded range checks. Schema 7 to 8 preserves composer drafts; deletion collects document roots.

## Actual checks

- `python scripts/preflight.py` with strict configuration cache passed (151.09s final invocation).
- `:agent-core:test`: 716 tests, 0 failures/errors, 1 skipped; executed in preflight-final, UP-TO-DATE in final-2 with unchanged core sources.
- `:app:testDebugUnitTest`: 879 tests, 0 failures/errors, 7 skipped; executed on final source.
- `:app:verifyUiResources`: 690 paired keys; `:app:lintDebug`: 0 errors, 103 warnings; `:samples:storage-fixture:verifyStorageFixtureSources` and `compileDebugKotlin` passed.
- Production PDF library tested with actual generated PDF, encrypted/corrupt/empty/101-page/overlong PDFs; UTF-8 code/text, illegal MIME, binary/size rejection; parser truncated/extra payload rejection; full blob restart/ranges/ownership/deletion; failed-send retention and successful admission cleanup/stale retry prevention.
- Failed evidence retained locally: compile-1 moved model text out of the Compose boundary; preflight-1 had 5 synthetic legacy-schema fixtures retaining the newly added table. Fixtures corrected without removing data preservation assertions. No old APK or device evidence reused.

## Limits

未生成 APK，APK SHA 未生成；未操作手机或模拟器，视觉未验证。Robolectric API33 运行真实 PDF 提取不等于 API33 设备验收。SAF 文件选择、Binder 管道、解析进程崩溃/超时恢复、导入期间旋转、混合图片/文档、小屏/横屏/大字体/中英文/明暗主题均待审核端当前构建验证。新 head CI、正式 S1、R8、签名及真实提供商 blob_read 多轮未执行。

Source/license: https://github.com/TomRoush/PdfBox-Android/tree/v2.0.27.0
Evidence: `E:\Desktop\AI研究\theta-auto-loop\event-runs\d1865de1-cc68-4938-b76c-fba5f558d46f`
