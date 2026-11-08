"""Offline regression for declared entry validation; no device or generated dex required."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('links', Path(__file__).with_name('check-plugin-links.py'))
links = importlib.util.module_from_spec(spec)
spec.loader.exec_module(links)

class EntryValidationTest(unittest.TestCase):
    def run_case(self, entry, definitions):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / 'fixture.apk'
            import json
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/theta-plugin.json', json.dumps({'entry': entry}))
            with patch.object(links.sys, 'argv', ['check', 'host.apk', str(apk)]), \
                 patch.object(links, 'read_apk', return_value=[]), \
                 patch.object(links, 'merged_classes', side_effect=[{}, definitions]), \
                 patch.object(links, 'platform_classes', return_value=None), \
                 contextlib.redirect_stdout(io.StringIO()):
                return links.main()

    def test_absent_declared_class_fails(self):
        self.assertEqual(1, self.run_case('test.S1Entry', {'Ltest/OldEntry;': object()}))

    def test_defined_declared_class_passes(self):
        self.assertEqual(0, self.run_case('test.S1Entry', {'Ltest/S1Entry;': object()}))

    def test_invalid_entry_fails(self):
        for entry in [None, '', 42]:
            with self.subTest(entry=entry):
                self.assertEqual(1, self.run_case(entry, {}))

if __name__ == '__main__': unittest.main()
