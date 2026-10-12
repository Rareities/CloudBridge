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
        (self.root / 'docs').mkdir(parents=True)
        self.changelog = self.root / 'docs/FORK_CHANGELOG.md'
        self.changelog.write_text('## 1.1.1-rc.1 — release\n\nNew release.\n\n## 1.1.0-rc.1\n\nOld release.\n')
        self.props(110)
    def tearDown(self): self.temp.cleanup()
    def props(self, code, version="1.1.1-rc.1"):
        (self.root / 'gradle.properties').write_text(f'io.github.rareities.protonbridge.versionCode={code}\nio.github.rareities.protonbridge.versionName={version}\n')
    def validate(self, tags, previous, tag="v1.1.1-rc.1"):
        with patch.object(module.subprocess, 'check_output', return_value='\n'.join(tags)), patch.object(module.subprocess, 'run', return_value=SimpleNamespace(stdout=f'io.github.rareities.protonbridge.versionCode={previous}')):
            return module.validate(tag, self.root)
    def test_next_base_exceeds_old_split_apks(self): self.assertEqual(110,self.validate(['v1.1.0-rc.1'],100))
    def test_legacy_cloudbridge_property_history_is_recognized(self):
        with patch.object(module.subprocess, 'check_output', return_value='v1.1.0-rc.1'), patch.object(module.subprocess, 'run', return_value=SimpleNamespace(stdout='de.schuelken.cloudbridge.versionCode=100')):
            self.assertEqual(110,module.validate('v1.1.1-rc.1',self.root))
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

    def test_notes_exclude_other_versions(self):
        self.assertEqual('## 1.1.1-rc.1 — release\n\nNew release.\n', module.release_notes('v1.1.1-rc.1', self.root))

    def test_missing_or_duplicate_notes_stop_release(self):
        for text in ['## 1.1.0-rc.1\nOld release.', '## 1.1.1-rc.1\nA\n## 1.1.1-rc.1 — duplicate\nB']:
            self.changelog.write_text(text)
            with self.assertRaises(ValueError): self.validate([],100)

    def test_empty_notes_stop_release(self):
        self.changelog.write_text('## 1.1.1-rc.1\n\n## 1.1.0-rc.1\nOld release.')
        with self.assertRaises(ValueError): self.validate([],100)

    def stable(self):
        self.props(200, '1.2.0')
        self.changelog.write_text('## 1.2.0 — stable release\n\nReviewed release.\n')

    def test_stable_release_accepts_next_base(self):
        self.stable()
        self.assertEqual(200, self.validate(['v1.1.9-rc.1'], 190, 'v1.2.0'))

    def test_stable_reused_tag_or_code_is_rejected(self):
        self.stable()
        for tags, previous in [(['v1.2.0'],190), (['v1.1.9-rc.1'],200)]:
            with self.subTest(tags=tags), self.assertRaises(ValueError):
                self.validate(tags,previous,'v1.2.0')

    def test_stable_tag_requires_matching_checked_in_version(self):
        self.stable()
        with self.assertRaises(ValueError): self.validate([],190,'v1.2.1')

    def test_unsupported_tag_formats_are_rejected(self):
        self.stable()
        for tag in ['1.2.0','v1.2','v1.2.0-beta.1','v1.2.0-rc','v1.2.0-rc.1-extra','v1.2.0+build']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.validate([],190,tag)

if __name__ == '__main__': unittest.main()
