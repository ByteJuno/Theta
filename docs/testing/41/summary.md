# Issue 41 verification

Implementation: `527ca2112a92981cdc0bb8bba1726b3146279905` on base
`8b8edc5ba3b66345feea8e6f18550420eda83b1d`. Debug APK SHA-256:
`502056e0f8c042752bbf0fdba8ab2f87759995e75fae4d557095048e67daf159`.

Hook publication and disable now record completion inside the IO operation. UI
cancellation propagates without replacing an already completed result. An action
cancelled before completion leaves the scope-wait state and displays a bilingual
cancellation message. Action tickets prevent an old cancellation or completion
from overwriting a later action or framework disconnect. Both action launch sites
enter cleanup before dispatch, including an already cancelled UI scope. Scope
timeouts and actual operation failures retain failure reporting.

On Windows/JDK 25, all 37 targeted JVM tests passed (zero failures, errors or skips):
10 HookActionStatusTest, 18 HookHostLifecycleTest and 9 HookScopeReuseTest. Controlled
dispatcher queues exercise real `withContext` cancellation on the return to UI
after publication/disable completion, cancellation during scope approval, late
approval, cancellation before IO dispatch, and an already cancelled UI scope.
Additional cases cover newer disable, prior publication, disconnect, actual
failure and scope timeout. The production status component is exercised; tests
do not execute Android framework IPC or an actual remote publication.

The final validation command ran `:app:testDebugUnitTest` with those three class
filters, `:app:assembleDebug`, and `:app:lintDebug`, using
`--configuration-cache --configuration-cache-problems=fail`. It completed in
72.87 seconds and reused the configuration cache. Lint reported 0 errors,
28 warnings and 1 hint; the bilingual resource guard passed. Raw logs, XML,
tested-source hashes and the matching APK are retained outside Git. No baseline
red run was performed; earlier local validation of the initial implementation
passed 36 cases before the already-cancelled-entry case was added.

The cancellation handling follows the upstream coroutine implementation's
[prompt cancellation guarantee](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Builders.common.kt):
a result can be discarded when returning across dispatchers even after the
operation has completed. Completion must therefore be recorded before that return.

Visual verification was not performed: no dedicated writer render/device entry
was established. Still to inspect on the matching APK: Chinese/English cancelled
state and retained success state on a small screen, large font and both themes;
leave/reopen the panel during approval and immediately after publication/disable.
No phone or other task's emulator was accessed. Actual framework behavior, Android
API 33 runtime, Release/R8 and final-head CI were not verified locally. Independent
review and prescribed final regression remain required before reviewer merge.
