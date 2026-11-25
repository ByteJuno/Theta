#!/usr/bin/env python3
"""Unit tests for scripts/preflight.py and scripts/paired-keep-size.py failure excerpts."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parent


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


preflight = load_module('theta_preflight', 'preflight.py')
paired = load_module('theta_paired_keep_size', 'paired-keep-size.py')


def git(repo, *args):
    return subprocess.run(
        ['git', '-c', 'user.name=Preflight Test', '-c', 'user.email=preflight@example.invalid',
         '-c', 'commit.gpgsign=false', '-c', 'core.quotePath=true', *args],
        cwd=repo, capture_output=True, text=True, check=True,
    ).stdout.strip()


def init_repo(repo):
    git(repo, 'init')
    for lang in ('values', 'values-zh'):
        path = repo / 'app/src/main/res' / lang / 'strings.xml'
        path.parent.mkdir(parents=True)
        path.write_text('<resources/>', encoding='utf-8')
    git(repo, 'add', '.')
    git(repo, 'commit', '-m', 'Base fixture')
    return git(repo, 'rev-parse', 'HEAD')


class PreflightTests(unittest.TestCase):
    def test_protected_rename_checks_source_before_and_after_commit(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            init_repo(repo)
            source = repo / 'docs/PROTOCOL.md'
            source.parent.mkdir()
            source.write_text('protected fixture content\n', encoding='utf-8')
            git(repo, 'add', '.')
            git(repo, 'commit', '-m', 'Protected base fixture')
            base = git(repo, 'rev-parse', 'HEAD')
            source.rename(repo / 'moved.txt')
            git(repo, 'add', '.')
            for committed in (False, True):
                with self.subTest(committed=committed):
                    if committed:
                        git(repo, 'commit', '-m', 'Rename protected fixture')
                    changed = preflight.git_changed_files(repo, base)
                    self.assertEqual(changed, ['docs/PROTOCOL.md', 'moved.txt'])
                    self.assertIn('Protected file modified: docs/PROTOCOL.md', preflight.check_changed_paths(changed))

    def test_missing_base_fails_without_running_gradle(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            init_repo(repo)
            path = repo / 'docs/PROTOCOL.md'
            path.parent.mkdir()
            path.write_text('protected fixture change', encoding='utf-8')
            git(repo, 'add', '.')
            git(repo, 'commit', '-m', 'Protected fixture change')
            stdout, stderr = io.StringIO(), io.StringIO()
            with mock.patch.object(preflight, 'find_repo_root', return_value=repo), \
                    mock.patch.object(preflight, 'run_gradle') as gradle, \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                rc = preflight.main(['--base', 'missing-test-base'])
            self.assertNotEqual(rc, 0)
            self.assertNotIn('checks passed', stdout.getvalue())
            self.assertIn('missing-test-base', stderr.getvalue())
            gradle.assert_not_called()

    def test_git_preserves_paths_in_commits_index_worktree_and_untracked(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            base = init_repo(repo)
            committed = ['docs/testing/审核/summary.md', '.github/workflows/审核.yml']
            tracked = 'docs/testing/本地/summary.md'
            for rel in committed + [tracked]:
                path = repo / rel
                path.parent.mkdir(parents=True)
                path.write_bytes(b'bad\x07text\n')
            git(repo, 'add', '.')
            git(repo, 'commit', '-m', 'Unicode fixture paths')
            self.assertEqual(preflight.git_changed_files(repo, base), sorted(committed + [tracked]))
            head = git(repo, 'rev-parse', 'HEAD')
            (repo / tracked).write_bytes(b'changed\x07text\n')
            staged = 'docs/testing/暂存/summary.md'
            path = repo / staged
            path.parent.mkdir(parents=True)
            path.write_bytes(b'staged\x07text\n')
            git(repo, 'add', staged)
            untracked = 'docs/testing/未暂存/summary.md'
            path = repo / untracked
            path.parent.mkdir(parents=True)
            path.write_bytes(b'untracked\x07text\n')
            unusual = ' leading space.txt' if os.name == 'nt' else ' leading and trailing spaces \tline\nbreak '
            (repo / unusual).write_text('fixture', encoding='utf-8')
            self.assertEqual(preflight.git_changed_files(repo, head), sorted([tracked, staged, untracked, unusual]))
            changed = preflight.git_changed_files(repo, base)
            errors = preflight.check_testing_docs(repo, changed) + preflight.check_changed_paths(changed)
            self.assertEqual(sum('ASCII control' in e for e in errors), 4)
            self.assertTrue(any('Protected file modified: .github/workflows/审核.yml' in e for e in errors))

    def test_strings_parity_detects_missing_and_duplicates(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            (repo / 'app/src/main/res/values').mkdir(parents=True)
            (repo / 'app/src/main/res/values-zh').mkdir(parents=True)
            (repo / 'app/src/main/res/values/strings.xml').write_text(
                '<resources><string name="a">A</string><string name="a">A2</string><string name="b">B</string></resources>',
                encoding='utf-8',
            )
            (repo / 'app/src/main/res/values-zh/strings.xml').write_text(
                '<resources><string name="a">甲</string><string name="c">丙</string></resources>',
                encoding='utf-8',
            )
            errors = preflight.check_strings_parity(repo)
            joined = '\n'.join(errors)
            self.assertIn('Duplicate string keys in en: a', joined)
            self.assertIn('Missing keys in values-zh/strings.xml: b', joined)
            self.assertIn('Missing keys in values/strings.xml: c', joined)

    def test_testing_docs_rejects_control_bytes_and_oversized(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            summary = repo / 'docs/testing/137/summary.md'
            summary.parent.mkdir(parents=True)
            summary.write_bytes(b'ok\n\x07bad\x08text\n')
            errors = preflight.check_testing_docs(repo, ['docs/testing/137/summary.md'])
            self.assertEqual(len(errors), 1)
            self.assertIn('Unexpected ASCII control bytes', errors[0])

            summary.write_bytes(b'clean\ttext\r\nline2\n')
            self.assertEqual(preflight.check_testing_docs(repo, ['docs/testing/137/summary.md']), [])

    def test_changed_paths_blocks_protected_and_probe_names(self):
        errors = preflight.check_changed_paths([
            'docs/PROTOCOL.md',
            'app/src/test/kotlin/MyProbeTest.kt',
            'samples/probe/src/main/kotlin/ProbePlugin.kt',
            'scripts/preflight.py',
        ])
        self.assertEqual(len(errors), 2)
        self.assertTrue(any('docs/PROTOCOL.md' in e for e in errors))
        self.assertTrue(any('MyProbeTest.kt' in e for e in errors))
        self.assertEqual(
            preflight.check_changed_paths(['docs/PROTOCOL.md'], allow_protected=True),
            [],
        )


class PairedKeepSizeExcerptTests(unittest.TestCase):
    def test_failure_excerpt_redacts_home_names_with_spaces(self):
        with tempfile.TemporaryDirectory() as td:
            log = Path(td) / 'build.log'
            log.write_text('\n'.join([
                'e: file:///Users/Alice Smith/repo/Foo.kt:12: error',
                'C:\\Users\\Bob Jones\\repo\\Foo.kt:12: error',
                'e: /home/Carol Doe/repo/Foo.kt:12: error',
            ]), encoding='utf-8')
            excerpt = paired.failure_excerpt(log)
            self.assertEqual(excerpt.count('~/repo/Foo.kt:12'), 2)
            self.assertIn('~\\repo\\Foo.kt:12', excerpt)
            for name in ('Alice', 'Smith', 'Bob', 'Jones', 'Carol', 'Doe'):
                self.assertNotIn(name, excerpt)

    def test_command_redacts_quoted_credentials_only_on_failure(self):
        payload = '\n'.join([
            'FAILURE: fixture error',
            '{"api_key": "json-credential"}',
            'password="first-word remaining-password"',
            "token = 'first-word remaining-token'",
            "{'secret': 'dict-credential'}",
        ])
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            for code in (0, 7):
                with self.subTest(exit_code=code):
                    log = repo / f'command-{code}.log'
                    record, stderr = {}, io.StringIO()
                    argv = [sys.executable, '-I', '-c', f'import sys; print({payload!r}); sys.exit({code})']
                    with contextlib.redirect_stderr(stderr):
                        if code:
                            with self.assertRaisesRegex(RuntimeError, 'Command exit 7'):
                                paired.command(argv, repo, dict(os.environ), log, record, 10)
                        else:
                            paired.command(argv, repo, dict(os.environ), log, record, 10)
                    self.assertEqual(record['exit'], code)
                    self.assertEqual(log.read_text(encoding='utf-8'), payload + '\n')
                    self.assertEqual(json.loads(log.with_suffix('.result.json').read_text(encoding='utf-8')), record)
                    output = stderr.getvalue()
                    if code:
                        self.assertIn('FAILURE: fixture error', output)
                        self.assertEqual(output.count('<redacted>'), 4)
                        for value in ('json-credential', 'first-word', 'remaining-password', 'remaining-token', 'dict-credential'):
                            self.assertNotIn(value, output)
                    else:
                        self.assertEqual(output, '')

    def test_failure_excerpt_sanitizes_paths_and_secrets(self):
        with tempfile.TemporaryDirectory() as td:
            log = Path(td) / 'build.log'
            log.write_text(
                '\n'.join([
                    '> Task :app:compileReleaseKotlin',
                    'e: file:///home/alice/repo/Foo.kt:12: Unresolved reference',
                    'C:\\Users\\bob\\project\\Bar.kt:10: error',
                    'api_key=supersecret123',
                    'THETA_RELEASE_STORE_PASSWORD=hunter2',
                    'FAILURE: Build failed with an exception.',
                ]),
                encoding='utf-8',
            )
            excerpt = paired.failure_excerpt(log)
            self.assertIn('~/repo/Foo.kt:12', excerpt)
            self.assertIn('~\\project\\Bar.kt:10', excerpt)
            self.assertNotIn('alice', excerpt)
            self.assertNotIn('bob', excerpt)
            self.assertNotIn('supersecret123', excerpt)
            self.assertNotIn('hunter2', excerpt)
            self.assertIn('api_key=<redacted>', excerpt)
            self.assertIn('FAILURE: Build failed with an exception.', excerpt)


if __name__ == '__main__':
    unittest.main()
