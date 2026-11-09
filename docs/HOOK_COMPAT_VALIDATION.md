# Hook document import and granted-scope compatibility validation

## Change boundary

Originally based on `c102b3aec033bc39e69d1310a0c172d63f2dd3d4`. After the review return, merged latest main `b33ab590` into this PR branch in `08d4860564554a358eecb86c77112ca96ad2e48c`. Preserve the current Theta naming. This is a branch update, not a merge of the PR into main.

- Keep the default document picker and add an explicit system DocumentsUI alternative. Only installed system Android/Google DocumentsUI candidates are selected; otherwise use the implicit picker. Download is an initial-location hint, not a rewritten result URI.
- Wrap source I/O/security failures with actionable guidance, preserving causes and coroutine cancellation. Do not alter payload size, signature, signer pinning or target policy.
- Reuse only framework-reported existing scope. Missing scope still requires the normal request callback; recheck framework scope, action and service before returning a grant. Never treat timeout as approval.
- Check coroutine cancellation around scope queries and before publication. Tests include cancellation during both scope reads and during a missing-scope query.
- No new storage permission, framework configuration bypass or public SDK change.

## Original candidate local checks (2026-11-09)

JDK 25.0.4.7, Android SDK 37, Windows; test release uses local debug signing, not a production release key.

```
:app:testDebugUnitTest :app:assembleRelease :app:lintRelease
-Pandroid.overridePathCheck=true --no-configuration-cache --max-workers=4
```

- 403 tests: 402 passed, 1 existing platform-conditional skip, zero failures/errors.
- Release build passed; lintRelease: 0 errors, 14 warnings.
- Added tests: HookDocumentPickerTest (10), HookScopeReuseTest (9).
- One initial invocation failed at the Windows non-ASCII path check before compilation. The explicitly quoted property invocation and the final post-review build passed; failure log retained locally.
- One independent read-only review found missing cancellation checks in the synchronous granted-scope path. Fixed with two regression tests and reran all checks above. No second review is claimed.

## Original physical-device candidate — not transferred to main-sync build

OnePlus PLK110, Android 17/API 37, ARM64; LSPosed framework 2.2.1/7927 and manager 2.1.1-it/7846.

- Host SHA-256: `24296a9bd358ff7837037a2337b83bca7703f59e8b8db06167b7d48bbe424ffb`.
- Host/normal fixture signer SHA-256: `0a03e9518759eb79fcb57b6264010ed1eff1193d5eea23facfbc75378b2ddf6b`.
- Target SHA-256: `8e39615ceffbf8f68b97121e2f4ebcb5bc5c2f6c0581497322303e6418fd529a` (unchanged fixture from preceding device run).
- Normal Hook payload SHA-256: `673934a00f1be3e96f0f958ebeb9edb778a350d54aca416b9fb76bb6689f30ef` (same previously staged fixture).

Executed on this candidate:
1. Cover-install without clearing data; system DocumentsUI -> Download -> normal Hook APK -> verified signer/target review succeeded.
2. Remove only Theta's dedicated target scope through the framework manager; normal publish entered the request-wait path and produced a real scope-request notification.
3. After several UI-location attempts timed out without approval, issue a fresh request. The operator clicked **Allow in that request notification**, not the manager's scope checkbox. Host then displayed `Published`; a cold target launch displayed `HOOKED (ORIGINAL)`.
4. Global disable followed by cold target restart displayed `ORIGINAL`. Installed host SHA-256 was rechecked and matched.
5. The initial immediate same-process refresh was inconclusive. A subsequent settled-control retest confirmed baseline `HOOKED (ORIGINAL)` on PID 27281, host `Global switch saved`, then explicit `Read Hook result` refresh to `ORIGINAL` with PID still 27281. Cold restart also returned `ORIGINAL`. This establishes sample-specific live disable, not precise latency or arbitrary-code unloading.
6. After the operator confirmed the module enabled and dedicated target scope unchecked, cold-host reconnect showed `Framework connected`. A newly inspected payload entered `Scope requested; awaiting framework decision`; a Theta-tagged `lsposed_module_scope` notification was independently observed. With no notification action, the host reported `TimeoutCancellationException; Timed out waiting for 60000 ms`. Cold target PID 27427 displayed `ORIGINAL`. This is an unanswered-request timeout test, not refusal, dismissal or late approval; remote publication records were not directly inspected.

