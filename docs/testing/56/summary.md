# Issue 56 verification

Implementation `77332b0ed1fe277c0b9e6e638d39ad4a4debb755`, based on
`02aad1582ce6c12b789fb9c3df2dfeb1b0ecccc0`. Debug APK SHA-256:
`2516ab1937a6243ea552f1b5f334958e805587a312c15e7fcbb582d93deeee7c`.

The OpenAI chat adapter accepts an empty token as no authentication, using the
existing transport behavior that omits Authorization. ApiKeyCredentials still
rejects empty/blank keys, and nonempty malformed credentials still fail before
transport. Safe error parameters remain available without authentication;
credential echoes and arbitrary server parameters remain filtered.

On Windows/JDK 25, all 58 targeted JVM tests passed with zero failures, errors or
skips: 7 OpenAiCredentialsTest and 51 OpenAiChatTest. The same invocation built
Debug with `--configuration-cache --configuration-cache-problems=fail`, stored
the cache and completed in 43.06 seconds.

The new loopback HTTP test uses the real OkHttp transport: an unauthenticated
request completes an SSE response with no Authorization header; an API key
request has exactly one correct Bearer header. It checks the POST path and that
the synthetic key does not enter the body. Other cases verify blank API key
rejection with zero transport calls, malformed credentials, credential redaction
and safe error parameters without authentication. Existing chat protocol,
streaming, cancellation and error cases passed in the same invocation.

Before the production fix, the two new cases failed and five existing credential
cases passed. An earlier test compilation attempt used the wrong MockWebServer
body accessor; it was corrected before the behavioral reproduction. All raw
failure/success logs, XML, matching APK and tested-source hashes remain outside
Git. The documentation commit after implementation does not change tested code.

Scope is the existing chat adapter Credentials entry point. AuthMethod.None is a
protocol design concept; the current runtime represents it with an empty token.
The app's saved-provider UI/store and OpenAiRunSnapshot remain API-key-only;
model discovery and image generation authentication are unchanged. No new UI
selector, storage migration or broader no-auth product support is claimed.

No live provider, device, Android API 33 runtime, Release/R8 or visual test was
performed. UI rendering is not applicable to this adapter-only change. Final-head
CI, independent review and prescribed regression remain required before reviewer
merge.
