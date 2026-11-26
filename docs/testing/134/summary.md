# Issue #134: enabled data skills and personas

- Implementation and tested source: `0e848277b6f6edafdcf28e5815220a9cddd1443a`, based on main `7edaa707de5785aa334366c81e37a3102f1d95cc`.
- APK: not generated; APK SHA-256: not applicable. No writer device or emulator operation.

## Delivered behavior

Enabled, validated data archives provide a stable, globally qualified skill directory and validated personas. Skill metadata supports plain Markdown and optional scalar/folded frontmatter name/description. The 8,000-character directory prefix contains metadata only, in stable id order; full documents are read by the first-party READ tool `host/skill_read`. No enabled skills means the original system prompt is unchanged. Skill text is data: the host runs no scripts or commands from it.

The tool permits only `.md`/`.txt` documents within the selected skill directory (at most one nested directory), checks lifecycle selection and archive digest for each call, and uses the existing output normalizer and session-scoped BlobStore for long results. The ZIP allowlist now admits those ancillary text documents while retaining traversal, executable, archive, link and size restrictions.

Provider settings expose a global persona preference for new chat runs across sessions. The existing custom system prompt is retained. New runs revalidate availability; disable/uninstall clears an unavailable selection and exposes the bilingual fallback notice. Re-enabling the package does not silently restore the old selection. Loading, empty, failure/retry, busy and selected states have bilingual text.

## Executed validation

All Gradle commands used `--configuration-cache --configuration-cache-problems=fail`, with release environment variables removed by the existing runner.

- Targeted tests: **61/61 passed**, 57.67 seconds: prompt parsing/sorting/bounds, real ZIP/filesystem lifecycle and quarantine, path/cross-package rejection, actual host ledger automatic READ admission, complete long-document BlobStore round trip, and ChatSessionOwner requests across enabled/disabled/deleted persona transitions.
- Final implementation: `:agent-core:test` **580 passed / 1 skipped**; `:app:testDebugUnitTest` **671 passed / 1 skipped**. Zero failures/errors. Combined validation completed in **170.23 seconds**.
- `:app:verifyUiResources`: **485 English/Chinese keys passed**.
- `:app:lintDebug` and `:samples:storage-fixture:compileDebugKotlin`: passed.
- Configuration cache stored successfully; `git diff --check` passed. Protected files unchanged; no new filename contains `probe`.
- Platform skips: SummaryWorkspaceTest's link/parent traversal case and PluginUpdatesTest's executable-bit snapshot case. They are not counted as passes.

The first targeted attempt failed test compilation because a new assertion used `risk` instead of `declaredRisk`. Corrected the assertion; the subsequent targeted and full runs passed. Raw command output, XML and that initial failure are retained outside Git under the current run and validation directories. No assertion or timeout was weakened.

## Remaining evidence

Final published-head CI, independent reviewer acceptance and prescribed emulator regression remain pending. No live model endpoint, API33 runtime, Release/R8 or personal-device acceptance was performed. **Visual unverified**: no dedicated writer rendering surface was available. Reviewer scenarios: open provider settings/persona selector in English and Chinese; long persona names/ids; loading/empty/error/retry/disabled states; selection and unavailable notice; small-screen/large-font and light/dark layouts. Compilation/resource checks are not visual evidence.

Reference consulted: [Codex skill catalog rendering](https://github.com/openai/codex/blob/main/codex-rs/ext/skills/src/render.rs), specifically bounded metadata and deterministic catalog ordering. No upstream code was copied; Theta retains its existing data ZIP validation and host execution infrastructure.
