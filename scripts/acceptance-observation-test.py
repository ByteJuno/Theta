#!/usr/bin/env python3
"""Regression controls for list scrolling and localized signature diagnostics."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

em = load('observation_emulator', 'emulator-test.py')
sig = load('observation_signature', 'signature-acceptance.py')
lc = load('observation_lifecycle', 'lifecycle-acceptance.py')

def resource(locale, key):
    tree = ET.parse(ROOT / 'app/src/main/res' / locale / 'strings.xml')
    return next(n.text for n in tree.getroot() if n.get('name') == key)

class Observations(unittest.TestCase):
    def test_return_to_hello_sweeps_below_language_controls(self):
        class ReachedReturn(Exception):
            pass
        def waiting(description, matches, **kwargs):
            if description == 'back on the list':
                self.assertTrue(matches('Hello'))
                self.assertFalse(matches('Theta'))
                self.assertTrue(kwargs.get('sweep', False), 'Hello card is below language controls')
                raise ReachedReturn()
        with patch.object(em, 'adb'), patch.object(em, 'install_plugin'), patch.object(em, 'tap_card'), patch.object(em, 'wait_for', side_effect=waiting):
            with self.assertRaises(ReachedReturn):
                em.run('host.apk', 'probe.apk', 'hello.apk')

    def test_restored_list_sweeps_below_language_controls(self):
        class ReachedRestore(Exception):
            pass
        def waiting(description, matches, **kwargs):
            if description == 'host restored after deletion':
                self.assertTrue(matches('Hello'))
                self.assertFalse(matches('Probe'))
                self.assertTrue(kwargs.get('sweep', False))
                raise ReachedRestore()
            return ET.Element('node', text='PASS 18/18')
        with patch.object(em, 'adb', return_value='onDisable\ncleanup:second\ncleanup:first'), patch.object(em, 'install_plugin'), \
                patch.object(em, 'tap_card'), patch.object(em, 'tap_text'), \
                patch.object(em, 'absent'), patch.object(em, 'wait_for', side_effect=waiting):
            with self.assertRaises(ReachedRestore):
                em.run('host.apk', 'probe.apk', 'hello.apk')

    def rejection(self, text, trust):
        run = sig.Acceptance.__new__(sig.Acceptance)
        run.lc = Mock()
        run.lc.events.return_value = []
        def wait(description, predicate):
            result = predicate()
            if result is False:
                raise RuntimeError('no specific rejection observed')
            return result
        run.lc.wait.side_effect = wait
        run.report = {'verified_artifacts': {'other-v2': {'manifest': {'id': 'test.fixture'}}}}
        run.artifacts = ROOT
        run.current = 'different-signer-update'
        run.observations = []
        run.screen = lambda: [ET.Element('node', text=text)]
        run.scroll = Mock()
        run.snapshot = Mock()
        run.reject('other-v2', trust=trust)
        return run.observations[0]['rejection']

    def test_localized_signer_mismatch_is_specific(self):
        for locale in ('values', 'values-zh'):
            text = resource(locale, 'failure_signer_mismatch')
            with self.subTest(locale=locale):
                self.assertEqual(text, self.rejection(text, trust=True))

    def test_localized_signature_invalid_is_specific(self):
        for locale in ('values', 'values-zh'):
            text = resource(locale, 'failure_signature_invalid')
            with self.subTest(locale=locale):
                self.assertEqual(text, self.rejection(text, trust=False))

    def test_unrelated_errors_and_wrong_reason_do_not_pass(self):
        for locale in ('values', 'values-zh'):
            for key, trust in (('operation_failed', True), ('operation_failed', False),
                               ('failure_signature_invalid', True), ('failure_signer_mismatch', False),
                               ('failure_digest_mismatch', True), ('failure_digest_mismatch', False)):
                with self.subTest(locale=locale, key=key, trust=trust):
                    with self.assertRaisesRegex(RuntimeError, 'no specific rejection'):
                        self.rejection(resource(locale, key), trust)

    def test_version_and_restart_resources_are_specific(self):
        for key in ('failure_higher_version', 'failure_restart_pending'):
            def matching(description, predicate):
                self.assertEqual(key, description)
                for locale in ('values', 'values-zh'):
                    self.assertTrue(predicate(resource(locale, key)))
                    self.assertFalse(predicate(resource(locale, 'operation_failed')))
                    self.assertFalse(predicate(resource(locale, 'failure_signer_mismatch')))
                    other = 'failure_restart_pending' if key == 'failure_higher_version' else 'failure_higher_version'
                    self.assertFalse(predicate(resource(locale, other)))
            with self.subTest(key=key), patch.object(lc, 'matching', side_effect=matching):
                lc.resource_text(key)

    def test_diagnostic_requires_specific_host_reason(self):
        def waiting(description, predicate):
            return predicate()
        reason = 'Initialization timed out; restart required'
        with patch.object(lc, 'wait', side_effect=waiting), patch.object(lc, 'adb') as adb:
            for message, expected in ((reason, True), ('Operation failed', False),
                                      ('APK signature verification failed', False), ('', False)):
                adb.return_value = message
                self.assertEqual(expected, lc.diagnostic('init timeout', lambda line: reason in line))
                adb.assert_called_with('logcat', '-d', '-v', 'raw', 'ThetaUi:W', '*:S')

    def test_confirmation_is_never_rejection(self):
        with self.assertRaisesRegex(RuntimeError, 'gate bypass'):
            self.rejection('Install Fixture 2.0?', trust=True)

if __name__ == '__main__':
    unittest.main(verbosity=2)
