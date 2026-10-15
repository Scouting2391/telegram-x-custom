#!/usr/bin/env python3
"""Publish the existing signed custom APK through an authenticated F-Droid index."""
from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import tempfile
import zipfile

import build_custom as bc
import release_pipeline as rp

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'Scouting2391/telegram-x-custom'
REPO_URL = 'https://scouting2391.github.io/telegram-x-custom/fdroid/repo'
FINGERPRINT = (ROOT / 'signer-sha256.txt').read_text().strip().lower()
ADD_URL = REPO_URL + '?fingerprint=' + FINGERPRINT.upper()


def run(args, *, cwd=None, env=None):
    result = subprocess.run([str(a) for a in args], cwd=cwd, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding='utf-8', errors='replace')
    rp.require(result.returncode == 0, 'F-Droid tool failed:\n' + result.stdout[-12000:])
    return result.stdout


def tools(java_home, build_tools):
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(java_home).resolve() / 'bin'
    sdk_tools = Path(build_tools).resolve()
    result = {'java': java / ('java' + suffix), 'keytool': java / ('keytool' + suffix),
              'jarsigner': java / ('jarsigner' + suffix), 'aapt2': sdk_tools / ('aapt2' + suffix),
              'apksigner': sdk_tools / 'lib/apksigner.jar'}
    rp.require(all(p.is_file() for p in result.values()), 'Missing Java or Android signing tools')
    return result


def published_assets(release):
    rp.tag_order(release.get('tag_name'))
    rp.require(release.get('draft') is False and release.get('prerelease') is False,
               'Expected a published stable custom release')
    wanted = ('tgx-update.json', 'verification.json', 'upstream-release.json')
    assets = release.get('assets', [])
    apks = [a for a in assets if a.get('name', '').endswith('.apk')]
    rp.require(len(apks) == 1, 'Expected one custom APK')
    selected = apks + [a for name in wanted for a in assets if a.get('name') == name]
    rp.require(len(selected) == 4 and len({a['name'] for a in selected}) == 4,
               'Missing or duplicate release assets')
    prefix = f'https://github.com/{REPOSITORY}/releases/download/{release["tag_name"]}/'
    for asset in selected:
        name = asset.get('name', '')
        rp.require(re.fullmatch(r'[A-Za-z0-9_.-]+', name) and not name.startswith('.'), 'Unsafe asset name')
        rp.require(asset.get('browser_download_url') == prefix + name, 'Unexpected release asset URL')
        rp.require(asset.get('state') == 'uploaded' and rp.DIGEST.fullmatch(asset.get('digest', '')),
                   'Release asset lacks a GitHub digest')
        rp.require(type(asset.get('size')) is int and 0 < asset['size'] <= rp.MAX_DOWNLOAD,
                   'Invalid release asset size')
    return selected


def verify_apk(apk, info, commands):
    signed = run([commands['java'], '-jar', commands['apksigner'], 'verify', '--verbose', '--print-certs', apk])
    rp.require(bc.signatures(signed) == [FINGERPRINT], 'APK uses a different signing key')
    badging = run([commands['aapt2'], 'dump', 'badging', apk])
    rp.require(bc.parse_badging(badging) == (rp.PACKAGE, info['versionCode'], info['versionName'], info['minSdkVersion']),
               'APK metadata differs from the verified GitHub release')


def validate_index(index, info, apk_hash, apk_size):
    rp.require(index.get('repo', {}).get('address') == REPO_URL, 'Wrong F-Droid repository address')
    packages = index.get('packages', {})
    rp.require(set(packages) == {rp.PACKAGE}, 'Unexpected packages in F-Droid index')
    versions = packages[rp.PACKAGE].get('versions', {})
    rp.require(set(versions) == {apk_hash}, 'F-Droid must contain exactly the current verified APK')
    version = versions[apk_hash]
    manifest = version.get('manifest', {})
    rp.require(manifest.get('versionCode') == info['versionCode'] and manifest.get('versionName') == info['versionName'],
               'F-Droid version does not match the published release')
    rp.require(manifest.get('usesSdk', {}).get('minSdkVersion') == info['minSdkVersion'], 'Wrong minimum Android version')
    rp.require([s.lower() for s in manifest.get('signer', {}).get('sha256', [])] == [FINGERPRINT],
               'F-Droid lists a different APK signer')
    entry = version.get('file', {})
    expected_name = f'/{rp.PACKAGE}_{info["versionCode"]}.apk'
    rp.require(entry.get('name') == expected_name and entry.get('sha256') == apk_hash and entry.get('size') == apk_size,
               'F-Droid APK name, digest, or size differs')
    return expected_name[1:]


