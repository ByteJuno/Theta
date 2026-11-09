# Hook document import and granted-scope compatibility validation

## Change boundary

Based on `c102b3aec033bc39e69d1310a0c172d63f2dd3d4` (main). Preserve the current Theta naming.

- Keep the default document picker and add an explicit system DocumentsUI alternative. Only installed system Android/Google DocumentsUI candidates are selected; otherwise use the implicit picker. Download is an initial-location hint, not a rewritten result URI.
- Wrap source I/O/security failures with actionable guidance, preserving causes and coroutine cancellation. Do not alter payload size, signature, signer pinning or target policy.
- Reuse only framework-reported existing scope. Missing scope still requires the normal request callback; recheck framework scope, action and service before returning a grant. Never treat timeout as approval.
- Check coroutine cancellation around scope queries and before publication. Tests include cancellation during both scope reads and during a missing-scope query.
- No new storage permission, framework configuration bypass or public SDK change.

## Local checks (2026-11-09)

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

## Exact physical-device candidate

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
5. The immediate same-process refresh screenshot still showed the old HOOKED text. The click was issued immediately after task switch without a settled-control check, so live propagation is **inconclusive on this candidate**, not a pass or established regression. Cold restart is the guaranteed exit path.

### Correction to the earlier timeout interpretation

The earlier 60-second timeout was real, but the corresponding scope-request notification was not approved. Manually checking scope in the manager does not complete that pending notification callback. A later read-only inspection found Theta's actual request notification, and the fresh notification approval above completed publication without changing requestScope's callback/timeout logic.

Therefore, do **not** report the old timeout as proof of a broken framework callback. Keep failed/unanswered attempts as separate evidence. Denial, dismissal, notification suppression and expired-request approval are not covered by this successful approval test. Bootstrap sender authentication remains an explicit implementation/integration limitation.

## Earlier candidate evidence — not transferred

Host `bc1216eb56ba0bc9e23d6066282bb569a6df33943c849d108a8a0af4137e45e9` had successful granted-scope reuse, live/cold disable, manager-based scope-removal rescue, three real fixture crashes followed by fourth-start quarantine, and same-signer version-2 recovery. That host used an older source base and lacked this PR's cancellation follow-up. Those results remain attached to that binary; they are not full regression results for the new main-based APK.

The default vendor media-provider ENOENT was diagnosed before source staging; the alternative DocumentsUI route is a compatibility path, not a repair of the OS provider. Earlier performance measurements used a separate frozen APK pair, not either Hook candidate.

## Evidence and remaining boundaries

Local evidence batch: `theta-physical-acceptance-20261109T015918Z/pr-integration` (not checked in): build logs/exit codes, validation.json, exact host APK, commands.jsonl and test-only PNGs including review, approved-status, approved-target and disabled-cold. Raw phone notification screens, credentials, device serial and private app data are not part of this PR.

This is not complete S1/J/K acceptance. Remaining work includes bootstrap authentication, other authorization outcomes, settled live-disable retest, final rescue branches outside the personal-device safety boundary, and G's controlled/CI-bound performance acceptance. No whole-device reboot, data clear, uninstall or unrelated module change was performed. Final test global switch is disabled, target shows ORIGINAL, and the dedicated scope remains approved.
