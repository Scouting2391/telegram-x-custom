import copy
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('fdroid_repo', ROOT / 'scripts/fdroid_repo.py')
fd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fd)


class FDroidTrustTest(unittest.TestCase):
    def release(self):
        tag = 'v0.29.1.1817-custom.3'
        names = ['app.apk', 'tgx-update.json', 'verification.json', 'upstream-release.json']
        return {'tag_name': tag, 'draft': False, 'prerelease': False, 'assets': [
            {'name': name, 'size': 123, 'state': 'uploaded', 'digest': 'sha256:' + 'a' * 64,
             'browser_download_url': f'https://github.com/{fd.REPOSITORY}/releases/download/{tag}/{name}'} for name in names]}

    def test_release_asset_origin_and_completeness(self):
        self.assertEqual(len(fd.published_assets(self.release())), 4)
        for mutation in ('draft', 'duplicate', 'missing', 'origin', 'digest', 'size', 'extra_apk'):
            release = self.release()
            if mutation == 'draft': release['draft'] = True
            elif mutation == 'duplicate': release['assets'].append(release['assets'][1].copy())
            elif mutation == 'missing': release['assets'].pop()
            elif mutation == 'origin': release['assets'][0]['browser_download_url'] = 'https://evil.test/app.apk'
            elif mutation == 'digest': release['assets'][0]['digest'] = None
            elif mutation == 'size': release['assets'][0]['size'] = 0
            elif mutation == 'extra_apk': release['assets'].append(dict(release['assets'][0], name='other.apk'))
            with self.subTest(mutation=mutation), self.assertRaises((ValueError, TypeError)):
                fd.published_assets(release)

    def test_index_binds_package_version_signer_and_bytes(self):
        info = {'versionCode': 1817303, 'versionName': '0.29.1.1817-universal', 'minSdkVersion': 24}
        digest = 'b' * 64
        version = {'manifest': dict(info, usesSdk={'minSdkVersion': 24}, signer={'sha256': [fd.FINGERPRINT]}),
                   'file': {'name': f'/{fd.rp.PACKAGE}_1817303.apk', 'sha256': digest, 'size': 123}}
        index = {'repo': {'address': fd.REPO_URL}, 'packages': {fd.rp.PACKAGE: {'versions': {digest: version}}}}
        self.assertEqual(fd.validate_index(index, info, digest, 123), f'{fd.rp.PACKAGE}_1817303.apk')
        for mutation in ('address', 'package', 'version', 'signer', 'digest', 'path', 'size', 'extra_version'):
            changed = copy.deepcopy(index)
            v = changed['packages'][fd.rp.PACKAGE]['versions'][digest]
            if mutation == 'address': changed['repo']['address'] = 'https://evil.test/repo'
            elif mutation == 'package': changed['packages']['other.package'] = {}
            elif mutation == 'version': v['manifest']['versionCode'] = 1817302
            elif mutation == 'signer': v['manifest']['signer']['sha256'] = ['c' * 64]
            elif mutation == 'digest': v['file']['sha256'] = 'd' * 64
            elif mutation == 'path': v['file']['name'] = '/../key.p12'
            elif mutation == 'size': v['file']['size'] = 124
            elif mutation == 'extra_version': changed['packages'][fd.rp.PACKAGE]['versions']['e' * 64] = v
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                fd.validate_index(changed, info, digest, 123)


if __name__ == '__main__':
    unittest.main()
