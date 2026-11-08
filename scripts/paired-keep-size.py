#!/usr/bin/env python3
"""Nonphysical paired release-size measurement; never changes the source tree.

prepare freezes two isolated copies without Gradle; run builds a prepared pair.
all does both (CI). Output must be NEW for prepare/all. Shared Gradle caches use
normal Gradle locking; no cache copying or lock removal. Comparison APK is NOT
production/plugin compatible and must never be distributed as a release.
"""
import argparse
import datetime as dt
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import traceback
import zipfile

PACKAGES = ('kotlin', 'kotlinx.coroutines', 'kotlinx.serialization',
            'androidx.compose', 'androidx.collection', 'androidx.lifecycle', 'androidx.savedstate')
RULES = tuple(f'-keep public class {p}.** {{ public protected *; }}' for p in PACKAGES)
KEEP = 'app/proguard-rules.pro'
SKIP_DIRS = {'.git', '.gradle', '.kotlin', 'build', '__pycache__', '.idea', '.venv', 'node_modules'}
SKIP_FILES = {'local.properties', '.DS_Store'}
RELEASE_ENV = ('THETA_RELEASE_STORE_FILE', 'THETA_RELEASE_STORE_PASSWORD',
               'THETA_RELEASE_KEY_ALIAS', 'THETA_RELEASE_KEY_PASSWORD')


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def save(path, obj):
    path = Path(path)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(obj, indent=2, ensure_ascii=True) + '\n', encoding='utf-8')
    tmp.replace(path)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def inventory(root):
    result = {}
    for directory, dirs, files in os.walk(root):
        base = Path(directory)
        for name in dirs + files:
            require(not (base / name).is_symlink(), f'Symlink not permitted: {base / name}')
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
        for name in sorted(files):
            if name not in SKIP_FILES and not name.endswith(('.pyc', '.jks', '.keystore')):
                p = base / name
                result[p.relative_to(root).as_posix()] = sha(p)
    return result


def stripped(original):
    lines = original.decode('utf-8').splitlines(keepends=True)
    for rule in RULES:
        require(sum(line.strip() == rule for line in lines) == 1,
                'Expected exactly one shared-library rule: ' + rule)
    modified = ''.join(line for line in lines if line.strip() not in RULES).encode('utf-8')
    require(b'-keep public class io.github.bytejuno.thetacore.sdk.** { public protected *; }' in modified,
            'SDK API keep missing')
    require(b'-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {' in modified,
            'Modern Hook entry keep missing')
    # Filtering exact full lines preserves every other byte, including Hook constructor rules.
    return modified


def input_delta(reference, current):
    return {n: {'reference': reference.get(n), 'current': current.get(n)}
            for n in sorted(set(reference) | set(current)) if reference.get(n) != current.get(n)}


def prepare(args):
    source, root = args.source.resolve(), args.output.resolve()
    require(source.is_dir(), 'Source directory missing')
    require(root != source and source not in root.parents and root not in source.parents,
            'Output and source must be disjoint trees')
    root.mkdir(parents=True, exist_ok=False)
    evidence = root / 'evidence'
    evidence.mkdir()
    result = {'status': 'PREPARING', 'started': now(), 'source': str(source),
              'scope': 'nonphysical paired release size only; no device, frame, or compatibility claim',
              'execution_context': args.context, 'comparison_production_compatible': False,
              'removed_exact_rules': list(RULES), 'variants': {},
              'script_sha256': sha(Path(__file__))}
    try:
        manifest = inventory(source)
        require('gradle/wrapper/gradle-wrapper.jar' in manifest, 'Gradle wrapper missing')
        save(evidence / 'source-hashes.json', manifest)
        result['source_manifest_sha256'] = sha(evidence / 'source-hashes.json')
        if args.reference_manifest:
            reference = json.loads(args.reference_manifest.read_text(encoding='utf-8'))
            delta = input_delta(reference, manifest)
            save(evidence / 'reference-delta.json', delta)
            result['reference_manifest'] = str(args.reference_manifest.resolve())
            result['reference_manifest_sha256'] = sha(args.reference_manifest)
            result['reference_delta_count'] = len(delta)
            production = lambda n: n.startswith(('app/', 'sdk/', 'build-logic/', 'gradle/')) or n in (
                'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat')
            # Only explicit test source sets are excluded; never match arbitrary
            # paths containing 'test'. The full delta above remains evidence.
            test_only = ('app/src/test/', 'app/src/androidTest/',
                         'sdk/src/test/', 'sdk/src/androidTest/')
            result['reference_test_only_delta'] = {n: d for n, d in delta.items()
                                                   if n.startswith(test_only)}
            prod_delta = {n: d for n, d in delta.items()
                          if production(n) and not n.startswith(test_only)}
            result['reference_production_delta'] = prod_delta
            require(set(prod_delta) <= {'app/build.gradle.kts', 'settings.gradle.kts'},
                    'Unexpected production drift from reference; inspect reference-delta.json')
            result['reference_binding'] = 'runtime sources identical; current build scripts preserved, not byte-identical full tree'
        original = (source / KEEP).read_bytes()
        modified = stripped(original)
        (evidence / 'production.pro').write_bytes(original)
        (evidence / 'comparison.pro').write_bytes(modified)
        (evidence / 'keep.diff').write_text(''.join(difflib.unified_diff(
            original.decode().splitlines(True), modified.decode().splitlines(True),
            fromfile='production/' + KEEP, tofile='comparison/' + KEEP)), encoding='utf-8')
        for variant, content in (('production', original), ('comparison', modified)):
            work = root / 'work' / variant
            out = evidence / variant
            out.mkdir()
            for name in manifest:
                destination = work / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source / name, destination)
            require(inventory(work) == manifest, 'Source changed while copying ' + variant)
            (work / KEEP).write_bytes(content)
            actual = inventory(work)
            differences = input_delta(manifest, actual)
            require(set(differences) == (set() if variant == 'production' else {KEEP}),
                    'Unexpected variant differences')
            save(out / 'source-hashes.json', actual)
            result['variants'][variant] = {'work': str(work), 'source_manifest_sha256': sha(out / 'source-hashes.json'),
                                           'keep_sha256': sha(work / KEEP), 'source_delta': differences}
        require(inventory(source) == manifest, 'Source changed during preparation')
        result['source_unchanged_at_freeze'] = True
        result['status'] = 'PREPARED_NO_GRADLE'
    except BaseException as error:
        result.update(status='FAILED', error=str(error), traceback=traceback.format_exc())
        raise
    finally:
        result['updated'] = now()
        save(evidence / 'result.json', result)
    print('Frozen isolated pair: ' + str(root), flush=True)


