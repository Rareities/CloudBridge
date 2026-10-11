import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('release_version', pathlib.Path(__file__).resolve().parents[1] / 'validate-release-version.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        (self.root / 'docs/releases').mkdir(parents=True)
        (self.root / 'docs/releases/v1.1.1-rc.1.md').write_text('new release')
        self.props(110)
    def tearDown(self): self.temp.cleanup()
    def props(self, code):
        (self.root / 'gradle.properties').write_text(f'de.schuelken.cloudbridge.versionCode={code}\nde.schuelken.cloudbridge.versionName=1.1.1-rc.1\n')
    def validate(self, tags, previous):
        with patch.object(module.subprocess, 'check_output', return_value='\n'.join(tags)), patch.object(module.subprocess, 'run', return_value=SimpleNamespace(stdout=f'de.schuelken.cloudbridge.versionCode={previous}')):
            return module.validate('v1.1.1-rc.1', self.root)
    def test_next_base_exceeds_old_split_apks(self): self.assertEqual(110,self.validate(['v1.1.0-rc.1'],100))
    def test_reserved_abi_digit_cannot_be_used(self):
        self.props(109)
        with self.assertRaises(ValueError): self.validate([],100)
    def test_stable_history_cannot_be_downgraded(self):
        with self.assertRaises(ValueError): self.validate(['v1.1.0'],120)
    def test_existing_tag_cannot_be_rebuilt(self):
        with self.assertRaises(ValueError): self.validate(['v1.1.1-rc.1'],100)
    def test_reused_code_is_rejected(self):
        self.props(100)
        with self.assertRaises(ValueError): self.validate(['v1.1.0-rc.1'],100)
    def test_tag_must_match_checked_in_version(self):
        with self.assertRaises(ValueError): module.validate('v1.1.2-rc.1',self.root)

if __name__ == '__main__': unittest.main()
