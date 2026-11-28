# Anthropic adapter and official SDK boundary

Candidate assessed: `com.anthropic:anthropic-java:3.14.0`, upstream commit
[`b0349e817c4cb805a2906b019d4ac0caa66d02e6`](https://github.com/anthropics/anthropic-sdk-java/tree/b0349e817c4cb805a2906b019d4ac0caa66d02e6).
This is an assessment pin, not a Theta runtime dependency. Revisit this decision
when the upstream boundaries below change. The SDK is capable of ordinary
Messages requests; the decision concerns Theta's stronger attempt, liveness and
opaque replay contracts, not general SDK quality.

## Decision and reusable capabilities

Retain the existing bounded native adapter and shared `ModelTransport`. Do not
introduce a second client, tool runner, retry policy or history owner. The SDK's
public typed streaming API cannot directly replace this path while preserving
all the boundaries below. An adapted SDK is possible, but currently still needs
a custom transport, stream decoder and replay mapping, leaving little protocol
code to remove. Merely using generated data classes would add another JSON
representation and its dependencies without removing these responsibilities.

The SDK **does** support cancellation and disabling its own retry loop. These are
not reasons to reject it: `ClientOptions.Builder.maxRetries(0)` reaches
`RetryingHttpClient`; the OkHttp async future cancels its call and closing a
response during a blocked read cancels that call. A future integration should
use those facilities, set redirects off, retain Theta's single-attempt transport
contract and avoid the SDK tool runner. Theta must remain the owner of events,
approvals, the ledger, retry decisions and checkpoint recovery.

## Concrete differences

| Boundary | Candidate source and observed behavior | Required Theta behavior |
| --- | --- | --- |
| One HTTP attempt | [`HttpRequestBodies.kt`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/anthropic-java-core/src/main/kotlin/com/anthropic/core/http/HttpRequestBodies.kt#L34) makes JSON bodies repeatable; [`OkHttpClient.kt`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/anthropic-java-client-okhttp/src/main/kotlin/com/anthropic/client/okhttp/OkHttpClient.kt#L464) maps that to `isOneShot() == false`. Despite `maxRetries(0)` and `retryOnConnectionFailure(false)`, a loopback `503` with `Retry-After: 0` causes **two** HTTP requests. Reproduced with both the SDK's OkHttp 4.12.0 and Theta's 5.5.1. | One collection sends one request. A one-shot body prevents OkHttp follow-ups as well as connection retries; server-tool continuations must not be replayed implicitly. |
| Liveness and event framing | [`SseHandler.kt`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/anthropic-java-core/src/main/kotlin/com/anthropic/core/handlers/SseHandler.kt#L16) drops `ping` and unnamed events. Its raw handler and `SseMessage` are internal; comment-only frames are retained until a later message, not yielded as independent heartbeats. [`MessageServiceImpl.kt`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/anthropic-java-core/src/main/kotlin/com/anthropic/services/blocking/MessageServiceImpl.kt#L127) uses the filtering handler. A public-client fixture containing `ping` and an unnamed `message_stop` yields zero events. | Deliver `KeepAlive` for ping/comment heartbeat and decode data-only frames by their JSON `type`; retain bounded decoding and explicit terminal checks. |
| Opaque thinking replay | [`ThinkingBlock.toParam()`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/anthropic-java-core/src/main/kotlin/com/anthropic/models/messages/ThinkingBlock.kt#L32) copies thinking/signature but not additional properties; `RedactedThinkingBlock.toParam()` similarly copies data only. The generated parser retains unknown properties, and explicitly copying `_additionalProperties()` **does** preserve the tested block. Thus this is a default-conversion gap, not an inherent SDK impossibility. | Keep complete native blocks, including unknown fields, accumulated signature fragments and part order, tied to provider/endpoint/model. Reject incompatible history through checkpoint recovery. |

Cancellation source: [`OkHttpClient.kt`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/anthropic-java-client-okhttp/src/main/kotlin/com/anthropic/client/okhttp/OkHttpClient.kt#L131)
and its `ResponseBodyInputStream.close()` at line 583. Retry configuration source:
[`ClientOptions.kt`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/anthropic-java-core/src/main/kotlin/com/anthropic/core/ClientOptions.kt#L454).
Using `withRawResponse()` could preserve bytes but still requires Theta's decoder;
request interceptors/custom `HttpClient` could enforce one-shot bodies. Those are
possible future integrations, not capabilities claimed to be absent.

## Dependency and license review

The candidate POMs and resolved standalone runtime graph were inspected, rather
than treating the upstream core's compatibility-test lockfile as published
dependency versions. The runtime graph contains:

| Group | Resolved candidate versions |
| --- | --- |
| Anthropic aggregate, core, OkHttp client | 3.14.0 |
| OkHttp / Okio | 4.12.0 / 3.6.0; a second fixture explicitly selected OkHttp 5.5.1 |
| Jackson core, databind, annotations, JDK8/JSR310 datatypes, Kotlin module | 2.19.4 |
| Kotlin stdlib/common/reflect; JDK7/JDK8 bridges; JetBrains annotations | 1.9.25; 1.9.10; 13.0 |
| Victools generator/Jackson/Swagger2 modules; Classmate; SLF4J | 4.38.0; 1.7.0; 2.0.16 |
| Error Prone annotations; Swagger annotations | 2.50.0; 2.2.50 |
| OpenTelemetry API/context/common; Standard Webhooks | 1.62.0; 1.2.0 |

SDK 3.14.0 is Apache-2.0. Its published core/client JARs also contain
[`LICENSE-MIT`](https://github.com/anthropics/anthropic-sdk-java/blob/b0349e817c4cb805a2906b019d4ac0caa66d02e6/LICENSE-MIT),
which preserves notices for earlier MIT releases/contributions. Dependency POMs
or their parent POMs declare Apache-2.0 for the groups above except SLF4J (MIT).
Standard Webhooks 1.2.0's POM/JAR omits license metadata; its upstream Java
library [license](https://github.com/standard-webhooks/standard-webhooks/blob/main/libraries/java/LICENSE)
is MIT, but exact-release provenance remains a check before shipping that
dependency. No SDK sources or JARs are vendored here, and no dependency is added.
This assessment does not certify Android API33, R8, APK size or all transitive
runtime behavior; an adoption would require those checks and license notices.

## Scope retained and regression boundary

`AnthropicCodec` owns Theta message/tool/schema/media/cache conversion and opaque
replay. `AnthropicStream` owns ordered part events, complete signatures, bounded
input, usage normalization and terminal/error mapping. `AnthropicProvider` owns
capabilities and safe history eligibility; `AnthropicModelsClient` maps bounded
model pagination onto native authentication. Existing shared HTTP/SSE machinery
continues to handle bytes and cancellation. The run kernel owns pause limits,
server-execution barriers and recovery. No additional autonomous retry/tool loop
is implemented here.

`AnthropicTransportContractTest` permanently checks one native HTTP attempt on
the immediate-503 retry hint, ping/comment/data-only event visibility, and
unknown signed fields across delta assembly and replay. Existing Anthropic and
transport tests cover errors, headers, terminal validation and cancellation.
Candidate experiment logs and dependency/source snapshots belong outside Git;
the issue's single publication summary records exact validation identities and
limitations. Earlier RETURN evidence remains preserved.
