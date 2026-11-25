#!/usr/bin/env python3
"""Cross-platform pre-flight checks before opening or updating a Theta PR.

Catches common PR failures locally before pushing:
- English/Chinese strings.xml XML validity, duplicate keys, and exact key parity
- docs/testing/ evidence hygiene (ASCII control bytes, file count <= 10, size <= 1 MiB)
- Protected file edits and stray probe filenames outside samples/probe/
- Optional Gradle checks matching CI (:samples:storage-fixture, :app:verifyUiResources,
  :app:lintDebug, :agent-core:test, :app:testDebugUnitTest)
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

PROTECTED = re.compile(
    r'^(\.github/|docs/DESIGN\.md$|docs/PROTOCOL\.md$|gradle/wrapper/|gradlew(?:\.bat)?$)'
    r'|keystore|\.jks$|signing|verify-signatures',
    re.I,
)
EVIDENCE_MAX_FILES = 10
EVIDENCE_MAX_BYTES = 1 << 20
BLOB_MAX_BYTES = 5 << 20
ALLOWED_CTRL = frozenset((9, 10, 13))


def find_repo_root(start=None):
    cur = Path(start or Path(__file__).resolve().parent).resolve()
    for candidate in (cur, *cur.parents):
        if (candidate / 'settings.gradle.kts').is_file() and (candidate / 'app').is_dir():
            return candidate
    raise RuntimeError('Could not locate Theta repository root')


def check_strings_parity(repo):
    errors = []
    en_path = repo / 'app/src/main/res/values/strings.xml'
    zh_path = repo / 'app/src/main/res/values-zh/strings.xml'
    parsed = {}
    for label, path in (('en', en_path), ('zh', zh_path)):
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as exc:
            errors.append(f'Failed to parse {path.relative_to(repo).as_posix()}: {exc}')
            continue
        names = [elem.attrib['name'] for elem in root if 'name' in elem.attrib]
        seen = set()
        dups = sorted({name for name in names if name in seen or seen.add(name)})
        if dups:
            errors.append(f'Duplicate string keys in {label}: {", ".join(dups[:10])}')
        parsed[label] = seen
    if 'en' in parsed and 'zh' in parsed:
        missing_zh = sorted(parsed['en'] - parsed['zh'])
        missing_en = sorted(parsed['zh'] - parsed['en'])
        if missing_zh:
            errors.append(f'Missing keys in values-zh/strings.xml: {", ".join(missing_zh[:10])}')
        if missing_en:
            errors.append(f'Missing keys in values/strings.xml: {", ".join(missing_en[:10])}')
    return errors


def find_control_bytes(raw):
    return [(idx, byte) for idx, byte in enumerate(raw) if byte < 32 and byte not in ALLOWED_CTRL]


def git_changed_files(repo, base):
    paths = set()
    cmds = [
        ['git', 'diff', '--name-only', f'{base}...HEAD'],
        ['git', 'diff', '--name-only'],
        ['git', 'diff', '--cached', '--name-only'],
        ['git', 'ls-files', '--others', '--exclude-standard'],
    ]
    for cmd in cmds:
        proc = subprocess.run(cmd, cwd=repo, text=True, capture_output=True, check=False)
        if proc.returncode == 0:
            for line in proc.stdout.splitlines():
                line = line.strip()
                if line:
                    paths.add(line)
    return sorted(paths)


def check_testing_docs(repo, changed_paths):
    errors = []
    testing_files = []
    for rel in changed_paths:
        full = repo / rel
        if not full.is_file():
            continue
        size = full.stat().st_size
        if size > BLOB_MAX_BYTES:
            errors.append(f'Oversized file (>5 MiB): {rel} ({size} bytes)')
        if rel.startswith('docs/testing/'):
            testing_files.append((rel, full, size))
            bad = find_control_bytes(full.read_bytes())
            if bad:
                sample = ', '.join(f'offset {pos}=0x{b:02x}' for pos, b in bad[:5])
                errors.append(f'Unexpected ASCII control bytes in {rel}: {sample}')
    total_bytes = sum(size for _, _, size in testing_files)
    if len(testing_files) > EVIDENCE_MAX_FILES:
        errors.append(f'Too many changed docs/testing files: {len(testing_files)} > {EVIDENCE_MAX_FILES}')
    if total_bytes > EVIDENCE_MAX_BYTES:
        errors.append(f'Changed docs/testing files exceed 1 MiB: {total_bytes} bytes')
    return errors


def check_changed_paths(changed_paths, allow_protected=False):
    errors = []
    for rel in changed_paths:
        if not allow_protected and PROTECTED.search(rel):
            errors.append(f'Protected file modified: {rel}')
        if not rel.startswith(('samples/probe/', 'samples/hook-target/')) and 'probe' in rel.lower():
            errors.append(f'Forbidden "probe" filename outside sample fixtures: {rel}')
    return errors


def gradle_tasks(quick=False):
    tasks = [
        ':samples:storage-fixture:verifyStorageFixtureSources',
        ':samples:storage-fixture:compileDebugKotlin',
        ':app:verifyUiResources',
        ':app:lintDebug',
    ]
    if not quick:
        tasks += [':agent-core:test', ':app:testDebugUnitTest']
    return tasks


def run_gradle(repo, quick=False, extra_args=()):
    wrapper = repo / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    cmd = [str(wrapper), '--no-daemon', *gradle_tasks(quick=quick), *extra_args]
    print('Running:', ' '.join(cmd), flush=True)
    proc = subprocess.run(cmd, cwd=repo, check=False)
    return proc.returncode


def main(argv=None):
    ap = argparse.ArgumentParser(description='Theta pre-PR local preflight checks.')
    ap.add_argument('--base', default='origin/main', help='Base git ref for changed-file checks (default: origin/main)')
    ap.add_argument('--static-only', action='store_true', help='Skip Gradle tasks and run static checks only')
    ap.add_argument('--quick', action='store_true', help='Run fast compile/resource/lint Gradle tasks without full unit tests')
    ap.add_argument('--allow-protected', action='store_true', help='Do not fail when protected files are modified')
    ap.add_argument('--gradle-arg', action='append', default=[], help='Extra argument forwarded to Gradle (repeatable)')
    args = ap.parse_args(argv)

    repo = find_repo_root()
    changed = git_changed_files(repo, args.base)
    errors = []
    errors.extend(check_strings_parity(repo))
    errors.extend(check_testing_docs(repo, changed))
    errors.extend(check_changed_paths(changed, allow_protected=args.allow_protected))

    if errors:
        print('PREFLIGHT STATIC CHECKS FAILED:', file=sys.stderr)
        for item in errors:
            print(' -', item, file=sys.stderr)
        return 1
    print(f'Preflight static checks passed ({len(changed)} changed paths vs {args.base}).', flush=True)

    if args.static_only:
        return 0
    rc = run_gradle(repo, quick=args.quick, extra_args=args.gradle_arg)
    if rc != 0:
        print(f'PREFLIGHT GRADLE FAILED (exit {rc})', file=sys.stderr)
        return rc
    print('PREFLIGHT OK', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
