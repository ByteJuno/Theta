# Issue 45 verification

Implementation `89afed210d796273413bc51e4f05c21e1de774d3`, based on
`8b8edc5ba3b66345feea8e6f18550420eda83b1d`. Debug APK SHA-256:
`c5e13ec99455595bc445a93fa3856a1627775f5d4a0cc8834b867ba41aee56f7`.

Cancelled and timed-out calls now use the same per-attempt grace restriction.
The ledger accepts and persists either terminal state with release-on-exit set;
the host monitor sets it after grace expiry. Only actual exit releases this
restriction. Logical results, late-return evidence, unknown-effect isolation,
resource leases, other outstanding attempts and independent restart causes keep
their existing rules. Legacy records without the flag remain conservatively
sticky. No protected design/protocol, lifecycle loader or UI file was changed.

On Windows/JDK 25, all 47 targeted JVM tests passed with zero failures, errors or
skips: 33 InvocationLedgerTest, 9 selected HostToolExecutionServiceTest and
5 HostScreenToolTest. The same invocation assembled Debug, using
`--configuration-cache --configuration-cache-problems=fail`; it reused the cache
and completed in 56.16 seconds.

The selected host cases exercise grace-expired cancellation and timeout, mixed
attempts in either exit order, original tool object reuse, cooperative exit,
blocked cancellation handlers, durable cancellation racing SDK success, revoked
definitions, independent sticky causes before/after grace, failed exit writes,
and late evidence while a child still prevents exit. Mixed deadline tests use the
existing manual clock so cancellation occurs before the deadline; the existing
real-timer timeout case remains. Assertions retain original terminal results,
resource isolation, occupied slots and evidence. Ledger cases also check a
different plugin owner can claim while a sibling is blocked, WRITE/UNKNOWN
preservation, old/new JSON round trips, SQLite reopen and cold recovery decisions.

Two focused runs against the original production implementation reproduced the
defects: the ledger rejected a cancelled release-on-exit mark, and the host kept
a blocked-handler cancellation sticky. The first green attempt passed 32 of 33
ledger cases; the new plugin-isolation fixture hit the existing unknown task-chain
barrier before the target restart gate. Giving that sibling an independent chain
corrected the fixture; the final 47-case invocation above passed. Raw failures,
success logs, XML, matching APK and tested-source hashes remain outside Git.

The existing actual-exit boundary is consistent with kotlinx.coroutines 1.10.2
[JobSupport](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/JobSupport.kt#L930):
cancelling notifies children before waiting for their completion. Cancellation
and a reported return therefore remain insufficient evidence of worker exit.

No device, external plugin package, Android API 33 runtime, Release/R8 or live
provider was exercised. The current host catalog only exposes built-in tools;
plugin owner isolation is covered at the ledger boundary, not by loading a dex
plugin. UI rendering is not applicable to this backend-only change and was not
performed. Final-head CI, independent review and prescribed emulator regression
remain the reviewer's responsibility before merge.
