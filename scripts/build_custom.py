#!/usr/bin/env python3
"""Build a verified custom GitHub-update APK. Standard-library Python only.

Recognize the reviewed upstream updater shape, fail on unfamiliar bytecode,
compile framework-only helpers, and preserve the existing personal signer.
"""
from __future__ import annotations
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile
import zlib

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'org.thunderdog.challegram'
HELPER = 'Lorg/thunderdog/challegram/github/GitHubUpdater;'
DIAGNOSTICS = 'Lorg/thunderdog/challegram/github/PushDiagnostics;'
OFFICIAL_CERT = 'eb801a303294ba02a84d030ebb1216c438ac985ad4b89208e32d7a7190dcdd33'
APKTOOL_HASH = 'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(command, *, quiet=False):
    result = subprocess.run([str(x) for x in command], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    if result.returncode or not quiet:
        print(result.stdout, flush=True)
    require(result.returncode == 0, f'Tool failed: {Path(str(command[0])).name}')
    return result.stdout


def methods(text):
    return {m[1]: m[0] for m in re.finditer(r'^\.method ([^\n]+)\n.*?^\.end method', text, re.M | re.S)}


def replace_method(text, signature, replacement):
    pattern = re.compile(r'^\.method ' + re.escape(signature) + r'\n.*?^\.end method', re.M | re.S)
    result, count = pattern.subn(lambda _: replacement, text)
    require(count == 1, f'Expected one method: {signature}')
    return result


def normalized(text):
    text = re.sub(r'^(\.field [^\n]+:(?:Z|B|S|C|I|J|F|D)) = (?:false|0x0[tLs]?|0\.0f|0\.0)$', r'\1', text, flags=re.M)
    text = re.sub(r'^(\.field [^\n]+:(?:L[^\n]+;|\[[^\n]+)) = null$', r'\1', text, flags=re.M)
    return '\n'.join(line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith('#'))


def class_name(text):
    match = re.search(r'^\.class (?:[^\n]+ )?(L[^\n]+;)$', text, re.M)
    require(match, 'Missing class declaration')
    return match[1]


def label_safety(full, block):
    outside = full.replace(block, '', 1)
    for label in re.findall(r'^\s*(:[\w_]+)\s*$', block, re.M):
        require(not re.search(re.escape(label) + r'(?![\w_])', outside),
                f'Cannot remove label referenced outside reviewed block: {label}')


def patch_hooks(files, resources):
    changes = {}
    expected = {}

    def save(relative, text, signatures):
        changes[relative] = text
        expected.setdefault(relative, set()).update(signatures)

    matches = [(p, t) for p, t in files.items() if 'Unable to initialize Google Play update manager' in t]
    require(len(matches) == 1, 'Unknown upstream Google Play updater constructor')
    path, original = matches[0]
    updater = class_name(original)
    ms = methods(original)
    ctors = [(s, t) for s, t in ms.items() if 'Unable to initialize Google Play update manager' in t]
    require(len(ctors) == 1, 'Unknown constructor shape')
    ctor_sig, ctor = ctors[0]
    activity = re.search(r'<init>\((L[^;]+;)\)V', ctor_sig)[1]
    context_field = re.search(r'iput-object p1, p0, ' + re.escape(updater) + r'->(\w+):' + re.escape(activity), ctor)[1]
    list_match = re.search(r'new-instance v1, (L[^;]+;).*?iput-object v1, p0, ' + re.escape(updater) + r'->(\w+):\1', ctor, re.S)
    require(list_match, 'Unknown listener-list initialization')
    list_type, list_field = list_match[1], list_match[2]
    state_field = re.search(r'iput-byte v0, p0, ' + re.escape(updater) + r'->(\w+):B', ctor)[1]
    manager_match = re.findall(r'iput-object v2, p0, ' + re.escape(updater) + r'->(\w+):(L[^;]+;)', ctor)
    require(len(manager_match) == 1, 'Unknown Play update manager field')
    manager_field, manager_type = manager_match[0]
    require(len(re.findall(r'^\.field public final \w+:L', original, re.M)) == 3,
            'Updater acquired additional final fields; manual review required')
    check_matches = [(s, t) for s, t in ms.items() if 'requestUpdateInfo(%s)' in t]
    require(len(check_matches) == 1, 'Unknown update-check method')
    check_sig, check_body = check_matches[0]
    check_name = re.search(r'(\w+)\(\)V$', check_sig)[1]
    calls = list(dict.fromkeys(re.findall(r'invoke-virtual \{p0\}, ' + re.escape(updater) + r'->(\w+)\(\)V', check_body)))
    require(len(calls) == 2, 'Unknown Telegram fallback / offer-update branches')
    channel_name = calls[0]
    channel_sig = next(s for s in ms if s.endswith(f' {channel_name}()V'))
    require(re.search(r'iget-object v1, p0, ' + re.escape(updater) + '->' + context_field, ms[channel_sig]),
            'Unknown Telegram-channel checker')
    replacement = f'''.method {ctor_sig}
    .locals 2
    invoke-direct {{p0}}, Ljava/lang/Object;-><init>()V
    const/4 v0, 0x0
    iput-byte v0, p0, {updater}->{state_field}:B
    iput-object p1, p0, {updater}->{context_field}:{activity}
    new-instance v1, {list_type}
    invoke-direct {{v1}}, {list_type}-><init>()V
    iput-object v1, p0, {updater}->{list_field}:{list_type}
    iput-object v0, p0, {updater}->{manager_field}:{manager_type}
    return-void
.end method'''
    patched = replace_method(original, ctor_sig, replacement)
    patched = replace_method(patched, check_sig, f'''.method {check_sig}
    .locals 1
    iget-object v0, p0, {updater}->{context_field}:{activity}
    invoke-static {{v0}}, {HELPER}->check(Landroid/app/Activity;)V
    return-void
.end method''')
    patched = replace_method(patched, channel_sig, f'''.method {channel_sig}
    .locals 0
    return-void
.end method''')
    save(path, patched, [ctor_sig, check_sig, channel_sig])

    # R8 inlines the Play result handler into BaseActivity.
    activity_paths = [(p, t) for p, t in files.items() if class_name(t) == activity]
    require(len(activity_paths) == 1, 'Activity class not found')
    act_path, act_text = activity_paths[0]
    result_sig = 'public onActivityResult(IILandroid/content/Intent;)V'
    result = methods(act_text).get(result_sig, '')
    needle = re.search(r'    iget-object p0, p0, ' + re.escape(activity) + r'->\w+:' + re.escape(updater), result)
    require(needle and '.locals 3' in result, 'Unknown inlined update-result handler')
    guard = needle[0] + f'''
    iget-object v0, p0, {updater}->{manager_field}:{manager_type}
    if-nez v0, :github_update_manager_present
    return-void
    :github_update_manager_present'''
    result = result.replace(needle[0], guard, 1)
    save(act_path, replace_method(act_text, result_sig, result), [result_sig])

    # The settings controller is in another DEX. Both old updater actions open configuration.
    check_id = resource_id(resources, 'btn_checkUpdates')
    auto_id = resource_id(resources, 'btn_updateAutomatically')
    settings = [(p, t) for p, t in files.items() if check_id in t and f'{updater}->{check_name}()V' in t
                and 'public final onClick(Landroid/view/View;)V' in methods(t)]
    require(len(settings) == 1, 'Unknown settings controller update actions')
    setting_path, setting_text = settings[0]
    click_sig = 'public final onClick(Landroid/view/View;)V'
    click = methods(setting_text)[click_sig]
    def branch(code, rid, activity_register=None):
        start = re.search(r'    const (v\d+), ' + re.escape(rid) + r'\n(?:\s*\.line \d+\n|\s*\n)*    if-ne (v\d+), \1, (:[\w_]+)\n', code)
        require(start, f'Unknown settings branch for {rid}')
        end = re.search(r'^    ' + re.escape(start[3]) + r'\s*$', code[start.end():], re.M)
        require(end, 'Missing settings branch target')
        body = code[start.end():start.end() + end.start()]
        if activity_register is None:
            context = re.search(r'iget-object v\d+, (v\d+), ' + re.escape(activity) + r'->\w+:' + re.escape(updater), body)
            require(context, 'Unknown settings activity register')
            activity_register = context[1]
        label_safety(code, body)
        insert = f'\n    invoke-static {{{activity_register}}}, {HELPER}->showSettings(Landroid/app/Activity;)V\n    return-void\n\n'
        return code[:start.end()] + insert + code[start.end() + end.start():], activity_register
    click, register = branch(click, check_id)
    click, _ = branch(click, auto_id, register)
    save(setting_path, replace_method(setting_text, click_sig, click), [click_sig])

    # Firebase's formatter is inlined into two failure callbacks. Preserve full throwable reporting.
    diagnostic_matches = [(p, t) for p, t in files.items() if '(?<=: )[A-Z_]+$' in t]
    require(len(diagnostic_matches) == 1, 'Unknown Firebase error formatter')
    diag_path, diag_text = diagnostic_matches[0]
    diag_methods = [(s, t) for s, t in methods(diag_text).items() if '(?<=: )[A-Z_]+$' in t]
    require(len(diag_methods) == 1, 'Unknown Firebase failure callback')
    diag_sig, diag_body = diag_methods[0]
    require(diag_sig.endswith('(Ljava/lang/Exception;)V'), 'Unreviewed Firebase callback parameters')
    require('FirebaseMessaging: installation ID fetch failed (' in diag_body and 'FirebaseMessaging: registration failed (' in diag_body,
            'Unknown Firebase formatter contexts')
    blocks = list(re.finditer(r'    invoke-virtual \{p1\}, Ljava/lang/Throwable;->getMessage\(\)Ljava/lang/String;\n.*?(?=    new-instance v2, Ljava/lang/StringBuilder;)', diag_body, re.S))
    require(len(blocks) == 2, 'Expected two Firebase formatter blocks')
    for block in reversed(blocks):
        label_safety(diag_body, block[0])
        replacement = f'    invoke-static {{p1}}, {DIAGNOSTICS}->describe(Ljava/lang/Throwable;)Ljava/lang/String;\n    move-result-object v0\n\n'
        diag_body = diag_body[:block.start()] + replacement + diag_body[block.end():]
    save(diag_path, replace_method(diag_text, diag_sig, diag_body), [diag_sig])

    # Preserve the initial server response that the SDK otherwise exposes only in logcat.
    # The helper receives an existing SDK log string and retains only status/allowlisted reasons.
    marker = 'Error when communicating with the Firebase Installations server API. HTTP response: [%d %s: %s]'
    clients = [(p, t) for p, t in files.items() if marker in t]
    require(len(clients) == 1, 'Unknown Firebase installation HTTP logger')
    client_path, client_text = clients[0]
    client_name = class_name(client_text)
    log_methods = [(s, t) for s, t in methods(client_text).items() if marker in t]
    require(len(log_methods) == 1, 'Unknown Firebase HTTP log method')
    log_sig, log_body = log_methods[0]
    require(log_sig.endswith('(Ljava/net/HttpURLConnection;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V'),
            'Unreviewed Firebase logger parameters')
    formatted = list(re.finditer(r'    invoke-static \{[^}]+\}, Ljava/lang/String;->format\(Ljava/lang/String;\[Ljava/lang/Object;\)Ljava/lang/String;\n(?:\s*\.line \d+\n|\s*\n)*    move-result-object (v\d+)', log_body))
    require(len(formatted) == 1, 'Unknown Firebase HTTP response formatting')
    item = formatted[0]
    log_body = log_body[:item.end()] + f'\n    invoke-static {{{item[1]}}}, {DIAGNOSTICS}->recordFirebaseResponse(Ljava/lang/String;)V\n' + log_body[item.end():]
    save(client_path, replace_method(client_text, log_sig, log_body), [log_sig])

    # The SDK discards IO exceptions while retrying. Capture their category in separate
    # catch-only trampolines so normal 5xx retry jumps and all retry limits are unchanged.
    log_call = client_name + '->' + re.search(r'(\w+)\(', log_sig)[1] + '(Ljava/net/HttpURLConnection;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V'
    callers = [(p, t) for p, t in files.items() if log_call in t and
               'Firebase Installations Service is unavailable. Please try again later.' in t]
    require(len(callers) == 1, 'Unknown Firebase network executor')
    net_path, net_text = callers[0]
    net_methods = [(s, t) for s, t in methods(net_text).items() if log_call in t and
                   'Ljava/net/HttpURLConnection;->getResponseCode()I' in t]
    require(len(net_methods) == 2, 'Expected reviewed Firebase create/token methods')
    patched_net = net_text
    for index, (signature, body) in enumerate(net_methods):
        local = re.search(r'^    \.locals (\d+)\s*$', body, re.M)
        require(local and int(local[1]) < 240, 'Unknown Firebase local register layout')
        count = int(local[1])
        require(all(int(v) < count for v in re.findall(r'\bv(\d+)\b', body)), 'Firebase parameters use explicit local aliases')
        handlers = sorted(set(re.findall(r'^    \.catch Ljava/io/IOException; [^\n]+ (:[\w_]+)$', body, re.M)))
        require(1 <= len(handlers) <= 2, 'Unknown Firebase IOException handlers')
        require(not any('move-exception' in body[body.index('    ' + label + '\n') + len(label) + 5:].split('\n', 4)[0] for label in handlers),
                'Firebase already preserves IOException details')
        tail = body[:body.rfind('.end method')].strip()
        require(re.search(r'(?:throw|return-object) [vp]\d+$', tail), 'Firebase method may fall through into added handlers')
        body = body[:local.start(1)] + str(count + 1) + body[local.end(1):]
        # The SDK's error logger skips unreadable or absent bodies. Observe the
        # response code already returned to the SDK before that optional logger.
        responses = list(re.finditer(r'    invoke-virtual \{v\d+\}, Ljava/net/HttpURLConnection;->getResponseCode\(\)I\n(?:\s*\.line \d+\n|\s*\n)*    move-result (v\d+)', body))
        require(len(responses) == 1, 'Expected one Firebase HTTP status result per network method')
        response = responses[0]
        require(re.match(r'(?:\s*\.line \d+\n|\s*\n)*    invoke-virtual \{v\d+, ' + re.escape(response[1]) + r'\}, L\w+;->\w+\(I\)V', body[response.end():]),
                'Unknown Firebase retry-limiter status update')
        body = body[:response.end()] + f'\n    invoke-static/range {{{response[1]} .. {response[1]}}}, {DIAGNOSTICS}->recordFirebaseHttpStatus(I)V\n' + body[response.end():]
        additions = []
        for handler_index, label in enumerate(handlers):
            diagnostic_label = f':firebase_io_diagnostic_{index}_{handler_index}'
            require(diagnostic_label not in body, 'Diagnostic handler already present')
            body, replaced = re.subn(r'(^    \.catch Ljava/io/IOException; [^\n]+ )' + re.escape(label) + r'$',
                                     lambda m: m[1] + diagnostic_label, body, flags=re.M)
            require(replaced >= 1, 'Missing Firebase IOException catch')
            additions.append(f'''    {diagnostic_label}
    move-exception v{count}
    invoke-static/range {{v{count} .. v{count}}}, {DIAGNOSTICS}->recordFirebaseTransportFailure(Ljava/lang/Throwable;)V
    goto/16 {label}
''')
        body = body[:body.rfind('.end method')] + '\n' + '\n'.join(additions) + '.end method'
        patched_net = replace_method(patched_net, signature, body)
    save(net_path, patched_net, [s for s, _ in net_methods])
    return changes, expected


def resource_id(resources, name):
    matches = re.findall(r'resource (0x[0-9a-f]+) id/' + re.escape(name) + r'\b', resources)
    require(len(matches) == 1, f'Unknown resource ID: {name}')
    return matches[0]


def patch_manifest(data, before, after):
    result = bytearray(data)
    require(struct.unpack_from('<H', result)[0] == 3, 'Expected binary XML manifest')
    offset = struct.unpack_from('<H', result, 2)[0]
    resources = []
    found = 0
    while offset < len(result):
        typ, header, size = struct.unpack_from('<HHI', result, offset)
        require(size >= header >= 8 and offset + size <= len(result), 'Invalid XML chunk')
        if typ == 0x0180:
            resources = list(struct.unpack_from('<' + 'I' * ((size - header) // 4), result, offset + header))
        if typ == 0x0102:
            attr_start, attr_size, count = struct.unpack_from('<HHH', result, offset + 24)
            require(attr_size == 20, 'Unsupported XML attribute size')
            for i in range(count):
                loc = offset + 16 + attr_start + i * attr_size
                name_index = struct.unpack_from('<I', result, loc + 4)[0]
                if name_index < len(resources) and resources[name_index] == 0x0101021b:
                    require(result[loc + 15] in (0x10, 0x11), 'Non-integer versionCode')
                    require(struct.unpack_from('<I', result, loc + 16)[0] == before, 'Unexpected original versionCode')
                    struct.pack_into('<I', result, loc + 16, after)
                    found += 1
        offset += size
    require(found == 1, 'Expected one manifest versionCode')
    return bytes(result)


def parse_badging(text):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text)
    require(match, 'APK package metadata missing')
    minimum = re.search(r"minSdkVersion:'(\d+)'", text)
    return match[1], int(match[2]), match[3], int(minimum[1])


def signatures(text):
    return sorted(set(re.findall(r'certificate SHA-256 digest: ([a-f0-9]{64})', text)))


def collect(decoded):
    return {p.relative_to(decoded).as_posix(): p.read_text(encoding='utf-8') for p in decoded.glob('smali*/**/*.smali')}


def compatibility_report(files, tag, digest, changes=None, expected=None, error=None):
    """Public evidence for maintenance; never receives signing keys or configuration."""
    markers = ('Unable to initialize Google Play update manager', 'requestUpdateInfo(%s)',
               '(?<=: )[A-Z_]+$', 'Error when communicating with the Firebase Installations server API.',
               'FirebaseMessaging: registration failed (')
    candidates = []
    for path, source in files.items():
        found = [marker for marker in markers if marker in source]
        if not found:
            continue
        lines = source.splitlines()
        positions = [i for i, line in enumerate(lines) if any(marker in line for marker in found)]
        snippets = []
        for position in positions[:3]:
            snippets.append({'line': position + 1, 'source': '\n'.join(lines[max(0, position - 12):position + 28])[:12000]})
        candidates.append({'path': path, 'class': class_name(source), 'markers': found, 'snippets': snippets})
        if len(candidates) >= 12:
            break
    return {'schemaVersion': 1, 'upstreamTag': tag, 'inputApkSha256': digest,
            'status': 'review-required' if error is not None else 'recognized',
            'error': (type(error).__name__ + ': ' + str(error))[:1500] if error is not None else None,
            'changedMethods': {p: sorted(signatures) for p, signatures in (expected or {}).items()},
            'changedClassCount': len(changes or {}), 'candidates': candidates,
            'runtimeDeviceTest': 'not performed',
            'publicationAllowedByThisReport': False}


def audit_classes(original, verified, expected):
    # DEX placement may change to remain below the 65,536 method-reference limit.
    indexed = {p.split('/', 1)[1]: t for p, t in verified.items()}
    require(len(indexed) == len(verified), 'Duplicate classes across DEX files')
    original_suffixes = {p.split('/', 1)[1] for p in original}
    added = {p for p in verified if p.split('/', 1)[1] not in original_suffixes}
    require(original_suffixes <= set(indexed), 'Original classes disappeared')
    # D8's debug build records lambda metadata using this marker annotation.
    compiler_annotation = 'com/android/tools/r8/annotations/LambdaMethod.smali'
    require(added and all('/org/thunderdog/challegram/github/' in p or
                         p.split('/', 1)[1] == compiler_annotation for p in added),
            'Unexpected added classes')
    changed = {}
    for path, before in original.items():
        after = indexed[path.split('/', 1)[1]]
        if normalized(before) == normalized(after):
            continue
        require(path in expected, f'Unexpected class change: {path}')
        bm, am = methods(before), methods(after)
        require(bm.keys() == am.keys(), 'Original method set changed')
        diff = {key for key in bm if normalized(bm[key]) != normalized(am[key])}
        require(diff == expected[path], f'Unexpected changed methods: {path}: {diff}')
        pattern = r'^\.method [^\n]+\n.*?^\.end method'
        require(normalized(re.sub(pattern, '', before, flags=re.M | re.S)) == normalized(re.sub(pattern, '', after, flags=re.M | re.S)),
                f'Original class/field declaration changed: {path}')
        changed[path] = sorted(diff)
    require(set(changed) == set(expected), 'Intended hook missing from final APK')
    return changed, sorted(added)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('input-apk', 'release-json', 'output-dir', 'work-dir', 'java-home', 'android-jar', 'build-tools', 'apktool'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--keystore')
    parser.add_argument('--keystore-password-file')
    parser.add_argument('--check-compatibility', action='store_true', help='Validate patch matching without signing or building an APK')
    parser.add_argument('--key-alias', default='telegram-x-custom')
    parser.add_argument('--revision', type=int, default=1)
    parser.add_argument('--reuse-decode', action='store_true', help='Local bootstrap only: use pre-decoded input in work-dir/decoded')
    args = parser.parse_args()
    require(1 <= args.revision <= 99, 'Custom revision must be 1..99')
    require(args.check_compatibility or (args.keystore and args.keystore_password_file), 'Signing files are required for an APK build')
    paths = {name: Path(getattr(args, name.replace('-', '_'))).resolve() for name in ('input-apk', 'release-json', 'output-dir', 'work-dir', 'java-home', 'android-jar', 'build-tools', 'apktool', 'keystore', 'keystore-password-file') if getattr(args, name.replace('-', '_'))}
    work, out = paths['work-dir'], paths['output-dir']
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    require(sha(paths['apktool'].read_bytes()) == APKTOOL_HASH, 'Unverified Apktool binary')
    release = json.loads(paths['release-json'].read_text(encoding='utf-8-sig'))
    require(release.get('draft') is False and release.get('prerelease') is False, 'Require stable upstream release')
    tag = release.get('tag_name', '')
    require(re.fullmatch(r'v\d+\.\d+\.\d+\.\d+', tag), 'Unsupported upstream tag')
    assets = [a for a in release['assets'] if a['name'] == f'Telegram-X-{tag[1:]}.apk']
    require(len(assets) == 1, 'Expected one official universal APK')
    asset = assets[0]
    require(asset.get('digest', '').startswith('sha256:') and asset['digest'][7:] == sha(paths['input-apk'].read_bytes()), 'Input APK digest differs from GitHub')
    require(paths['input-apk'].stat().st_size == asset['size'], 'Input APK length differs from GitHub')
    exe = '.exe' if os.name == 'nt' else ''
    java, javac = paths['java-home'] / ('bin/java' + exe), paths['java-home'] / ('bin/javac' + exe)
    tools = paths['build-tools']
    def sign_tool(*params):
        return run([java, '-jar', tools / 'lib/apksigner.jar', *params], quiet=True)
    original_sign = sign_tool('verify', '--verbose', '--print-certs', paths['input-apk'])
    require(signatures(original_sign) == [OFFICIAL_CERT], 'Unrecognized official signing certificate')
    original_badging = run([tools / ('aapt2' + exe), 'dump', 'badging', paths['input-apk']], quiet=True)
    package, code, version, minimum = parse_badging(original_badging)
    require(package == PACKAGE and version == tag[1:] + '-universal' and minimum == 24, 'Unreviewed APK variant')
    require(code % 1000 == 300 and code + args.revision < 2100000000, 'Unknown upstream version code scheme')
    decoded = work / 'decoded'
    if args.reuse_decode:
        require(decoded.is_dir(), 'Pre-decoded input is missing')
        with zipfile.ZipFile(paths['input-apk']) as z:
            require((decoded / 'AndroidManifest.xml').read_bytes() == z.read('AndroidManifest.xml') and
                    (decoded / 'resources.arsc').read_bytes() == z.read('resources.arsc'), 'Pre-decoded input differs')
    else:
        require(not decoded.exists(), 'Use a fresh work directory')
        run([java, '-jar', paths['apktool'], 'd', paths['input-apk'], '-r', '-p', work / 'framework', '-o', decoded])
    print('Discovering and applying reviewed code hooks…', flush=True)
    original = collect(decoded)
    resources = run([tools / ('aapt2' + exe), 'dump', 'resources', paths['input-apk']], quiet=True)
    try:
        changes, expected = patch_hooks(original, resources)
    except Exception as error:
        report = compatibility_report(original, tag, asset['digest'][7:], error=error)
        (out / 'compatibility-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print('Patch compatibility requires maintenance:\n' + json.dumps(report, indent=2), flush=True)
        raise
    report = compatibility_report(original, tag, asset['digest'][7:], changes, expected)
    (out / 'compatibility-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if args.check_compatibility:
        print(json.dumps({k: v for k, v in report.items() if k != 'candidates'}, indent=2), flush=True)
        return
    patches = []
    for relative, text in changes.items():
        (decoded / relative).write_text(text, encoding='utf-8')
        patches.extend(difflib.unified_diff(original[relative].splitlines(True), text.splitlines(True), fromfile='a/' + relative, tofile='b/' + relative))
    (out / 'bytecode-changes.patch').write_text(''.join(patches), encoding='utf-8')
    # Upstream fills its primary DEX. Move a bounded set of unchanged classes to a
    # new DEX so the few new external helper references do not overflow its index.
    with zipfile.ZipFile(paths['input-apk']) as z:
        existing_dex = [n for n in z.namelist() if re.fullmatch(r'classes(?:\d+)?\.dex', n)]
    relocated_dex = f'classes{len(existing_dex) + 1}.dex'
    relocated_folder = decoded / ('smali_' + relocated_dex[:-4])
    require(not relocated_folder.exists(), 'Use a fresh decoded tree for class relocation')
    candidates = sorted((p for p in original if p.startswith('smali/') and p not in changes),
                        key=lambda p: (-original[p].count('\n.method '), p))[:16]
    require(len(candidates) == 16, 'Insufficient classes for DEX index balancing')
    for relative in candidates:
        target = relocated_folder / relative.split('/', 1)[1]
        target.parent.mkdir(parents=True, exist_ok=True)
        (decoded / relative).rename(target)
    run([java, '-jar', paths['apktool'], 'b', decoded, '--no-apk', '-p', work / 'framework'])
    classes = work / 'helper-classes'
    classes.mkdir(exist_ok=True)
    sources = sorted((ROOT / 'updater/src').rglob('*.java'))
    require(sources, 'Updater helper source missing')
    run([javac, '--release', '8', '-Xlint:-options', '-cp', paths['android-jar'], '-d', classes, *sources])
    helper_dex = work / 'helper-dex'
    helper_dex.mkdir(exist_ok=True)
    run([java, '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', paths['android-jar'], '--min-api', '24', '--output', helper_dex, *sorted(classes.rglob('*.class'))])
    replacements = {}
    for relative in changes:
        folder = relative.split('/')[0]
        dex = 'classes.dex' if folder == 'smali' else folder.replace('smali_', '') + '.dex'
        replacements[dex] = (decoded / 'build/apk' / dex).read_bytes()
    with zipfile.ZipFile(paths['input-apk']) as z:
        next_dex = f'classes{len(existing_dex) + 2}.dex'
        replacements[relocated_dex] = (decoded / 'build/apk' / relocated_dex).read_bytes()
        replacements[next_dex] = (helper_dex / 'classes.dex').read_bytes()
        replacements['AndroidManifest.xml'] = patch_manifest(z.read('AndroidManifest.xml'), code, code + args.revision)
        unsigned = work / 'unsigned.apk'
        with zipfile.ZipFile(unsigned, 'w') as target:
            for entry in z.infolist():
                upper = entry.filename.upper()
                if upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF'):
                    continue
                target.writestr(entry, replacements.get(entry.filename, z.read(entry)))
            target.writestr(next_dex, replacements[next_dex], compress_type=zipfile.ZIP_DEFLATED)
            target.writestr(relocated_dex, replacements[relocated_dex], compress_type=zipfile.ZIP_DEFLATED)
    aligned = work / 'aligned.apk'
    run([tools / ('zipalign' + exe), '-P', '16', '4', unsigned, aligned])
    filename = f'Telegram-X-{tag[1:]}-github-r{args.revision}.apk'
    final = out / filename
    sign_tool('sign', '--ks', paths['keystore'], '--ks-type', 'PKCS12', '--ks-key-alias', args.key_alias,
              '--ks-pass', 'file:' + str(paths['keystore-password-file']), '--v1-signing-enabled', 'false',
              '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', final, aligned)
    signed_report = sign_tool('verify', '--verbose', '--print-certs', final)
    expected_cert = (ROOT / 'signer-sha256.txt').read_text().strip()
    require(signatures(signed_report) == [expected_cert], 'Custom signer changed; refusing release')
    run([tools / ('zipalign' + exe), '-c', '-P', '16', '4', final])
    final_badging = run([tools / ('aapt2' + exe), 'dump', 'badging', final], quiet=True)
    require(parse_badging(final_badging) == (package, code + args.revision, version, minimum), 'Final APK metadata differs')
    verified = work / 'verified'
    run([java, '-jar', paths['apktool'], 'd', final, '-r', '-p', work / 'framework', '-o', verified])
    print('Auditing all original and added classes…', flush=True)
    changed_methods, added_classes = audit_classes(original, collect(verified), expected)
    with zipfile.ZipFile(paths['input-apk']) as old, zipfile.ZipFile(final) as new:
        require(new.testzip() is None, 'ZIP CRC error')
        require(set(new.namelist()) - set(old.namelist()) == {next_dex, relocated_dex}, 'Unexpected APK entries added')
        require(set(old.namelist()) <= set(new.namelist()), 'Unexpected APK entries removed')
        changed_entries = [n for n in old.namelist() if old.read(n) != new.read(n)]
        require(set(changed_entries) == set(replacements) - {next_dex, relocated_dex}, 'Unexpected APK content changed')
        for name in existing_dex + [next_dex, relocated_dex]:
            data = new.read(name)
            require(struct.unpack_from('<I', data, 8)[0] == zlib.adler32(data[12:]) & 0xffffffff and data[12:32] == hashlib.sha1(data[32:]).digest(), f'DEX checksum failure: {name}')
    metadata = {'schemaVersion': 1, 'packageName': package, 'versionCode': code + args.revision, 'versionName': version,
                'apkFileName': filename, 'minSdkVersion': minimum}
    report = {'success': True, 'apkSha256': sha(final.read_bytes()), 'apkFileName': filename, 'upstreamTag': tag,
              'upstreamApkSha256': asset['digest'][7:], 'customSignerSha256': expected_cert, 'versionCode': code + args.revision,
              'changedZipEntries': changed_entries, 'addedDex': [relocated_dex, next_dex], 'relocatedUnchangedClasses': candidates, 'changedMethods': changed_methods,
              'originalClassesAudited': len(original), 'addedClasses': added_classes,
              'googlePlayUpdaterDisabled': True, 'officialChannelUpdaterDisabled': True,
              'githubUpdaterConfigurableInApp': True, 'firebaseCauseDiagnosticsEnabled': True,
              'firebaseHttpAndTransportDiagnosticsEnabled': True,
              'firebaseBodyIndependentHttpDiagnosticsEnabled': True,
              'signatureVerification': 'Android v2/v3 passed', 'alignment': '4-byte and 16 KiB native alignment passed',
              'runtimeDeviceTest': 'not performed', 'buildMethod': 'reviewed APK patch plus compiled Android helper'}
    (out / 'tgx-update.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    (out / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    (out / 'signature-verification.txt').write_text(signed_report, encoding='utf-8')
    (out / 'SHA256SUMS.txt').write_text(report['apkSha256'] + '  ' + filename + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
