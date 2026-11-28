# Native Gemini adapter

`GeminiProvider` translates the stable `generateContent` content format over
`streamGenerateContent?alt=sse`. The base URL includes the API version (for
example `https://generativelanguage.googleapis.com/v1beta`). Credentials only
travel in `x-goog-api-key`; query credentials and URL userinfo are rejected.
The existing redirect-free, one-attempt OkHttp transport handles cancellation
and bounded SSE decoding. No dependency or third-party implementation is copied.

`GeminiCodec` projects tools through `SchemaProfile.BASIC`, using the documented
`parametersJsonSchema` field for the resulting JSON Schema. Original constraints
remain in the host catalog. Named/required choices use `ANY`; grouped function
results retain provider IDs when present and otherwise use the kernel's persisted
internal call bindings. Images are bounded session media encoded as `inlineData`.

Replay envelopes bind provider, endpoint, protocol and exact requested model.
Signed parts, including empty streamed text parts, stay in their original positions.
The UI receives a continuous text block while its replay envelope retains the
original native part array. The codec never joins signed and unsigned wire parts
or moves the first parallel call's signature to another call. Incompatible replay requires the
existing checkpoint continuation. Oversized replay fails rather than truncating
opaque data. Output image generation, server tools and continuation tokens are
not implemented; unknown stop reasons fail closed before executing client tools.

Thinking levels map to `generationConfig.thinkingConfig.thinkingLevel`. Rejected
thinking parameters produce a sanitized `UNSUPPORTED_PARAMETER`; the adapter never
retries them. The run loop implements the ratified first-step exception, records
capability information through the host observer and displays the existing gentle
reasoning adjustment notice. The v3 store preserves this observation for later runs.

Sources checked on 2026-11-28:

- https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta
- https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures
- https://github.com/googleapis/python-genai/blob/main/google/genai/chats.py

The SDK's streamed chat history retains response content parts rather than moving
signatures between them; Theta follows that boundary with its own event reducer.
Tests use authored protocol fixtures based on the official schema and a local
HTTP server. They are not a captured real-account conversation or live API acceptance.
