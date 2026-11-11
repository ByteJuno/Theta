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

class LifecycleSearch(unittest.TestCase):
    def search(self, *, target=True, duplicate=False, end=7, initial=0,
               bounds='[0,72][1080,1850]', empty=False, changing=False,
               timeout=12, delayed=False, target_position=None):
        """Exercise the real wait/matching/scroll/tap chain, with no adb or clock IO."""
        state = {'position': initial, 'clock': 0, 'swipes': [], 'taps': [], 'screens': 0}

        def screen():
            state['screens'] += 1
            nodes = [ET.Element('node', text='', bounds=bounds, scrollable='true'),
                     ET.Element('node', text='Theta', bounds='[16,80][150,120]'),
                     ET.Element('node', text=f'Fixture · 2.0 / {state["position"]}',
                                bounds='[16,140][600,180]'),
                     ET.Element('node', text='Uninstall Fixture?', bounds='[16,200][600,240]'),
                     ET.Element('node', text='Uninstall Fixture other', bounds='[16,250][600,290]'),
                     ET.Element('node', text='Uninstall data', bounds='[16,300][600,340]')]
            target_at = end if target_position is None else target_position
            if target and state['position'] == target_at and (not delayed or state['clock'] >= 3):
                nodes.append(ET.Element('node', text='Uninstall Fixture', bounds='[16,400][600,448]'))
                if duplicate:
                    nodes.append(ET.Element('node', text='Uninstall Fixture', bounds='[16,500][600,548]'))
            if empty:
                return []
            return nodes

        def adb(*args, **kwargs):
            if args == ('shell', 'wm', 'size'):
                return 'Physical size: 1080x1920'
            if args[:3] == ('shell', 'input', 'swipe'):
                x1, y1, x2, y2 = map(int, args[3:7])
                down = y1 > y2
                state['swipes'].append((x1, y1, x2, y2))
                bx1, by1, bx2, by2 = lc.rect(ET.Element('node', bounds=bounds))
                if bx1 < x1 == x2 < bx2 and by1 < min(y1, y2) < max(y1, y2) < by2:
                    if changing:
                        state['position'] += 1
                    else:
                        state['position'] = min(end, state['position'] + 1) if down else max(0, state['position'] - 1)
                return ''
            if args[:3] == ('shell', 'input', 'tap'):
                state['taps'].append(tuple(map(int, args[3:])))
                return ''
            raise AssertionError(f'Unexpected adb: {args}')

        def sleep(seconds):
            state['clock'] += seconds

        with patch.object(lc.em, 'screen', side_effect=screen), \
                patch.object(lc.em, 'crashed', return_value=False), \
                patch.object(lc, 'adb', side_effect=adb), \
                patch.object(lc.em, 'adb', side_effect=adb), \
                patch.object(lc.time, 'monotonic', side_effect=lambda: state['clock']), \
                patch.object(lc.time, 'sleep', side_effect=sleep), patch.object(lc, 'TIMEOUT', timeout):
            try:
                lc.tap('Uninstall Fixture')
            except lc.FAIL as error:
                state['error'] = str(error)
        return state

    def test_exact_uninstall_below_visible_header(self):
        state = self.search()
        self.assertNotIn('error', state)
        self.assertEqual([(308, 424)], state['taps'])
        self.assertEqual(8, len(state['swipes']))  # up to the top, then seven down
        self.assertLessEqual(state['screens'], 10, 'Reuse the observed XML for each swipe')

    def test_offscreen_target_requires_swiping_actual_body(self):
        state = self.search(bounds='[100,1550][980,1850]')
        self.assertNotIn('error', state)
        self.assertEqual([(308, 424)], state['taps'])
        self.assertEqual(8, len(state['swipes']))

    def test_missing_or_invalid_scroll_body_never_swipes(self):
        for bounds in ('', '[0,0][0,0]', '[0,-100][1080,100]', '[0,0][1080,40]'):
            with self.subTest(bounds=bounds):
                nodes = [ET.Element('node', bounds=bounds, scrollable='true')]
                with patch.object(lc, 'adb') as adb:
                    self.assertFalse(lc.scroll(True, nodes))
                    adb.assert_not_called()

    def test_search_reaches_target_above_initial_viewport(self):
        state = self.search(end=7, initial=7, target_position=1)
        self.assertNotIn('error', state)
        self.assertEqual(6, len(state['swipes']))
        self.assertTrue(all(y1 < y2 for _, y1, _, y2 in state['swipes']))
        self.assertEqual([(308, 424)], state['taps'])

    def test_visible_target_is_not_moved_before_tapping(self):
        state = self.search(end=0)
        self.assertNotIn('error', state)
        self.assertEqual([], state['swipes'])
        self.assertEqual([(308, 424)], state['taps'])

    def test_missing_target_exhausts_both_ends_without_oscillation(self):
        state = self.search(target=False, initial=3)
        self.assertIn('timed out', state.get('error', ''))
        self.assertEqual([], state['taps'])
        self.assertEqual(12, len(state['swipes']))  # four up + eight down, then observe only
        self.assertLessEqual(state['clock'], 12.3)

    def test_unchanged_short_list_stops_swiping_but_can_observe_late_target(self):
        state = self.search(end=0, delayed=True)
        self.assertNotIn('error', state)
        self.assertEqual(2, len(state['swipes']))
        self.assertEqual([(308, 424)], state['taps'])

    def test_duplicate_exact_target_never_taps_either(self):
        state = self.search(end=0, duplicate=True)
        self.assertIn('Ambiguous', state.get('error', ''))
        self.assertEqual([], state['taps'])
        self.assertEqual([], state['swipes'])

    def test_empty_dump_never_drives_a_blind_swipe(self):
        state = self.search(empty=True)
        self.assertIn('timed out', state.get('error', ''))
        self.assertEqual([], state['swipes'])
        self.assertEqual([], state['taps'])

    def test_changing_list_has_a_hard_sweep_bound(self):
        state = self.search(target=False, changing=True, timeout=60)
        self.assertIn('list end', state.get('error', ''))
        self.assertEqual(30, len(state['swipes']))
        self.assertEqual([], state['taps'])
        self.assertLess(state['clock'], 60)

    def test_swipe_stays_in_observed_scroll_body_not_display_fraction(self):
        nodes = [ET.Element('node', bounds='[100,1550][980,1850]', scrollable='true')]
        with patch.object(lc.em, 'screen', return_value=nodes), \
                patch.object(lc, 'adb', return_value='Physical size: 1080x1920') as adb:
            lc.scroll(True)
            args = adb.call_args.args
            self.assertEqual(('shell', 'input', 'swipe'), args[:3])
            x1, y1, x2, y2 = map(int, args[3:7])
            self.assertTrue(100 < x1 == x2 < 980)
            self.assertTrue(1550 < y2 < y1 < 1850)
            self.assertEqual(1, adb.call_count, 'XML bounds avoid a wm size round trip')

    def test_late_observation_still_fails_existing_deadline(self):
        node = ET.Element('node', text='Uninstall Fixture', bounds='[16,400][600,448]')
        with patch.object(lc.time, 'monotonic', side_effect=[0, 0, 2]):
            with self.assertRaisesRegex(lc.FAIL, 'observation exceeded deadline'):
                lc.wait('uninstall', lambda: node, timeout=1)


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
