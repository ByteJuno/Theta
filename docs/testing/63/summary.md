# Issue 63 — bounded runtime history

Implementation source: `edb228deaa4e719bd320cf5b94cd49038b68e2bf`; base main `0c8e97cd89dec865b6166bf2c207bef8bbf95a7c`.
APK: not generated; SHA-256: not applicable.

The SQLite runtime snapshot checks the immutable hash chain and unique call index one transaction at a time, then exposes an on-demand list. AgentRunLoop, checkpoint matching and source digests no longer materialize the entire conversation. UI `page`/`recent` and explicit full-export `snapshot` keep their existing contracts. A request view admits at most 4096 messages / 2,097,152 charged UTF-16 characters (including structure, bindings and opaque replay) by default, independently of token admission. Ordinary prior-history summarization uses the existing opt-in model; without it, exceeding the view budget saves a budget pause, never silently truncates input. Active tool pairs and raw history remain intact.

Legacy atomic batches above 8 MiB serialized size are refused before runtime JSON decoding and yield an explicit pause; raw paging/export remains available. Checkpoint record aggregation is limited to 4096 records / 8 MiB serialized data, and intermediate summary retention to 4096 items / 2 Mi characters. These limits do not erase source/ledger records. This is not a general bound on all application memory: the existing invocation ledger, provider response buffers, explicit full exports and UI page retention have their own costs. Runtime chain verification is linear I/O; random backward list access restarts a scan. No schema migration, protected-document edit or current PR98 chat change.

## Validation

Final source: 132/132 JUnit cases, zero failures/errors/skips (131 behavioral cases plus one classpath-export utility). Classes: AgentRunLoopTest 24, BoundedHistoryTest 6, ContextPolicyTest 64, HistoryChunkBoundsTest 3, HistoryLedgerConsistencyTest 7, RunRecoveryTest 16, SqliteRunHistoryTest 9, UnknownContextMergeTest 3. Strict `--configuration-cache --configuration-cache-problems=fail`; cache stored, 43.85 seconds. New checks cover immutable snapshot boundaries/digests, complete tool pairs and raw paging, no-provider-submission budget pause, successful summary view, pre-decode huge-batch refusal, and corrupt old history rejection. Existing 64 context cases retain summary/reconciliation/approval/unknown-effect safeguards. `git diff --check` passed.

Separate Java 25 Windows processes with `-Xmx96m`, one 4096-ASCII-character message per transaction, SQLite WAL FULL, no OS-cache flush. Each opens the stored session, runs the production loop without a summary model, verifies durable BUDGET pause and zero model requests, then reads all raw pages. Heap sampled through MemoryMXBean after SQL chunk reads; not continuous peak/RSS, Android, external API, or device evidence.

| Messages | Raw text chars | JVM max heap bytes | Baseline heap bytes | Sampled peak heap bytes | Post-GC retained bytes | Raw messages recovered | End-to-end seconds |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1000 | 4096000 | 100663296 | 2506664 | 33418008 | 2922640 | 1000 | 4.053 |
| 10000 | 40960000 | 100663296 | 2508000 | 33574144 | 2918248 | 10000 | 37.157 |

Timing includes fixture creation, run and paging. Measurements establish bounded growth for this tested no-tool/no-summary pause scenario; successful summary continuation is a separate small deterministic test, not a measured 10k-message remote-model run. Limits for oversized batches or ledger aggregates conservatively pause and may require host action. No UI change/visual test, APK, API33 runtime, R8, Android process-kill, physical power-loss or personal-device test. Final-head independent review, prescribed regression and CI remain pending. Writer does not merge.

Raw logs/XML/hashes and pre-final measurements remain outside Git under `event-runs/30ed77f0-966c-4934-a5df-1daf1acdfebf` and `validation-issue11/issue63-30ed77f0-*`. Initial terminal encoding/truncation, unavailable rg and one missing source-filename lookup are preserved in the worker log; Gradle validation attempts all passed.
