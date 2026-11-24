# Issue 80 verification

Implementation: `2accebd80eac6d1708c0ae3c876bf870af4b4955`, based on
`9334f8bd3c6ffd6dc484e06f384c055e0d550c2a` (`origin/main`). Debug APK SHA-256:
`f285493b60dd8188864ccb319a91fb7796ad3f0e425efbe3aa82201f4499c9d4`.

Probe verification before the fix (`issue80-probe-red-20261124`, preserved outside
Git): `FrozenDefinitionDigestSurrogateTest` confirmed 2/3 failures on unmodified
`origin/main`—definitions differing only by an unpaired UTF-16 surrogate
(`\uD800`, `\uD801`, `\uDC00`) or `'?'` in `name`, `description`, or `inputSchema`
collapsed to identical `FrozenDefinition.digest` values and caused an
`ALLOW_ALWAYS` receipt for one surrogate definition to grant another.

Fix: `FrozenDefinition.digest` now escapes raw unpaired UTF-16 surrogates in the
canonical JSON string as `\uxxxx` (reusing `escapeUnpairedSurrogatesForJson`
from `InvocationRecordCodec.kt`) before UTF-8 SHA-256 hashing. Because
`canonical(...)` already escapes literal backslashes to `\\`, escaping raw
unpaired surrogates to `\uxxxx` is injective (cannot collide with `'?'`, literal
`\ud800`, or valid surrogate pairs).

Impact on existing persisted approval records: for every valid Unicode definition
(ASCII, CJK, valid emoji surrogate pairs—all strings without raw unpaired UTF-16
surrogates), `escapeUnpairedSurrogatesForJson` is an exact identity function, so
all existing valid `definition-v1` digests and persisted `ALLOW_ALWAYS` /
`ALLOW_SESSION` / `ALLOW_ONCE` approval receipts remain 100% byte-for-byte
unchanged. Only definitions containing malformed raw unpaired surrogates receive
distinct lossless digests instead of colliding with `'?'`.

Windows/JDK 25: 233/233 JVM tests passed across all 24 suites in
`io.github.bytejuno.thetacore.agent.ledger.*` (0 failures, 0 errors, 0 skips) in
one invocation (`issue80-green-20261124`, 102.75s), including
`FrozenDefinitionDigestSurrogateTest` (3), `InvocationLedgerTest` (33),
`InvocationRecordCodecTest` (4), `LedgerRegressionTest` (11),
`SqliteInvocationStoreTest` (8), `SqliteMetadataProjectionTest` (66), and
`ToolOutputTest` (7). `:app:assembleDebug` and `:app:lintDebug` passed in the
same invocation with `--configuration-cache --configuration-cache-problems=fail`
(0 errors).

Limitations: physical device, Android API 33 runtime, Release/R8, and emulator
instrumentation were not run locally; final-head independent review, prescribed
regression, and CI remain required before reviewer merge. Protected files are
unchanged. Raw logs, XML, and red probe evidence are retained outside Git.