def apk_info(path):
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, 'Invalid APK ZIP')
        dex = [i for i in archive.infolist() if re.fullmatch(r'classes\d*\.dex', i.filename)]
        return {'path': str(path), 'sha256': sha(path), 'bytes': path.stat().st_size,
                'dex_uncompressed_bytes': sum(i.file_size for i in dex),
                'dex_compressed_bytes': sum(i.compress_size for i in dex)}


def signer_digest(text):
    require(re.search(r'^Number of signers: 1\s*$', text, re.MULTILINE),
            'Expected exactly one APK signer')
    # Build-tools 37 uses "V2 Signer:"; earlier releases use "Signer #1".
    # One certificate can be repeated for multiple verified signing schemes.
    certs = {value.lower() for value in re.findall(
        r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
        text, re.MULTILINE)}
    require(len(certs) == 1, 'Expected one APK signing certificate')
    return next(iter(certs))


def command(argv, cwd, env, log, record, timeout):
    record.update(command=[str(a) for a in argv], cwd=str(cwd), started=now(), timeout_seconds=timeout)
    save(log.with_suffix('.command.json'), record)
    try:
        with log.open('xb') as stream:
            process = subprocess.run(record['command'], cwd=cwd, env=env,
                                     stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        record['exit'] = process.returncode
        require(process.returncode == 0, f'Command exit {process.returncode}: {log}')
    except BaseException as error:
        record['error'] = str(error)
        raise
    finally:
        record['finished'] = now()
        if log.exists():
            record['log_sha256'] = sha(log)
        save(log.with_suffix('.result.json'), record)


def run(args):
    root = args.output.resolve()
    evidence = root / 'evidence'
    result = json.loads((evidence / 'result.json').read_text(encoding='utf-8'))
    require(result['status'] == 'PREPARED_NO_GRADLE', 'Run requires a fresh prepared pair; never overwrite evidence')
    # Exclusive marker belongs only to this experiment. Never touch external build.lock.
    with (root / 'run-started.lock').open('x') as marker:
        marker.write(now())
    env = dict(os.environ)
    for name in RELEASE_ENV:
        env.pop(name, None)
    result.update(status='RUNNING', build_started=now(), release_secrets_removed=list(RELEASE_ENV),
                  gradle_offline=args.offline, gradle_cache='shared normal locking; no copied caches')
    save(evidence / 'result.json', result)
    try:
        sdk = Path(args.sdk or env.get('ANDROID_SDK_ROOT') or env.get('ANDROID_HOME') or '').resolve()
        java_home = Path(args.java_home or env.get('JAVA_HOME') or '').resolve()
        java = java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        signer = sdk / 'build-tools' / args.build_tools / 'lib' / 'apksigner.jar'
        require(java.is_file() and signer.is_file(), 'JDK or apksigner.jar missing')
        env.update(JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
        env['PATH'] = str(java_home / 'bin') + os.pathsep + env.get('PATH', '')
        result.update(java_home=str(java_home), sdk=str(sdk), build_tools=args.build_tools)
        for variant, record in result['variants'].items():
            work = root / 'work' / variant
            out = evidence / variant
            before = json.loads((out / 'source-hashes.json').read_text(encoding='utf-8'))
            require(sha(out / 'source-hashes.json') == record['source_manifest_sha256'], 'Manifest was changed')
            require(inventory(work) == before, 'Frozen inputs changed: ' + variant)
            # Local-only generated path file is deliberately excluded from source manifests.
            (work / 'local.properties').write_text('sdk.dir=' + str(sdk).replace('\\', '/').replace(':', '\\:') + '\n', encoding='utf-8')
            argv = [str(java), '-classpath', str(work / 'gradle/wrapper/gradle-wrapper.jar'),
                    'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', '--console=plain', '--stacktrace',
                    '--no-configuration-cache', '--no-build-cache', '--no-parallel', '--max-workers=4',
                    '-Pandroid.overridePathCheck=true', '-Pandroid.builder.sdkDownload=false']
            if args.offline:
                argv.append('--offline')
            argv += ['clean', ':app:assembleRelease']
            record['build'] = {}
            print(variant + ' release build started', flush=True)
            command(argv, work, env, out / 'build.log', record['build'], args.timeout)
            require(inventory(work) == before, 'Inputs changed during build: ' + variant)
            output = out / 'app-release.apk'
            shutil.copy2(work / 'app/build/outputs/apk/release/app-release.apk', output)
            mapping = work / 'app/build/outputs/mapping/release'
            shutil.copytree(mapping, out / 'r8')
            merged = out / 'r8/configuration.txt'
            require(merged.is_file(), 'Missing merged R8 configuration')
            record['r8_hashes'] = {p.relative_to(out / 'r8').as_posix(): sha(p)
                                   for p in sorted((out / 'r8').rglob('*')) if p.is_file()}
            merged_text = merged.read_text(encoding='utf-8')
            matched = [rule for rule in RULES if rule in merged_text]
            require(len(matched) == (7 if variant == 'production' else 0), 'Merged R8 shared-keep mismatch')
            require('io.github.bytejuno.thetacore.sdk.**' in merged_text and
                    'io.github.libxposed.api.XposedModule' in merged_text, 'Merged SDK/Hook rules missing')
            record['merged_shared_keep_count'] = len(matched)
            record['apk'] = apk_info(output)
            record['signature'] = {}
            command([str(java), '-jar', str(signer), 'verify', '--verbose', '--print-certs', str(output)],
                    work, env, out / 'apksigner.log', record['signature'], 120)
            text = (out / 'apksigner.log').read_text(encoding='utf-8')
            record['signer_sha256'] = signer_digest(text)
            save(evidence / 'result.json', result)
            print(variant + ' complete: ' + str(record['apk']['bytes']) + ' bytes', flush=True)
        production, comparison = (result['variants'][v] for v in ('production', 'comparison'))
        require(production['signer_sha256'] == comparison['signer_sha256'], 'Paired signers differ')
        a, b = production['apk'], comparison['apk']
        result['size_delta'] = {'production_minus_comparison_bytes': a['bytes'] - b['bytes'],
                                'percent_of_comparison': (a['bytes'] / b['bytes'] - 1) * 100,
                                'dex_uncompressed_bytes': a['dex_uncompressed_bytes'] - b['dex_uncompressed_bytes']}
        result['status'] = 'MEASURED_' + result['execution_context']
    except BaseException as error:
        result.update(status='FAILED', error=str(error), traceback=traceback.format_exc())
        raise
    finally:
        result['finished'] = now()
        original = json.loads((evidence / 'source-hashes.json').read_text(encoding='utf-8'))
        try:
            current = inventory(Path(result['source']))
            result['live_source_delta_after_run'] = input_delta(original, current)
        except Exception as error:
            result['live_source_check_error'] = str(error)
        save(evidence / 'result.json', result)
        print('Evidence: ' + str(evidence / 'result.json'), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('prepare', 'run', 'all'))
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--reference-manifest', type=Path)
    parser.add_argument('--context', choices=('LOCAL_ONLY', 'CI'), default='LOCAL_ONLY')
    parser.add_argument('--java-home')
    parser.add_argument('--sdk')
    parser.add_argument('--build-tools', default='37.0.0')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--timeout', type=int, default=3000)
    args = parser.parse_args()
    if args.phase in ('prepare', 'all'):
        prepare(args)
    if args.phase in ('run', 'all'):
        run(args)


if __name__ == '__main__':
    main()
