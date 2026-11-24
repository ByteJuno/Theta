# Issue 57 verification

Implementation: `aac585f920c922dac04e73bed72acdafe931ce4e`, based on
`632ae22eb26f28852f325e8686992bb15ad66231` (merged with `main`). Debug APK SHA-256:
`6100b9beb029e56f1dddb351e1470fcae77136be4f31cf35048a36735a1267a4`.

Explicit credential replacement now classifies AndroidKeyStore and JCA failures
fail-closed across the full exception cause chain before deleting and rebuilding a
key. Only definitive key invalidation (`KeyPermanentlyInvalidatedException`,
non-transient/non-auth/non-system `android.security.KeyStoreException` with
`ERROR_KEY_CORRUPTED` or `ERROR_KEY_DOES_NOT_EXIST`, or explicit AndroidKeyStore
boundary detection of non-`SecretKey`, non-AES, or dangling alias entries) authorizes
replacement. Transient IO/service failures, authentication/temporal exceptions,
system errors, unknown bare `InvalidKeyException`/`UnrecoverableKeyException`, and
any cause/suppressed chain containing a non-recoverable veto preserve the original
key and encrypted record so old credentials remain readable once the fault clears.
Reading credentials and settings-only updates never authorize rotation. Both
languages state that saved credentials cannot be read and must be reentered.

Before the fix, `CredentialKeyRecoveryTest.wrappedTemporaryAndUnknownFailures*`
reproduced the return finding on `632ae22eb26f28852f325e8686992bb15ad66231` (1/1
failed; evidence preserved outside Git in `issue57-return-red-20261124`). The first
green attempt failed compilation on `requiresUserAuthentication()` method syntax
(`issue57-return-green-20261124`, preserved outside Git). After fixing that call:

Windows/JDK 25: 48/48 JVM tests passed, no failures/errors/skips, in one invocation
(`issue57-return-green2-20261124`): CredentialKeyRecoveryTest (10),
OpenAiSettingsStoreTest (20), ProviderSettingsEditorTest (2), and
PluginSettingsStorageTest (16). Debug APK assembly and lintDebug passed in that
same invocation (77.50 seconds, 0 errors);
`--configuration-cache --configuration-cache-problems=fail` was used and the cache
was reused.

New and updated tests use real AES-GCM, the production fail-closed cause-chain
classifier, atomic filesystem records, and provider editor/store paths with injected
key-provider and cipher-init failures. They cover wrapped transient IO/auth/system
and unknown bare exceptions paired with both succeeding and failing key generation
(asserting original key and record preservation plus cold-reopen readability after
fault recovery), all `android.security.KeyStoreException` error codes and flag
combinations, cause/suppressed veto precedence, definitive lookup/wrapped-lookup/
cipher-init invalidation recovery for both profiles, read/settings-update
nonmutation, usable-key reuse, corrupt-ciphertext replacement without rotation,
partial-generation cleanup, delete/persistence failure retries, sanitized errors,
immutable admitted snapshots, profile isolation, and plugin recovery opt-out.

No real credentials were read. AndroidKeyStore/hardware invalidation, device,
Android API 33 runtime, and Release/R8 were not exercised. No agent-core API was
added. Visual rendering was not verified: pending scenarios are credential read
failure, reentry, save success/failure/retry in Chinese and English on small
screens, large fonts, and existing light/dark themes. Existing layout is reused;
only the failure message changed. Final-head independent review, prescribed
regression, and CI remain required before reviewer merge. Protected files are
unchanged. Raw logs, XML, and failure evidence are retained outside Git.