### Correction to the earlier timeout interpretation

The earlier 60-second timeout was real, but the corresponding scope-request notification was not approved. Manually checking scope in the manager does not complete that pending notification callback. A later read-only inspection found Theta's actual request notification, and the fresh notification approval above completed publication without changing requestScope's callback/timeout logic.

Therefore, do **not** report the old timeout as proof of a broken framework callback. Keep failed/unanswered attempts as separate evidence. Denial, dismissal, notification suppression and expired-request approval are not covered by this successful approval test. Bootstrap sender authentication remains an explicit implementation/integration limitation.

## Earlier candidate evidence — not transferred

Host `bc1216eb56ba0bc9e23d6066282bb569a6df33943c849d108a8a0af4137e45e9` had successful granted-scope reuse, live/cold disable, manager-based scope-removal rescue, three real fixture crashes followed by fourth-start quarantine, and same-signer version-2 recovery. That host used an older source base and lacked this PR's cancellation follow-up. Those results remain attached to that binary; they are not full regression results for the new main-based APK.

The default vendor media-provider ENOENT was diagnosed before source staging; the alternative DocumentsUI route is a compatibility path, not a repair of the OS provider. Earlier performance measurements used a separate frozen APK pair, not either Hook candidate.

## Evidence and remaining boundaries

Local evidence batch: `theta-physical-acceptance-20261109T015918Z/pr-integration` (not checked in): build logs/exit codes, validation.json, exact host APK, commands.jsonl and test-only PNGs including review, approved-status, approved-target and disabled-cold. Raw phone notification screens, credentials, device serial and private app data are not part of this PR.

This is not complete S1/J/K acceptance. Remaining work includes bootstrap authentication, final rescue branches outside the personal-device safety boundary, and G's controlled/CI-bound performance acceptance. The original-candidate series ended with target ORIGINAL and the operator reporting the module enabled and dedicated target scope unchecked; expired-notification persistence was not checked in that series. Latest old-candidate follow-up evidence is in `pr-negative-live`. The separate main-sync regression below supersedes only the main-sync candidate's previously untested status.

## Main-sync local validation (2026-11-09)

- Built source commit `08d4860564554a358eecb86c77112ca96ad2e48c`, containing main `b33ab590088b57e7ae9732c606cc730fe6c55002`; subsequent validation-document edits do not change executable source.
- Tasks: `:agent-core:test :sdk:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease`, with the same JDK/SDK and quoted Windows path override above.
- App: 403 tests, 402 passed, 1 skipped; agent-core: 162 tests, all passed. Total: 565 tests, 564 passed, 1 skipped, zero failures/errors. SDK test task had no test cases.
- Release build passed; lint: 0 errors, 14 warnings. An initial PowerShell Stop-on-error invocation stopped on Java's stderr warning before compilation; retry with native-process exit-code handling passed. Both logs retained.
- New locally test-signed Host APK SHA-256: `0139f91af7174d30074604d3d20549591c1659a147a0d6eed13c55a2b5fb91c9`.
- Build evidence: `theta-physical-acceptance-20261109T015918Z/pr-main-sync` (local only). The new APK was cover-installed with data retained and was physically regressed in the separate evidence batch below. All earlier device results remain bound to their explicitly listed earlier APKs.

## Main-sync physical regression (2026-11-09)

Executed on the same OnePlus PLK110 / Android 17 / LSPosed setup, approximately 09:21–09:49 UTC. Installed Host SHA-256 was read from the `pm path` APK at both the beginning and end and matched `0139f91af7174d30074604d3d20549591c1659a147a0d6eed13c55a2b5fb91c9`. No executable source or APK changed during this series. Target and normal fixture SHA-256 were independently remeasured and matched the values above.

