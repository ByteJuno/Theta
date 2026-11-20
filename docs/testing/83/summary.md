# Issue 83 — foreground task execution

## Source and artifact

- Implementation: `1c8aa5dd29cc35541c814c092d6492bfefb48e42`.
- Git tree: `06e9ca219b108f5822d23b61ff5a093c8dae124d`.
- Base: `a9194436561fd2a3f3e581fd95f1e4af38012b90` (PR98 merged).
- Debug APK SHA-256: `46839ec0a46bcca348c068fc781d599c420d6ac01b93a55678b718f72b2fe6ec`.
- The following documentation-only commit does not change tested source or APK identity.

## Delivered scope

The application-owned chat runner acknowledges the foreground service before requesting the model or approving a cold pending tool. Running notifications show model/tool progress and a token-bound Stop action through the existing owner cancellation path. Old notification actions cannot stop a later execution. Pending approvals stop foreground presence and show an ordinary attention notification; completion, failure, cancellation and paused outcomes stop the service. Noncooperative tool worker exit and resource isolation remain ledger-owned, not service-owned.

The service is non-exported, non-sticky and uses `specialUse` (API34+) with its subtype declaration; API33 uses the ordinary foreground entry. Interactive inference plus local plugin execution is not media playback, device messaging or a bounded data transfer. Current Android timeout documentation specifies the fixed time budgets for dataSync/mediaProcessing/shortService, not specialUse. This is not a guarantee against user stop, system process death, OEM battery policy or Play policy review. A renewable, timeout-bounded partial wake lock exists only while foreground service work is active and is released when it stops.

First-use battery guidance and notification permission handling reuse Material dialogs and bilingual resources. Dismissed/declined prompts do not recur. Denied notification permission does not falsely promise visible notifications; the dialog directs users to the chat Stop action. Explicit recovery remains required after an unavailable service; no automatic replay, boot receiver or scheduler is added.

## Executed checks

Windows, JDK25 and Android compile SDK37; `run-validation.py` clears release-signing environment variables and applies `--configuration-cache --configuration-cache-problems=fail`.

Final command tasks: `:app:testDebugUnitTest --tests *ChatSessionOwnerTest :app:verifyUiResources :app:lintDebug :app:assembleDebug`.

- 31 tests passed, zero failures/errors/skips, 95.82 seconds for the combined build; configuration cache stored.
- Coverage includes foreground acknowledgement before model submission, admission rejection without a model request, durable paused recovery, approval foreground suspension/restart, failed restart without granting approval, cold allow/deny admission, stale notification cancellation, ordinary completion, model filtering/context pause, active noncooperative tool cancellation and unchanged ledger exit truth.
- Existing chat/SQLite/approval/restart/context regressions in that class pass.
- Bilingual resource guard passed. Lint: zero errors, 25 warnings; the added SharedPreferences usage has a stylistic UseKtx warning, not a correctness error. Debug APK assembled; no release signing used.
- `git diff --check` passed. Protected paths unchanged. Existing two stashes and untracked `scripts/__pycache__/` retained.

## Failed attempts retained outside Git

- Initial resource guard rejected preference-storage keys inside a Compose file. The keys now belong to a non-UI preferences owner; no guard was weakened.
- Next attempt rejected a test fixture trailing-lambda signature after adding the presence dependency. Parameter order was corrected; no assertion or timeout was relaxed.
- An intermediate 30-test pass preceded the final admission-recovery improvement and is not claimed as final-source validation.
- Raw evidence: `event-runs/f2b122ad-435c-41e0-8001-8dc870ed4333` and `validation-issue11/issue83-f2b122ad-{initial,r2,r3,final}` outside the repository. Only this issue summary is tracked.

## Remaining acceptance and limits

No phone or emulator was accessed. No dedicated authorized writer renderer was available; other tasks' emulators are excluded. Actual rendering is **not verified**: English/Chinese battery and permission dialogs at small screen/large font/light-dark themes, running/approval notifications, and their actual touch targets.

The independent reviewer must validate the final head and APK: long-running task completion after Home and screen lock, notification Stop cancellation, approval transition to ordinary notification, resume after approval, notification-permission denial, service rejection, cold recovery and wake-lock/service cleanup. Prescribed lifecycle/signature regression and CI remain mandatory before reviewer-authorized merge. OEM phone acceptance belongs to S2 trial, not this writer run. API33 device execution, process death, Release/R8 and real-provider network behavior were not exercised.

References informing this implementation:

- Android service type/subtype requirements: https://developer.android.com/develop/background-work/services/fgs/service-types
- Type-specific timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- Background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/launch
- Now in Android notification implementation (permission check, channels, immutable activity intent; adapted to platform notification APIs, not its WorkManager news-sync model): https://github.com/android/nowinandroid/blob/main/core/notifications/src/main/kotlin/com/google/samples/apps/nowinandroid/core/notifications/SystemTrayNotifier.kt
