import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class LocksTest(unittest.TestCase):
    def test_native_build_inputs_are_pinned(self):
        lock = json.loads((ROOT / 'scripts/dependencies.lock.json').read_text())
        for name in ('proot', 'talloc', 'libarchive'):
            self.assertRegex(lock[name]['sha256'], r'^[0-9a-f]{64}$')
            self.assertTrue(lock[name]['url'].startswith('https://'))
            self.assertNotIn('latest', lock[name]['url'])

    def test_rootfs_matches_official_architecture_and_signing_key(self):
        lock = json.loads((ROOT / 'scripts/rootfs.lock.json').read_text())
        self.assertEqual(lock['architecture'], 'aarch64')
        self.assertRegex(lock['sha256'], r'^[0-9a-f]{64}$')
        self.assertEqual(lock['signing_key_fingerprint'], '68B3537F39A313B3E574D06777193F152BDBE6A6')
        self.assertGreater(lock['size'], 100_000_000)

    def test_gradle_distribution_is_pinned(self):
        self.assertIn('distributionSha256Sum=', (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text())

if __name__ == '__main__':
    unittest.main()