def verify_site(site, commands):
    site = Path(site).resolve()
    public = json.loads((site / 'repo-info.json').read_text())
    rp.require(public.get('repository') == REPOSITORY and public.get('fingerprint') == FINGERPRINT.upper(),
               'Wrong published repository identity')
    repo = site / 'fdroid/repo'
    jar = repo / 'entry.jar'
    output = run([commands['jarsigner'], '-J-Duser.language=en', '-verify', jar])
    rp.require('jar verified.' in output.lower(), 'F-Droid entry signature did not verify')
    certs = run([commands['keytool'], '-J-Duser.language=en', '-printcert', '-jarfile', jar, '-rfc'])
    pems = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', certs, re.S)
    rp.require(len(pems) == 1, 'Expected one F-Droid index signer')
    der = ssl.PEM_cert_to_DER_cert(pems[0])
    rp.require(hashlib.sha256(der).hexdigest() == FINGERPRINT, 'F-Droid signing fingerprint changed')
    with zipfile.ZipFile(jar) as archive:
        rp.require(sum(e.file_size for e in archive.infolist()) < 2 * 1024 * 1024, 'Oversized signed entry')
        rp.require([n for n in archive.namelist() if not n.startswith('META-INF/')] == ['entry.json'],
                   'Unexpected signed entry payload')
        entry = json.loads(archive.read('entry.json'))
    index_path = repo / 'index-v2.json'
    rp.require(entry['index']['name'] == '/index-v2.json' and entry['index']['sha256'] == rp.sha256(index_path),
               'Signed entry does not authenticate the F-Droid index')
    rp.require(entry['index']['size'] == index_path.stat().st_size, 'Index size differs from signed entry')
    index = json.loads(index_path.read_text())
    apk_name = validate_index(index, public['app'], public['apkSha256'], public['apkSize'])
    apk = repo / apk_name
    rp.require(apk.stat().st_size == public['apkSize'] and rp.sha256(apk) == public['apkSha256'], 'Published APK bytes differ')
    verify_apk(apk, public['app'], commands)
    for path in site.rglob('*'):
        rp.require(not path.is_symlink(), 'A public artifact must not contain symlinks')
        if not path.is_file():
            continue
        relative = path.relative_to(site).as_posix()
        rp.require(relative in ('index.html', 'add-repo.png', 'repo-info.json') or
                   (relative.startswith('fdroid/repo/') and
                    (path.name in (apk_name, 'entry.jar', 'entry.json', 'index-v2.json', 'index-v1.json',
                                   'index-v1.jar', 'index.jar', 'index.xml') or path.suffix == '.png')),
                   'Unexpected file in public artifact: ' + relative)
    print(json.dumps({'verified': True, 'repositoryUrl': REPO_URL, 'fingerprint': FINGERPRINT.upper(),
                      'versionCode': public['app']['versionCode'], 'apkSha256': public['apkSha256']}, indent=2))
    return public


def metadata(info):
    return {'Name': 'Telegram X Custom', 'AutoName': 'Telegram X', 'Categories': ['Internet'],
            'License': 'GPL-3.0-or-later', 'WebSite': 'https://github.com/' + REPOSITORY,
            'SourceCode': 'https://github.com/' + REPOSITORY,
            'IssueTracker': 'https://github.com/' + REPOSITORY + '/issues',
            'Summary': 'Telegram X with GitHub updates and no Play update prompts',
            'Description': 'Personal custom Telegram X, retaining the existing application signing key. '
                           'Google Play in-app update prompts are disabled. GitHub and this F-Droid feed '
                           'distribute the same signed APK. The app still includes Google Play Services '
                           'and Firebase messaging. This is an independent repository, not the official '
                           'F-Droid collection or an official Telegram X release. Source modifications '
                           'and build instructions are available in the linked source repository.',
            'CurrentVersion': info['versionName'], 'CurrentVersionCode': info['versionCode'],
            'AllowedAPKSigningKeys': [FINGERPRINT],
            'AntiFeatures': {'NonFreeDep': {'en-US': 'Includes Google Play Services and Firebase dependencies.'},
                             'NonFreeNet': {'en-US': 'Connects to Telegram and Firebase services.'}}}


