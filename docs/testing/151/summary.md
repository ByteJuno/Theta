# Issue 151 validation

Implementation `44d947ca4835fb0f16326b33496dd5684e308e15`, tree `0054737736043159bfb7ef926626f1e6d0897011`, includes main `b817d9364c7261b19d925408121cf6a7d4dd6a4f`.

- Previous PR head `e0764848b518ce7817e8fa906036808ccee5b53c` with regression tests added: 3 of 22 targeted JVM cases failed. An entry admission cleared unsent text, interleaved parts produced ACB instead of ABC, and an older ledger snapshot emitted WAITING_APPROVAL after REJECTED. The first baseline archive used CRLF for gradlew and ran zero tests (exit 127). The LF archive reproduced all three failures.
- On the implementation tree all 23 targeted theta-gpu-test JVM cases passed, including draft and attachment identity, streaming before the first part ends, bounded overflow, attempt discard, stale revisions and a fresh approval while the call state is unchanged.
- Windows scripts/preflight.py with strict configuration cache passed in 167.9 s. App unit tests: 730 passed, 1 skipped. Lint and 558 bilingual keys passed. Core and SDK test tasks and storage fixture compilation were up to date.

No APK generated and no phone or emulator used. Chat composer visuals, real attachment selection, rotation, API 33 runtime, R8 plugin linkage and live provider streaming remain unverified. New-head CI, independent review and prescribed emulator regression are pending.
