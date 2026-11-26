# Issue 136 validation

Implementation commit: `53b4679991565d1c767ea42215d91a226123e1fc`.
Branch: `auto/136-mcp-http`; PR #149; verified RETURN `IC_kwDOVkrS_s8AAAABrAPXyw` on remote head `18238649f98f8011fe6f699ba7a61c945cea2803`.

Connection disable/delete now revokes the directory and cancels live host invocations under the definition gate, removes input waiters, rejects late answers, and closes in-flight HTTP responses. All session generations share an irreversible request scope so old clients cannot continue after revocation. The existing ledger tracks actual worker exit and retains returned evidence or UNKNOWN for sent calls; no call is replayed.

All credential catalog access paths now revoke enabled connections and publish the classified failure state. Disable/delete revoke before reading credentials. Transient errors preserve ciphertext/keys and do not authorize replacement. The original schema retains validated x-mcp-header annotations through registration, argument validation and real HTTP header mapping; illegal names/locations remain rejected.

Checks on this source (worktree hashes retained outside Git; committed source verified equal):
- Full `python scripts/preflight.py` passed in 149.63s: agent-core 603 tests = 602 passed + 1 platform skip; app 687 tests = 686 passed + 1 platform skip; no failures/errors.
- lintDebug, storage-fixture source/compile checks and 525 English/Chinese resource keys passed; strict configuration cache reused with problems=fail.
- Local targeted tests: 29/29 passed in 108.11s. Designated `/opt/homelab-report/theta-gpu-test`: 29/29 passed in 56.90s, same source archive and unchanged LF wrapper blob, 256 MiB test heap.
- New end-to-end regressions cover disable and delete during host input (including an older refreshed session), actual streamed-response disconnect and UNKNOWN effect, every catalog access failure with two active connections, and original-schema validation plus actual header emission.
- No protected files, dependencies, probe files or raw evidence committed. Existing untracked scripts cache and two stashes preserved.

Failure history: prior reviewer reproduced all three defects on 18238649; verdict and old-head CI failure log retained in this round output. That CI failed `PluginToolBridgeTest.attachCatalogAndOperationLockDoNotHangOnNonCooperativeMetadataTimeout`; the test was not changed, and passed in this round local targeted/full and GPU targeted runs. This does not establish that CI cannot recur. Earlier round failures remain in their existing output directories. Initial text-read encoding/output issue corrected; diagnostic jcmd was attempted after test process exit and supplies no test evidence.

Limits: no APK generated (APK SHA unavailable); no phone, device, emulator or visual verification. Existing input form, progress and credential recovery UI still need independent reviewer rendering/interaction acceptance. API33 runtime, R8, physical power loss and third-party MCP interoperability unverified. OAuth/sampling/URL elicitation remain deferred as documented previously. New-head GitHub CI, independent review and prescribed final emulator regression remain required. Writer did not push, update/create PR, merge, or create scheduling.
