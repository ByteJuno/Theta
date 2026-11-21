# Issue 112 — bounded conversation previews

Implementation: `0715832bf22b3263e1adbda39c48653ef5dad0fd` (includes verified main `029adaf75c8118e27a33cbe6aed6057f3b587b0b`).
Debug APK SHA-256: `05c8faa0cb487c401485382c26394c1889dc04fcf12cd56a6ea97286668656a4`.

The conversation list now shows up to 60 Unicode code points from the first USER text, collapses whitespace/control characters, preserves surrogate pairs, and adds an ellipsis when clipped. Rows use stable session-ID keys, one-line layout ellipsis, a bilingual current-conversation label and selected semantics. The existing numbered label remains for empty/legacy/unreadable previews. Pagination uses its own database cursor, independent of the selected row, and hides Load more after exhaustion.

New sessions seed a tiny preview marker in the same create transaction. The first USER text updates that value in the same transaction as its history append; subsequent text never overwrites it. The shared schema remains version 5. Existing deletion removes the value/chunks atomically with history and retains tombstones. Old sessions without metadata deliberately retain numbered labels, even after a later append; no backfill/history decoding is attempted. A list page reads at most 50 preview values, each projected to at most 1025 bytes before CursorWindow, rejects oversize/type/ordinal/frame/checksum damage and returns fallback. No session handle or HEAD/batch is opened by preview reads.

Validation on this exact source:

- 20/20 targeted cases passed in one final invocation: SessionPreviewTest 6, SqliteRunHistoryTest 9, HistoryChunkBoundsTest 3, ChatSessionOwnerTest 2. Zero failures/errors/skips. Commands selected these classes and the session preview/history paging owner methods under `:agent-core:test` and `:app:testDebugUnitTest`.
- Covers cold reopen, first-vs-later input, blank text, 500000 emoji input, 55-session storage/53-session owner paging, switching, deletion/tombstone/new-session behavior and commit/rollback failure injection. A >8 MiB legacy first batch plus damaged HEAD remains listable with fallback. Listing does not change HistoryIo (including messagesDecoded); hostile metadata projection stays bounded.
- `:app:assembleDebug`, `:app:lintDebug`, bilingual resource guard passed. Lint: 0 errors / 28 warnings. Strict `--configuration-cache --configuration-cache-problems=fail`; final run reused cache, 57.37 seconds.
- Exact-APK-bound API33 direct-JDK scan: 404 compiled classes, 0 violations; 169 compiler bootstrap exclusions and 2 public-API resolutions. This is a static linkage check, not API33 execution or whole-app certification.
- Raw logs, XML, APK, scanner reports and all 349 tracked non-document source digests are outside Git in `event-runs/4b67793c-4819-4eab-93ab-b9dd0dac73d9` and `validation-issue11/issue112-4b67793c-*`.

Failures retained: initial fixture referenced nonexistent Role.SYSTEM (changed to ASSISTANT); API scan rejected StringBuilder.offsetByCodePoints (replaced by last-codepoint start tracking); integrating newly merged main introduced a duplicate TextOverflow import (removed). No assertions, deadlines or scan rules were relaxed. Earlier successful runs were superseded by final-source results above.

Visual/device acceptance is **unverified**: no dedicated writer rendering lease/entry was confirmed; personal phone and shared emulators were not operated. Reviewer still needs the final APK's list/open/select/load-more/delete path, fallback/current labels, Chinese/English, narrow screen/large font and light/dark rendering, plus prescribed emulator regression and final-head CI. No Release/R8, physical power-loss or real-provider test. Writer has not merged this PR.

AndroidX LazyDsl.kt's itemsIndexed key contract was inspected: stable unique Bundle-saveable session IDs keep item identity and scroll anchoring across list updates (https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyDsl.kt). No new library or protected/build/workflow file was changed.
