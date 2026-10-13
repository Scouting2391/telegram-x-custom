import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('release_pipeline', ROOT / 'scripts/release_pipeline.py')
rp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rp)


class ReleasePolicyTest(unittest.TestCase):
    def fixture(self):
        return {'id': 1, 'tag_name': 'v0.29.1.1817', 'draft': False, 'prerelease': False,
                'assets': [{'name': 'Telegram-X-0.29.1.1817.apk', 'state': 'uploaded', 'size': 123,
                            'digest': 'sha256:' + 'a' * 64,
                            'browser_download_url': 'https://github.com/TGX-Android/Telegram-X/releases/download/v0.29.1.1817/Telegram-X-0.29.1.1817.apk'}]}

    def test_select_only_matching_stable_universal(self):
        version, asset, digest = rp.select_universal(self.fixture())
        self.assertEqual(version, '0.29.1.1817')
        self.assertEqual(digest, 'a' * 64)
        for change in ({'draft': True}, {'prerelease': True}, {'tag_name': 'latest'}, {'assets': []}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                rp.select_universal(dict(self.fixture(), **change))

    def test_asset_must_have_digest_and_valid_size(self):
        for change in ({'digest': None}, {'digest': 'sha256:short'}, {'size': 0}, {'size': -1}, {'state': 'new'}):
            fixture = self.fixture()
            fixture['assets'][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                rp.select_universal(fixture)

    def test_repo_and_revision_bounds(self):
        self.assertEqual(rp.checked_repo('owner/custom-tgx'), 'owner/custom-tgx')
        for repo in ('TGX-Android/Telegram-X', '../repo', 'owner/repo/path', 'owner/repo?token=x'):
            with self.subTest(repo=repo), self.assertRaises(ValueError): rp.checked_repo(repo)
        for value in ('0', '100', '-1', '1.5', 'x'):
            with self.subTest(value=value), self.assertRaises(ValueError): rp.checked_revision(value)
        self.assertEqual(rp.checked_revision('99'), 99)

    def test_monotonic_tags(self):
        self.assertLess(rp.tag_order('v0.29.1.1817-custom.1'), rp.tag_order('v0.29.1.1817-custom.2'))
        self.assertLess(rp.tag_order('v0.29.1.1817-custom.99'), rp.tag_order('v0.29.1.1818-custom.1'))
        for value in ('v0.29.1.1817', 'v0.29.1.1817-custom.0', 'v0.29.1.1817-custom.100'):
            with self.subTest(value=value), self.assertRaises(ValueError): rp.tag_order(value)

    def test_download_host_restrictions(self):
        rp.check_download_url('https://github.com/owner/repo/releases/download/tag/app.apk')
        rp.check_download_url('https://release-assets.githubusercontent.com/a?token=x')
        for url in ('http://github.com/a', 'https://evil.test/a', 'https://github.com:444/a', 'https://user:pass@github.com/a'):
            with self.subTest(url=url), self.assertRaises(ValueError): rp.check_download_url(url)

    def test_publish_requires_matching_complete_uploaded_assets(self):
        expected = {'app.apk': ('a' * 64, 12), 'tgx-update.json': ('b' * 64, 20)}
        valid = [{'name': n, 'digest': 'sha256:' + d, 'size': s, 'state': 'uploaded'} for n, (d, s) in expected.items()]
        self.assertTrue(rp.uploaded_assets_match(valid, expected))
        self.assertFalse(rp.uploaded_assets_match(valid[:1], expected))
        self.assertFalse(rp.uploaded_assets_match(valid + [valid[0]], expected))
        for field, value in (('digest', 'sha256:' + '0' * 64), ('size', 11), ('state', 'new'), ('name', 'wrong.apk')):
            changed = [dict(v) for v in valid]
            changed[0][field] = value
            with self.subTest(field=field): self.assertFalse(rp.uploaded_assets_match(changed, expected))

    def test_dist_metadata_matches_verified_bytes(self):
        test_root = ROOT / '.test-work'
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as temp:
            directory = Path(temp)
            self.assertTrue(directory.resolve().is_relative_to(test_root.resolve()))
            (directory / 'app.apk').write_bytes(b'verified fixture')
            metadata = {'schemaVersion': 1, 'packageName': rp.PACKAGE, 'versionCode': 1817301,
                        'versionName': '0.29.1.1817-universal', 'apkFileName': 'app.apk'}
            report = {'success': True, 'apkSha256': rp.sha256(directory / 'app.apk')}
            (directory / 'tgx-update.json').write_text(json.dumps(metadata))
            (directory / 'verification.json').write_text(json.dumps(report))
            (directory / 'upstream-release.json').write_text('{}')
            self.assertEqual(rp.validate_dist(directory)[0], metadata)
            for change in ({'packageName': 'another.package'}, {'versionCode': 0}, {'versionCode': '1817301'},
                           {'schemaVersion': 2}, {'apkFileName': '../app.apk'}, {'versionName': 'bad'}):
                (directory / 'tgx-update.json').write_text(json.dumps(dict(metadata, **change)))
                with self.subTest(change=change), self.assertRaises(ValueError): rp.validate_dist(directory)
            (directory / 'tgx-update.json').write_text(json.dumps(metadata))
            (directory / 'app.apk').write_bytes(b'altered after verification')
            with self.assertRaises(ValueError): rp.validate_dist(directory)


if __name__ == '__main__': unittest.main()
