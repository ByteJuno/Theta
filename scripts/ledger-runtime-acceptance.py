#!/usr/bin/env python3
"""Standalone instrumentation of the unchanged R8 APK (no host test keep-all rules).

python -I scripts/ledger-runtime-acceptance.py --sdk SDK --jdk JDK --serial emulator-5588 \
  --avd theta_issue17_api36 --apk app/build/outputs/apk/release/app-release.apk \
  --mapping app/build/outputs/mapping/release/mapping.txt --keystore DEBUG_KEYSTORE --output NEW_DIR

Destructive ONLY to emulator-5588 / theta_issue17_api36 host app data. Never selects a
physical device or emulator-5582. Signature must match host (local debug-signed Release).
The only current consumer is this instrumentation, not a production UI/model path.
Every guard and command executes under python -O as well as normal Python.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zipfile

PACKAGE = 'io.github.bytejuno.thetacore'
TEST_PACKAGE = 'io.github.bytejuno.ledgeracceptance'


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def validate_device(serial, avd, device):
    require(serial == 'emulator-5588', 'Only owned emulator-5588 is allowed; emulator-5582 and physical devices are reserved')
    require(avd == 'theta_issue17_api36', 'Use the issue17-owned AVD')
    require(device('get-serialno').strip() == serial, 'Device serial mismatch')
    require(device('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Device is not an emulator')
    name = device('emu', 'avd', 'name').splitlines()
    require(bool(name) and name[0].strip() == avd, 'AVD name mismatch')


def clear_host_data(device):
    # Never put a side-effectful command inside an assert: -O would skip the command.
    response = device('shell', 'pm', 'clear', PACKAGE)
    require(response.strip() == 'Success', 'pm clear failed: ' + response)


def require_phase(text, phase):
    require('ledger=PASS ' + phase in text and 'INSTRUMENTATION_CODE: -1' in text
            and 'ledger=FAIL' not in text, 'Instrumentation failed: ' + text)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'jdk', 'serial', 'avd', 'apk', 'mapping', 'keystore', 'output'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    output = Path(a.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    sdk, jdk = Path(a.sdk), Path(a.jdk)
    tools = sorted((sdk / 'build-tools').iterdir(), key=lambda x: tuple(map(int, x.name.split('.'))))[-1]
    platforms = [jar for jar in (sdk / 'platforms').glob('android-*/android.jar')
                 if re.fullmatch(r'android-\d+(?:\.\d+)*', jar.parent.name)]
    android = max(platforms, key=lambda jar: tuple(map(int, jar.parent.name.removeprefix('android-').split('.'))))
    env = dict(os.environ, JAVA_HOME=str(jdk))
    env['PATH'] = str(jdk / 'bin') + os.pathsep + env.get('PATH', '')
    report = {'phases': [], 'serial': a.serial, 'avd': a.avd, 'coverage': 'explicit in-process API; no UI/model consumer'}
    counter = 0

    def run(command, timeout=180):
        nonlocal counter
        counter += 1
        result = subprocess.run([str(s) for s in command], env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
        (output / f'{counter:02d}.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        require(result.returncode == 0, f'command {counter} failed: {result.stdout}{result.stderr}')
        return result.stdout

    adb = [sdk / 'platform-tools/adb', '-s', a.serial]
    def device(*args): return run([*adb, *args])
    try:
        validate_device(a.serial, a.avd, device)
        report['api'] = device('shell', 'getprop', 'ro.build.version.sdk').strip()
        for key in ('apk', 'mapping'):
            report[key + '_sha256'] = hashlib.sha256(Path(getattr(a, key)).read_bytes()).hexdigest()
        mapping = Path(a.mapping).read_text(encoding='utf-8')
        match = re.search(r'^io\.github\.bytejuno\.thetacore\.ThetaApplication -> ([^:]+):$', mapping, re.M)
        require(match is not None, 'Application missing from this build mapping')
        application = match.group(1)
        require('hostToolRequestForInstrumentation(java.lang.String)' in mapping, 'Instrumentation adapter not retained')
        require('hostToolRequest(java.lang.String,kotlin.coroutines.Continuation)' in mapping, 'Suspending runtime API not retained')
        manifest = output / 'AndroidManifest.xml'
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{TEST_PACKAGE}">
<uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36"/>
<application android:label="Ledger runtime acceptance" android:hasCode="true"/>
<instrumentation android:name="{TEST_PACKAGE}.LedgerInstrumentation" android:targetPackage="{PACKAGE}"/>
</manifest>''', encoding='utf-8')
        classes = output / 'classes'; classes.mkdir()
        source = Path(__file__).with_name('ledger-runtime') / 'LedgerInstrumentation.java'
        report['instrumentation_source_sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
        report['runner_source_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        run([jdk / 'bin/javac', '-source', '8', '-target', '8', '-cp', android, '-d', classes, source])
        jar = output / 'instrumentation.jar'
        run([jdk / 'bin/jar', 'cf', jar, '-C', classes, '.'])
        dex = output / 'dex'; dex.mkdir()
        suffix = '.bat' if os.name == 'nt' else ''
        run([tools / ('d8' + suffix), '--lib', android, '--min-api', '33', '--output', dex, jar])
        unsigned = output / 'unsigned.apk'
        run([tools / 'aapt2', 'link', '-I', android, '--manifest', manifest, '-o', unsigned])
        with zipfile.ZipFile(unsigned, 'a') as archive:
            archive.write(dex / 'classes.dex', 'classes.dex')
        aligned = output / 'aligned.apk'
        run([tools / 'zipalign', '-f', '4', unsigned, aligned])
        test = output / 'instrumentation.apk'
        run([tools / ('apksigner' + suffix), 'sign', '--ks', a.keystore, '--ks-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--key-pass', 'pass:android', '--out', test, aligned])
        device('install', '-r', a.apk)
        device('install', '-r', test)
        clear_host_data(device)
        # Observe completion in the real launcher PID BEFORE stopping it. This log is emitted
        # only after durable open, cold recovery and clock registration; no request/probe runs.
        device('logcat', '-c')
        launch = device('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        require('Status: ok' in launch, 'Launcher failed: ' + launch)
        pid = device('shell', 'pidof', PACKAGE).strip()
        require(re.fullmatch(r'\d+', pid) is not None, 'Launcher process missing/ambiguous')
        deadline = time.monotonic() + 45
        while True:
            log = device('logcat', '-d', '--pid=' + pid, '-s', 'ThetaHostTools:I', '*:S')
            require('Host tools unavailable' not in log, 'Launcher eager startup failed: ' + log)
            if 'Host tools initialized' in log:
                break
            require(time.monotonic() < deadline, 'Launcher did not complete eager startup')
            require(device('shell', 'pidof', PACKAGE).strip() == pid, 'Launcher exited during startup')
            time.sleep(0.5)
        report['launcher'] = {'pid': pid, 'eager_initialization': True, 'log': log}
        report['phases'].append('launcher-eager')
        print('PASS launcher-eager', flush=True)
        for phase in ('execute', 'recover', 'ready-create', 'ready-restart', 'storage', 'startup-failure', 'recover'):
            device('shell', 'am', 'force-stop', PACKAGE)
            text = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'phase', phase,
                          '-e', 'applicationClass', application,
                          TEST_PACKAGE + '/.LedgerInstrumentation')
            require_phase(text, phase)
            report['phases'].append(phase)
            print('PASS ' + phase, flush=True)
        for key in ('apk', 'mapping'):
            require(report[key + '_sha256'] == hashlib.sha256(Path(getattr(a, key)).read_bytes()).hexdigest(), key + ' changed during acceptance')
        report['passed'] = True
    except BaseException as error:
        report['passed'] = False
        report['error'] = str(error)
        raise
    finally:
        (output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
