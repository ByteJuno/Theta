# Issue #134: enabled data skills and personas

- Tested implementation: `62c54d65d4d44cef52a2c6d11587b033b828401e`.
- Integrated main: `6d11d8191ced3d77188710e9e58bd3574137a85f`; prior PR148 head: `1e0083f80c0ee8e7ed48eff4f42c6ab2e2b4a0bc`.
- APK: not generated; SHA-256: not applicable. No writer device/emulator access.

## Behavior

Enabled data archives supply validated personas and a deterministic skill metadata prefix capped at 8,000 characters. The first-party READ tool `host/skill_read` loads full same-skill Markdown/text documents on demand, rechecks lifecycle/digest/path boundaries and uses the existing BlobStore for long results. Skill documents remain data, with no script execution. Persona selection persists for new runs, retains the custom system prompt, and clears an unavailable selection with bilingual feedback.

This RETURN repair integrates main's image-input work without dropping either feature. Application/ChatSessionOwner wiring retains prompt snapshots, the media-aware model target and the image validator. A real loopback HTTP test installs a data ZIP and checks that skill metadata, persona, custom prompt and image payload coexist; disabling the bundle removes its prompt contribution and reports fallback while image input remains intact. Image rejection and session deletion checks remain.

## Validation bound to the implementation

`python scripts/preflight.py` completed successfully with `--configuration-cache --configuration-cache-problems=fail` and release environment variables cleared. The index tree was recorded before testing and matches the implementation commit; all tracked worktree SHA-256 values remained unchanged after testing.

- agent-core: 590 tests, 589 passed, 1 platform skip, 0 failures/errors. These completed during the first preflight in this round. Only missing app-test imports changed afterward; final Gradle correctly reused the unchanged core task as UP-TO-DATE.
- app: 675 tests, 674 passed, 1 platform skip, 0 failures/errors, fresh final-run XML.
- Decisive suites within app: UserImageChatTest 3/3; ChatPromptIntegrationTest 1/1; PluginPromptDataTest 6/6.
- lintDebug, 495 English/Chinese resource keys, storage fixture source verification and compilation: passed.
- Final preflight: 174.70 seconds, configuration cache reused. First attempt: 190.31 seconds; failed app test compilation due to missing imports for existing readSkill/promptData extension functions. Imports corrected, original failure preserved outside Git, no assertion or timeout relaxed.
- Platform skips: SummaryWorkspaceTest link/parent traversal and PluginUpdatesTest executable-bit snapshot. Skips are not passes.
- Protected files unchanged; issue134 summary unchanged by writer. The merge carries main's existing issue133 summary byte-for-byte without creating new evidence for that issue.

Evidence lives under `event-runs/12045acb-7fca-45aa-ba45-c9c6cf04a152/validation` and `validation-final` (command, logs, XML, tested tree/worktree hashes). The pre-merge identity error and original conflict output are also retained in the run record/logs. No raw evidence is committed.

## Remaining verification

The writer only created the local implementation commit and publication handoff. New published-head CI, independent review and prescribed final dedicated-emulator regression remain the existing reviewer/controller's responsibility. Prior-head CI success is not transferred to this implementation. No APK, live model endpoint, API33 runtime, Release/R8 or personal-device acceptance was performed.

Visual unverified: no dedicated writer rendering surface available. Pending reviewer scenarios are English/Chinese persona selection and unavailable notice, long names/ids, loading/empty/error/retry/disabled/selected states, small-screen and large-font readability, and light/dark appearance. Compilation and resource checks do not establish visual acceptance.

Original reference: https://github.com/openai/codex/blob/main/codex-rs/ext/skills/src/render.rs (bounded deterministic skill metadata; no source copied).
