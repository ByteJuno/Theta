# S1 closeout candidate — 2026-11-09

S1 closeout is in progress. Results in `HOOK_COMPAT_VALIDATION.md` remain bound to Host `0139f91a…fb91c9`; they do not establish acceptance of the bootstrap-authenticated candidate.

## Bootstrap boundary

The published provider now checks the original Binder caller UID before forwarding any call or reading Binder extras. Only exact root UID 0 and platform system UID 1000 may reach the unchanged official libxposed provider delegate. The library's independently exported provider is removed from the merged manifest, preserving its authority on the gated provider instead.

This authenticates a platform-privileged bootstrap origin. It does not identify a particular framework among other root/system software or defend against a compromised OS/root. Framework name, version, capability flags and Binder descriptors are not used as identity proof. Scope approval remains the framework's normal notification callback; this change does not grant scope.

Three JVM tests cover rejection without delegate execution, exact privileged forwarding, and delegate failure without fallback. A dedicated activity in the existing Hook Target calls the provider from its ordinary application UID through URI and authority forms, including null extras. It reports SecurityException results and whether the supplied fake Binder was transacted. The intended physical result is three rejections and zero fake transactions, followed by continued legitimate framework connectivity; that result is not claimed until executed on an identified APK.

## Physical performance inputs

PR Check's isolated paired job still reports only a nonphysical size measurement. A separate seven-day test artifact now preserves the two measurement APKs, frozen source manifests, effective R8 configurations, and resolved runtime dependency trees. The stripped comparison is not a plugin-compatible release.

Any physical use must verify the CI run/head and artifact hashes, verify the seven keep rules are the sole relevant input/configuration difference, and freeze the installed APK identities. If CI debug certificates differ from the phone's existing test certificate, local test signing must be recorded as a separate transformation, with non-signature ZIP entry hashes unchanged. Never replace CI artifact hashes with the locally signed hashes or bypass Android's installed-app signature check.

## Validation before physical execution

Initial local checks on this source: app 406 tests (405 passed, one existing platform skip), agent-core 162 passed, SDK test task had no cases. Total 568 tests, 567 passed, one skipped, zero failures/errors. Release and both dedicated samples built; lintRelease had zero errors and 15 warnings. Merged release manifest contains only `TrustedXposedProvider` at the bootstrap authority. These are build/JVM checks, not physical bootstrap acceptance.

Remaining physical work: the new binary's complete Hook authorization/disable regression, bootstrap rejection and legitimate connection, fresh crash-loop quarantine and same-signer version recovery, host-unavailable manager rescue, and controlled CI-bound paired frame measurements. Whole-framework disable or uninstall remains outside the personal-device boundary. Final acceptance must state which rescue path actually ran and retain any untested alternatives.
