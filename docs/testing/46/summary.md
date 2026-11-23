# Issue 46 verification

Implementation `762f657a67f8861f752272d6ef34f4316bdf1ad8`, based on
`c7ca8f493dfbfce85e701f5c6728cb7552caed1a`. Debug APK SHA-256:
`54fdbe82d97ac1c362ad9f32c9f683fb77c929dc7ddf90347fe6db9b6b563775`.

A generation-only agent turn backed by SQLite now retains its full pending step
when a tool claim is refused by an execution/exit/resource/restart admission gate.
The existing saved-step continuation rechecks the ledger using the original call
bindings. Submitted calls are observed, never submitted again. An early retry
leaves the step pending; actual exit can release timeout/cancellation restrictions.
Independent restart causes and unresolved effects/resources still refuse claims.
Stopping the task cancels the remaining calls using the existing reconciliation.
Memory-only and server-executing model modes retain their previous interruption
behavior. There is no automatic tool or model replay and no new UI control.

On Windows/JDK 25, 53 applicable JVM cases passed with no failures, errors or skips:
27 AgentRunLoopTest and 16 RunRecoveryTest cases in the first green run, plus
8 selected ChatSessionOwnerTest and 2 HostToolExecutionServiceTest cases against
final source. The three new chat cases overlap between runs and are counted once;
core production/tests were unchanged after the first green run. This is an
aggregate, not one 53-case invocation. Both invocations used
`--configuration-cache --configuration-cache-problems=fail` and stored the cache
(65.44 and 61.50 seconds). The final invocation also built Debug.

The real ChatSessionOwner -> AgentRunLoop -> host bridge -> SQLite path covers
both timeout and invocation cancellation, early continuation, actual exit,
independent sticky restart causes, unchanged original terminal result/attempt ID,
ordered result pairing, no repeated model generation and stopping pending work.
The executor/host case retains a running parallel sibling across two capacity
pauses, then completes and repeats continuation without a duplicate claim.
Existing targeted cases cover approval cold-open, failed approval writes,
late-exit recovery, renewed unknown-effect approval, owner cancellation and
normal-result completion handlers. Before the fix, all three new chat cases
failed on the missing pending step. Original failing logs/XML and both passing
runs, the matching APK and source hashes are retained outside Git.

The actual-exit boundary follows the existing host design. kotlinx.coroutines
1.10.2 JobSupport waits for children before finalizing completion:
https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/JobSupport.kt#L935
A logical timeout/cancellation remains insufficient proof of exit.

Visual rendering was not verified. The existing saved-step/stop controls and
localized strings are reused without layout/resource changes; pending scenarios
are pause, early continue, actual-exit continue and stop on small screens/large
fonts and existing light/dark themes. No device, live provider, external plugin,
Android API 33 runtime or Release/R8 execution was performed. No API scan was run
locally this round. Final-head CI, independent review and prescribed emulator
regression remain required before reviewer merge. Protected files are unchanged.
