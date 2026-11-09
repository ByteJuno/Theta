#!/usr/bin/env python3
"""Offline guard regression. Run both `python -I FILE` and `python -I -O FILE`."""
import ast
import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).with_name('ledger-runtime-acceptance.py')
spec = importlib.util.spec_from_file_location('acceptance', PATH)
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceGuardsTest(unittest.TestCase):
    def test_no_optimizable_asserts_anywhere(self):
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(ast.parse(PATH.read_text(encoding='utf-8')))))

    def test_wrong_target_rejected_before_any_command(self):
        for serial, avd in [('emulator-5582', 'theta_issue17_api36'), ('physical', 'theta_issue17_api36'),
                            ('emulator-5586', 'theta_issue17_api36'), ('emulator-5588', 'wrong')]:
            commands = []
            with self.assertRaises(RuntimeError):
                acceptance.validate_device(serial, avd, lambda *args: commands.append(args))
            self.assertEqual([], commands)

    def test_every_device_identity_guard_is_enforced(self):
        for values in [('other', '1', 'theta_issue17_api36'), ('emulator-5588', '0', 'theta_issue17_api36'),
                       ('emulator-5588', '1', 'wrong'), ('emulator-5588', '1', '')]:
            replies = iter(values)
            with self.assertRaises(RuntimeError):
                acceptance.validate_device('emulator-5588', 'theta_issue17_api36', lambda *args: next(replies))
        replies = iter(('emulator-5588', '1', 'theta_issue17_api36\nOK'))
        acceptance.validate_device('emulator-5588', 'theta_issue17_api36', lambda *args: next(replies))

    def test_clear_runs_once_and_rejects_failed_result(self):
        for response in ('Success\n', 'Failed', 'NotSuccess'):
            commands = []
            def device(*args):
                commands.append(args)
                return response
            if response.startswith('Success'):
                acceptance.clear_host_data(device)
            else:
                with self.assertRaises(RuntimeError):
                    acceptance.clear_host_data(device)
            self.assertEqual([('shell', 'pm', 'clear', acceptance.PACKAGE)], commands)

    def test_phase_checks_reject_false_passes(self):
        for text in ('', 'ledger=PASS execute', 'INSTRUMENTATION_CODE: -1',
                     'ledger=FAIL execute\nINSTRUMENTATION_CODE: -1',
                     'ledger=PASS execute\nINSTRUMENTATION_CODE: 0',
                     'ledger=PASS recover\nINSTRUMENTATION_CODE: -1',
                     'ledger=PASS execute\nledger=FAIL execute\nINSTRUMENTATION_CODE: -1'):
            with self.assertRaises(RuntimeError):
                acceptance.require_phase(text, 'execute')
        acceptance.require_phase('INSTRUMENTATION_RESULT: ledger=PASS execute\nINSTRUMENTATION_CODE: -1', 'execute')


if __name__ == '__main__':
    unittest.main()
