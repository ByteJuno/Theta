# Issue 57 verification

Implementation: `2e22ed3657003f9251e6b3ae52ad3e13611f28dd`, based on
`44e676af8185e09f9c56026f7efc058f7acee48f`. Debug APK SHA-256:
`6a4a629386125b7496cc86fb8a307f9174902e118a56c5c996a8b22c2305c002`.

Explicit credential replacement now retries once with a rebuilt key when key
lookup or cipher initialization reports an unusable key. Reading credentials and
updating settings with a saved key never authorize rotation. Usable keys remain
unchanged. The existing provider reentry/save controls use this path for the
separate chat and image aliases; plugin settings do not opt into recovery. Both
languages now state that saved credentials cannot be read and must be reentered.

Windows/JDK 25: 45/45 JVM tests passed, no failures/errors/skips, in one invocation:
CredentialKeyRecoveryTest (7), OpenAiSettingsStoreTest (20), ProviderSettingsEditorTest
(2), and PluginSettingsStorageTest (16). Debug and lint passed in that invocation
(74.76 seconds); lint reported zero errors, 28 warnings and one hint.
`--configuration-cache --configuration-cache-problems=fail` was used and the cache
was reused. The first attempt built Debug but failed compiling the new tests due
to private snapshot-field access. Tests were corrected to use the public target
boundary; the original failure log is preserved outside Git. This is not a
before/after behavioral reproduction on the original implementation.

New tests use real AES-GCM, the production recovery policy, atomic filesystem
records and provider editor/store paths, with injected key-provider failures.
They cover unreadable aliases and actual Cipher.init invalid-key rejection, read
and settings-update nonmutation, explicit replacement and cold reopen for both
profiles, usable-key reuse, corrupt-ciphertext replacement without rotation,
partial-generation cleanup, delete failure and retry, persistence failure and
retry, sanitized errors, immutable admitted snapshots, profile isolation and
plugin recovery opt-out. Existing tests cover concurrent record replacement,
snapshot requests, plaintext clearing and the no-backup factory boundary.

No real credentials were read. AndroidKeyStore/hardware invalidation, device,
Android API 33 runtime and Release/R8 were not exercised. No agent-core API was
added. Visual rendering was not verified: pending scenarios are credential read
failure, reentry, save success/failure/retry in Chinese and English on small
screens, large fonts and existing light/dark themes. Existing layout is reused;
only the failure message changed. No authorized local disposable render entry
was used. Final-head independent review, prescribed regression and CI remain
required before reviewer merge. Protected files are unchanged. Raw logs, XML,
source hashes and the matching APK are retained outside Git.
