# Issue 46 verification

The writer verification below applies to `e81a03d`; the reviewer correction and
its separate source validation are recorded at the end.

Implementation `e81a03d9ebf1791e9469426888d02b423f528874`, based on reviewed
head `a609bff6afac67dd05748337b40940f4e52c4abd`. Debug APK SHA-256:
`fff384d87d5fa5a9737f4308c0771833d43f39fdb7824f42b5a13486afb156b5`.

Saved generation-only chat steps retain their original calls after an admission
pause. A submitted parallel sibling keeps the original chat execution identity,
status observation and foreground admission callback until it settles. Internal
approval returns to that original instance. Remaining calls require explicit
saved-step continuation; neither tool claims nor model responses are replayed.
The ordinary run() entry retains its previous interruption/reconciliation
behavior; durable saved-step suspension is confined to the chat-task entry.

Stopping a saved step cancels through the host and atomically appends the original
assistant plus ledger-derived ordered results. This uses logical terminal results
without requiring actual exit. Original outcomes and attempt IDs, occupied leases,
UNKNOWN effects and isolation remain intact; the host observer alone records exit
and releases resources. Stopping during retained-sibling observation uses the same
reconciliation under the session gate. Existing localized stop feedback is reused.

Validation against this source: 70 applicable JVM cases passed on Windows and
through the existing osaka-lab /opt/homelab-report/theta-gpu-test entry. The aggregate
is 46 unchanged agent-core cases (29 AgentRunLoopTest, 16 RunRecoveryTest, one
ContextPolicyTest serial barrier case) plus 24 final-source ChatSessionOwnerTest
cases. Final app tests supersede earlier overlapping app runs. No failures or skips
in the Windows applicable aggregate. GPU summaries report total/failed only:
69/0 in the first valid run, then 24/0 final app cases; GPU skip/error counts and
cache-store/reuse receipts are not independently exposed by that entry.

All invocations used --configuration-cache --configuration-cache-problems=fail.
Local cache storage was confirmed. Local first affected run took 63.09 s (two new
test-fixture failures); final app tests plus Debug took 56.49 s and passed. The
unchanged 46 core cases passed in that first affected run. The two corrected stop
fixtures separately passed in 39.67 s before final verification. The Debug APK
above was built from final production source; no device installation occurred.

Four new formal cases cover timeout and cancellation followed by stop before exit,
stopping a retained running sibling, and retained internal approval with delayed
foreground acknowledgement. Assertions cover original bindings/attempts/results,
ordered persisted results after session reopen, occupied capacity before exit,
no second model request and no duplicate tool submission. Related existing cases
cover saved-step retries, sticky restart gates, cold approvals, approval storage
failure, admission failures/withdrawals and recovery. The unchanged serial
ContextPolicyTest reproduced the failed CI behavior before the chat-only scope fix
and passed afterward. Three initial new app cases failed on original production.
The reviewer GPU probe failures are preserved outside Git; no probe was committed.

Other retained failures: the first new tests referenced an internal codec and were
corrected to compare public result fields; two stop fixtures tried to open an
already-open session and were corrected to use the normal session selection path.
The first GPU invocation executed zero tests (exit 127): transferred Windows CRLF
gradlew could not launch. The disposable remote copy was replaced with exact
HEAD:gradlew LF bytes, preserving the local protected wrapper. Subsequent GPU runs
passed. Raw logs/XML, source digests and matching APKs remain outside Git.

Visual rendering is unverified: pending scenarios are retained parallel execution,
internal approval, delayed/rejected foreground admission, stop before actual exit,
and explicit continuation in Chinese/English on small screens/large fonts and the
existing light/dark themes. No layout/resource change was made. No phone, other
task emulator, live provider, external plugin, Android API 33 runtime or Release/R8
execution was performed. No local API scan/lint rerun was needed for these private
chat changes; the final build's bilingual resource guard passed. Final-head CI,
independent review and prescribed regression remain required before reviewer merge.
Protected files are unchanged. This is not release/device acceptance.

## Reviewer round 2 (one previous return)

Reviewed PR head `4e246754029ea60eda0ba13e293970a0e49ef054`. Its production and test
sources match the writer's implementation commit above; the intervening commit
changes only this summary. The fixed API scan supplied to the reviewer passed
with minSdk 33 and zero hits.

A targeted GPU probe exposed stopping an explicit saved-step continuation while
its initial foreground admission was pending: stop feedback appeared, but the
second call remained READY and the pending assistant had no committed tool result
message. The first cancelled instance still held its lease; there was no second
model request or duplicate tool execution. The initial probe executed one case
and failed its pending-step assertion (Gradle reported BUILD FAILED in 26 s).
This failure remains in the external review transcript; no raw log or probe is
committed.

Correction `0fd3ecbfb6ac0fb36a0d84d2e6f06a4da7dcbee1` reconciles any remaining saved
step at the run cancellation boundary, including cancellation before executeChat.
Status refresh and late-exit observation happen after active-run cleanup, so stop
cleanup does not request foreground admission again. The new formal regression
also checks ordered history after session reopen, original result/instance and
isolation preservation, an unclaimed cancelled sibling, one request/execution,
and an occupied slot before actual exit.

The existing GPU entry ran `:app:testDebugUnitTest` with filters
`*SavedStepAdmissionStopProbe*`, `*ChatSessionOwnerTest.stopping*`, and
`*ChatSessionOwnerTest.retainedSiblingApprovalKeepsExecutionIdentityAndForegroundAdmission`:
6 cases, 0 failures, GRADLE_EXIT=0. This includes the corrected probe and five
formal cases. The probe now observes no pending step, two CANCELLED records,
USER/ASSISTANT/TOOL history, one request/execution, and one still-occupied slot.
An earlier invocation with explicit configuration-cache flags was rejected by
the entry before tests ran; the successful invocation used its accepted task and
filter arguments. No cache receipt or additional duration was exposed.

No APK was built for the reviewer correction. The writer's APK hash above does
not identify the corrected source. Visual rendering remains unverified. Final
commit CI and the controller's prescribed emulator regression remain separate.
