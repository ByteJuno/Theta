#!/usr/bin/env python3
"""Unit tests for scripts/preflight.py and scripts/paired-keep-size.py failure excerpts."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


preflight = load_module('theta_preflight', 'preflight.py')
paired = load_module('theta_paired_keep_size', 'paired-keep-size.py')


class PreflightTests(unittest.TestCase):
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
