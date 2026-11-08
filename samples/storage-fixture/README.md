# Independent S1 DATA storage fixture

Disposable APK: `io.github.bytejuno.thetacore.sample.storagefixture`. No host dependency,
custom Application, shared UID, permissions, provider, network, external payload, or host
storage access. All writes are beneath this APK's own `filesDir`. Do not install over or
replace candidate9. No production source or host UI seam was added.

## Build owner only (not executed during implementation)

From the frozen source root, using the project's existing Gradle/JDK/SDK setup:

```sh
./gradlew :samples:storage-fixture:verifyStorageFixtureSources :samples:storage-fixture:testDebugUnitTest :samples:storage-fixture:assembleDebug
```

Windows cmd equivalent uses `gradlew.bat` with the same three tasks. Output:
`samples/storage-fixture/build/outputs/apk/debug/storage-fixture-debug.apk`.
No `:app` build task is needed. AGP built-in Kotlin compiles the exact allowlisted
production files by source inclusion. The only library is the production JSON parser.
`verifyStorageFixtureSources` checks inclusion/isolation before preBuild. Unit tests
check the deterministic valid ZIP, real digest rejection, all five hook snapshots in
both enabled states, MOVED semantics and rejection of same-process/no-marker recovery.
Those snapshots are not Android kill evidence.

## Sole device operator only

Only the designated device operator should run these commands. Set `SERIAL` to the
already assigned device serial; the agent identifier is not an adb serial. Install this
unique package only, then run the stdlib Python driver from the source root:

```sh
adb -s "$SERIAL" install -r samples/storage-fixture/build/outputs/apk/debug/storage-fixture-debug.apk
python -I samples/storage-fixture/run-device.py --serial "$SERIAL" --output /absolute/new/storage-fixture-evidence
```

Use a new output directory each time. Optional `--adb /absolute/path/to/adb`.
The driver never installs, kills, force-stops or accesses the host application. It uses
`run-as` only on this debug fixture to collect its fsynced JSON. It records start outputs,
unique request IDs, 10 mismatch outcomes, and 10 actual self-kill/fresh-process recovery
cases (five stages times baseline enabled/disabled). It waits for fixture process exit
before recovery; a timeout is a failure, not permission to force-stop and claim a pass.
If any exception occurs there is no PASS summary. Keep the partial evidence.

### Manual Activity interface

```sh
COMPONENT=io.github.bytejuno.thetacore.sample.storagefixture/io.github.bytejuno.thetacore.plugin.StorageFixtureActivity
adb -s "$SERIAL" shell am start -W -n "$COMPONENT" --es command PREPARE --es stage MOVED --ez enabled false --es requestId manual_prepare
adb -s "$SERIAL" shell am start -W -n "$COMPONENT" --es command MISMATCH --es stage MOVED --ez enabled false --es requestId manual_mismatch
adb -s "$SERIAL" shell am start -W -n "$COMPONENT" --es command CRASH --es stage MOVED --ez enabled false --es requestId manual_crash
# Wait for this fixture's process to disappear; do not issue force-stop.
adb -s "$SERIAL" shell pidof io.github.bytejuno.thetacore.sample.storagefixture
adb -s "$SERIAL" shell am start -W -n "$COMPONENT" --es command RECOVER --es stage MOVED --ez enabled false --es requestId manual_recover
adb -s "$SERIAL" shell am start -W -n "$COMPONENT" --es command REPORT --es stage MOVED --ez enabled false --es record RECOVER
adb -s "$SERIAL" logcat -d -s S1StorageFixture:I '*:S'
```

Wait for each command's matching `requestId` JSON before continuing; `am start -W` waits
for Activity launch, not background work. `REPORT` displays the durable JSON in the UI
and logcat without triggering restore; `record` accepts PREPARE/MISMATCH/CRASH/RECOVER/ERROR.
Read ERROR after failures. PREPARE resets only the selected fixture scenario, never the host.
Evidence files are named `<STAGE>-<enabled|disabled>-<COMMAND>.json`. A previous successful
file may remain after a failed run; require current requestId/runId, not just status.

## What is tested

- A generated local ZIP with root manifest and inert Markdown, under 4096 bytes. Before
  mismatch testing, the exact bytes pass production `validatePluginDataZip` and
  `stageData(..., actualSha256)`; that positive stage is discarded.
- An otherwise well-formed expected SHA with exactly one changed hex digit must produce
  `PluginPackageException: Data ZIP index digest mismatch`, no v2 publication, no stage
  residue, and no selection/enable/byte changes. This is the trusted-index digest input,
  not the separate user confirmation digest. No author-trust inference is claimed.
- Production `PluginDataCatalog.install` commits v1 and the requested enabled state.
- Production v2 publication calls the existing boundary hook. The fixture fsyncs marker
  and JSON evidence then calls only `Process.killProcess(Process.myPid())`. No thrown
  exception simulates process death on Android, and no production finally cleanup runs.
- Fresh process has a new UUID token (PID also recorded; PID reuse is allowed). It invokes
  production `restoreInventory`, validates exact baseline ZIP bytes and durable state,
  checks no errors, no intent/temporary directory, and removed lost staging. Enabled
  baseline content is also read through production `catalog.content`.

| Cutpoint | v2 after recovery | Selected version |
| --- | --- | --- |
| INTENT | absent | v1, previous enabled state |
| TEMP_CREATED | absent | v1, previous enabled state |
| PAYLOAD_READY | absent | v1, previous enabled state |
| PARENT_READY | absent | v1, previous enabled state |
| MOVED | preserved, byte-exact, **published but not selected** | v1, previous enabled state |

MOVED is invoked after atomic rename and before `PluginDataCatalog.save`; its callback
runs before publication intent cleanup. It is not an unpublished temporary version and
must not be deleted or automatically activated. Recovery leaves the successful evidence
unchanged (no opportunistic v3 install or enabling a disabled selection).

## Explicit limits

DATA Android storage integration only. The shared production publication file requires
compiling `PluginTrustStore`, `PluginSignatureVerifier` and `PluginManifest`; their
presence is not CODE coverage. No fake signature verifier is used. CODE install/runtime,
platform APK signature acceptance, host PluginManager integration, initial install with
no committed predecessor, physical power-loss/directory durability and hostile same-UID
modification remain outside this fixture. CODE needs signed versioned APK inputs and a
separate code-state/runtime plan; this module does not claim that gap is closed.

Implementation-time status: no Gradle build/unit test, installation, or adb command has
been run by the implementation agent. Build owner and sole device operator must retain
actual outputs before recording device PASS.
