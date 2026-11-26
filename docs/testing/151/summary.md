# Issue 151 validation

Implementation `347d9601aa0c02c8f9d737d0b726914e8220e838` on tested tree `6032a61ee387e0e8abe23bd90a57253669ed377e`. Integrated main `66751d7913632b3b5615fe5e48ffc5f4afc140dc`.

- Full preflight with strict configuration cache passed in 144.3 seconds. App JVM suite: 722 passed, 1 skipped, zero failures or errors. Lint, UI resources and storage fixture verification/compilation passed. SDK unit tests reported NO-SOURCE. Core was UP-TO-DATE after the first preflight restored cached results (628 total, 1 skipped, zero failures or errors).
- theta-gpu-test: 16 JVM tests passed on the verified Git tree, including both entry suites and the metadata timeout case that failed CI on the preceding PR head.
- The return-dispatch test cancels 33 openings after acquisition. It checks reclamation each time and the admitted run completes with valid history. Plugin shutdown permits all 32 handle slots to be filled again. An interrupted model stream delivers its partial text, the discard boundary, successful retry text, a retained boundary and Finished in order. The failure journal preserves the partial and the assistant history contains the successful answer.
- Initial GPU archive could not start Gradle due to CRLF. After preserving Git line endings, two regressions exposed duplicate acquisition of the selected history. Fixed and reran on both platforms. Raw evidence remains outside Git.

No APK generated. No device, API 33 runtime, R8 plugin linkage, or live provider tested. New-head CI, independent review and the prescribed emulator regression are pending.
