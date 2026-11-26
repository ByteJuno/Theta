# Issue 151 validation

Implementation `44d947ca4835fb0f16326b33496dd5684e308e15`, tree `0054737736043159bfb7ef926626f1e6d0897011`, includes main `b817d9364c7261b19d925408121cf6a7d4dd6a4f`.

- Previous PR head `e0764848b518ce7817e8fa906036808ccee5b53c` with regression tests added: 3 of 22 targeted JVM cases failed. An entry admission cleared unsent text, interleaved parts produced ACB instead of ABC, and an older ledger snapshot emitted WAITING_APPROVAL after REJECTED. The first baseline archive used CRLF for gradlew and ran zero tests (exit 127). The LF archive reproduced all three failures.
- On the implementation tree all 23 targeted theta-gpu-test JVM cases passed, including draft and attachment identity, streaming before the first part ends, bounded overflow, attempt discard, stale revisions and a fresh approval while the call state is unchanged.
- Windows scripts/preflight.py with strict configuration cache passed in 167.9 s. App unit tests: 730 passed, 1 skipped. Lint and 558 bilingual keys passed. Core and SDK test tasks and storage fixture compilation were up to date.

No APK generated and no phone or emulator used. Chat composer visuals, real attachment selection, rotation, API 33 runtime, R8 plugin linkage and live provider streaming remain unverified. New-head CI, independent review and prescribed emulator regression are pending.

Revision `d4efa66a0749e1e2e5310851729c28bd8d93e5a3`, tree `c6bac7f0c90d26bfe8bf1402a327902946750267`. The previous baseline ran 24 targeted cases with two failures: an empty session survived failed admission and a completed entry run reported failure. Two initial attempts to test the candidate stopped before Gradle when the Dell endpoint was offline.

On the candidate, the development test endpoint selected the f512 runner and all 26 targeted JVM cases passed (17 entry-session, 4 chat-state, 5 plugin-entry). Strict Windows preflight passed again. The metadata-timeout test that failed CI on `d8b4459` passed locally. These checks cover session state and draft restoration; real attachment selection and screen rendering still require UI coverage.
