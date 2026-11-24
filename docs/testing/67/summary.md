# Issue 67 verification

Implementation: `8c1d1c8011c73c045629f3d7f06c4e85cdad0f3e`, based on
`9334f8bd3c6ffd6dc484e06f384c055e0d550c2a` (`origin/main`). Debug APK SHA-256:
`7fd23220ef15c9c6738e6a68f3e2fc09fc4981c0db6a9589cdf9717bcb397912`.

`ToolOutputNormalizer` now reserves JSON-escaped UTF-8 envelope budget for media
placeholders and retrieval references before truncating structured JSON or long
text, while keeping the complete truncation notice and enforcing the 32 KiB
`ModelResultText` JSON envelope limit. When media placeholders alone exceed the
remaining budget, as many placeholders as fit are retained followed by an explicit
omission notice (`[<omitted> of <total> media attachment(s) omitted due to 32 KiB
limit; full media references are in original JSON: blob <rawId>.]`) and the full
truncation notice. `InvocationLedger.recordToolOutput` also re-applies the
media-preserving budget when an `Effect.UNKNOWN` warning prefix pushes an output
over 32 KiB. Outputs that already fit remain unchanged in their original order.

Windows/JDK 25: 30/30 JVM tests passed across `issue67-green-20261124` and
`issue67-green2-20261124` (0 failures/errors/skips):
- `agent-core` (`issue67-green2-20261124`, 21.93s, cache reused):
  `ToolOutputTest` (10), `BlobRegressionTest` (11).
- `app` (`issue67-green-20261124`, identical production sources, up-to-date in
  `issue67-green2-20261124`): `HostBlobOutputTest` (8), `HostSessionBlobTest` (1).
`:app:assembleDebug` and `:app:lintDebug` passed with
`--configuration-cache --configuration-cache-problems=fail` (0 errors).

New tests cover 60,000-character structured JSON with multiple media types
(IMAGE, AUDIO, DOCUMENT, VIDEO), long multibyte/escaped text with media,
`Effect.UNKNOWN` warning prefixing via `InvocationLedger.recordToolOutput`, and
excessive media overflow (400 attachments) verifying explicit omission notice,
intact truncation notice, and <= 32 KiB JSON envelope size.

Limitations: physical device, Android API 33 runtime, Release/R8, and emulator
instrumentation were not run locally; final-head independent review, prescribed
regression, and CI remain required before reviewer merge. Protected files are
unchanged. Raw logs and XML are retained outside Git.