| Case | Observed result on this APK |
| --- | --- |
| Cold connection and disabled baseline | Host displayed `Framework connected`; explicit global disable was saved; cold target displayed `ORIGINAL`. |
| System document picker | Selected the normal fixture through system DocumentsUI. Review displayed fixture ID/version 1, the dedicated target, and signer `0a03e9518759eb79fcb57b6264010ed1eff1193d5eea23facfbc75378b2ddf6b`. |
| Unanswered request | Fresh awaiting-decision state and a changed Theta-tagged scope notification were observed. Host subsequently reported `Timed out waiting for 60000 ms`; cold target remained `ORIGINAL`. |
| Approval after timeout | Clicked Allow on that expired request notification. The scope notification disappeared; the host retained the timeout failure and cold target remained `ORIGINAL`. A fresh manager process later showed the target scope granted. Granting framework scope did not automatically retry publication. |
| Explicit refusal | Fresh request notification 2006 was refused through its visible Reject button, without selecting Don't ask again. Host immediately reported `Target scope not approved`; cold target remained `ORIGINAL`. Theta's existing remote payload listing was unchanged. |
| Notification dismissal | Fresh request notification 2008 was swiped away. Its record disappeared and host immediately reported `Target scope not approved`; cold target remained `ORIGINAL`, with the remote payload listing unchanged. |
| Normal notification approval | Fresh request 2009 was allowed through its notification. Host changed from awaiting decision to `Published`, a new remote payload appeared, and cold target PID 18048 displayed `HOOKED (ORIGINAL)`. |
| Same-process disable | After explicit host `Global switch saved`, settled target UI was refreshed with `Read Hook result`. Result changed from `HOOKED (ORIGINAL)` to `ORIGINAL` with PID still 18048. A subsequent cold target also displayed `ORIGINAL`. |
| Existing-scope reuse | A new explicit publication completed without a new scope-request notification. The remote payload name changed again and cold target PID 20017 displayed `HOOKED (ORIGINAL)`. Global disable and cold `ORIGINAL` were then reconfirmed. |
| Disable while request pending, then approve | Fresh request 2010 entered awaiting decision. Explicit disable was independently confirmed saved before its notification was allowed. Host reported `Hook action superseded; confirm again`; cold target stayed `ORIGINAL` and the remote payload listing did not change. |
| Dispose requesting UI, then approve | During fresh request 2011, public Android CLEAR_TOP navigation replaced the standard host Activity in the same PID 24852. The old composition was disposed and host reported `rememberCoroutineScope left the composition`. Allowing the notification afterward preserved that failure, did not change the remote payload listing, and cold target stayed `ORIGINAL`. This covers this Activity replacement path, not every possible UI/process-close path. |

The positive approval above followed an attempted disable tap that did **not** produce `Global switch saved`; that attempt is not accepted as a revocation test. The later request-2010 test independently established the required disable-before-approval order. Likewise, one notification expansion attempt opened an unrelated app; device input stopped until the operator authorized returning only to Theta. That request timed out and is not counted as a refusal. An initial short notification swipe failed to dismiss its card and is not counted as the successful dismissal above.

Only Theta's own remote-file directory was read. Negative-case listings retained the previously selected payload; positive publications created new UUID filenames and reclaimed the preceding payload. Remote preference contents were not directly inspected. These observations establish the listed sample behavior, not universal non-publication/security properties or precise switch latency. Bootstrap sender authentication remains unverified; arbitrary-code unloading, other firmware/targets, crash/quarantine/recovery on this APK, destructive rescue, and performance acceptance remain outside this regression.

Final cleanup explicitly saved global disable, cold-opened the manager to avoid its stale scope display, and removed only Theta's dedicated target scope. The module main switch remained enabled. Final cold target PID 31414 displayed `ORIGINAL`; the installed Host hash still matched. No scope-request notification remained; Theta's separate module-updated notification was left alone. No whole-device reboot, data clear, uninstall, unrelated scope/module change, or hidden approval API was used.

Physical evidence is local-only in `theta-physical-acceptance-20261109T015918Z/pr-main-sync-live`: timestamped command/action logs, cropped Theta request cards, masked test screens, and `复测结果.md`. Source remains `08d4860564554a358eecb86c77112ca96ad2e48c`; subsequent documentation commits do not create a new APK candidate. No old APK result is transferred to this table.