def build(args):
    commands = tools(args.java_home, args.build_tools)
    site = Path(args.site_dir).resolve()
    rp.require(not site.exists(), 'Use a fresh public output directory')
    release = rp.api(f'/repos/{REPOSITORY}/releases/latest')
    assets = published_assets(release)
    env = os.environ.copy()
    encoded = env.pop('TGX_KEYSTORE_BASE64', '')
    password = env.pop('TGX_KEYSTORE_PASSWORD', '')
    rp.require(encoded and password and '\n' not in password and '\r' not in password, 'Missing signing secrets')
    key = base64.b64decode(''.join(encoded.split()), validate=True)
    rp.require(0 < len(key) < 1024 * 1024, 'Invalid signing key length')
    private_root = ROOT / '.ci'
    private_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='fdroid-', dir=private_root) as temporary:
        work = Path(temporary).resolve()
        downloads = work / 'release'
        downloads.mkdir()
        for asset in assets:
            rp.download(asset['browser_download_url'], downloads / asset['name'], asset['digest'][7:], asset['size'])
        info, _ = rp.validate_dist(downloads)
        report = json.loads((downloads / 'verification.json').read_text())
        rp.require(report.get('customSignerSha256') == FINGERPRINT and report.get('googlePlayUpdaterDisabled') is True,
                   'The release does not preserve the approved custom signing key and update blocking')
        original_apk = downloads / info['apkFileName']
        verify_apk(original_apk, info, commands)
        repo = work / 'repo'
        repo.mkdir()
        apk_name = f'{rp.PACKAGE}_{info["versionCode"]}.apk'
        shutil.copyfile(original_apk, repo / apk_name)
        meta = work / 'metadata'
        meta.mkdir()
        (meta / (rp.PACKAGE + '.yml')).write_text(json.dumps(metadata(info), indent=2) + '\n')
        key_path = work / 'repo-key.p12'
        key_path.write_bytes(key)
        key_path.chmod(0o600)
        env['TGX_FDROID_PASSWORD'] = password
        env['LC_ALL'] = 'C.UTF-8'
        config = {'repo_url': REPO_URL, 'repo_name': 'Scouting2391 Telegram X Custom',
                  'repo_description': 'Custom Telegram X releases with Google Play update prompts disabled.',
                  'repo_keyalias': 'telegram-x-custom', 'keystore': str(key_path),
                  'keystorepass': {'env': 'TGX_FDROID_PASSWORD'}, 'keypass': {'env': 'TGX_FDROID_PASSWORD'},
                  'sdk_path': str(Path(args.build_tools).resolve().parent.parent),
                  'apksigner': str(Path(args.build_tools).resolve() / 'apksigner'),
                  'jarsigner': str(commands['jarsigner']), 'keytool': str(commands['keytool']),
                  'archive_older': 0, 'make_current_version_link': False}
        config_path = work / 'config.yml'
        config_path.write_text(json.dumps(config, indent=2) + '\n')
        config_path.chmod(0o600)
        # Generate v0, v1, and modern v2 signed indexes using the official tools.
        run(['fdroid', 'update'], cwd=work, env=env)
        for name in ('entry.jar', 'index-v2.json', 'index-v1.jar'):
            rp.require((repo / name).is_file(), 'F-Droid did not generate ' + name)
        site.mkdir(parents=True)
        public_repo = site / 'fdroid/repo'
        public_repo.mkdir(parents=True)
        public_names = {apk_name, 'entry.jar', 'entry.json', 'index-v2.json', 'index-v1.json',
                        'index-v1.jar', 'index.jar', 'index.xml'}
        for path in repo.rglob('*'):
            rp.require(not path.is_symlink(), 'Unexpected symlink in generated repository')
            if not path.is_file() or not (path.relative_to(repo).as_posix() in public_names or path.suffix == '.png'):
                continue
            target = public_repo / path.relative_to(repo)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
        public = {'repository': REPOSITORY, 'repositoryUrl': REPO_URL, 'addUrl': ADD_URL,
                  'fingerprint': FINGERPRINT.upper(), 'releaseUrl': release['html_url'],
                  'releaseTag': release['tag_name'], 'app': info,
                  'apkSha256': rp.sha256(original_apk), 'apkSize': original_apk.stat().st_size,
                  'sourceCommit': os.environ.get('GITHUB_SHA', '')}
        (site / 'repo-info.json').write_text(json.dumps(public, indent=2) + '\n')
        import qrcode
        qrcode.make(ADD_URL).save(site / 'add-repo.png')
        page = (ROOT / 'fdroid/index.html').read_text()
        for name, value in {'ADD_URL': ADD_URL, 'REPO_URL': REPO_URL, 'FINGERPRINT': FINGERPRINT.upper(),
                            'BUILD': str(info['versionCode']), 'RELEASE_URL': release['html_url']}.items():
            page = page.replace('{{' + name + '}}', html.escape(value, quote=True))
        (site / 'index.html').write_text(page)
        verify_site(site, commands)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('build', 'verify'))
    parser.add_argument('--site-dir', default='fdroid-site')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'), required=False)
    parser.add_argument('--build-tools', required=True)
    args = parser.parse_args()
    rp.require(args.java_home, 'JAVA_HOME is required')
    if args.command == 'build':
        build(args)
    else:
        verify_site(args.site_dir, tools(args.java_home, args.build_tools))


if __name__ == '__main__':
    main()
