#!/usr/bin/env python3
"""Trusted GitHub release orchestration. No third-party Python dependencies."""
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
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

UPSTREAM = "TGX-Android/Telegram-X"
PACKAGE = "org.thunderdog.challegram"
APKTOOL_URL = "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar"
APKTOOL_SHA256 = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"
TAG = re.compile(r"v(\d+\.\d+\.\d+\.\d+)\Z")
CUSTOM_TAG = re.compile(r"v(\d+\.\d+\.\d+\.\d+)-custom\.([1-9]\d?)\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}\Z")
DIGEST = re.compile(r"sha256:([a-fA-F0-9]{64})\Z")
DOWNLOAD_HOSTS = {"github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}
MAX_DOWNLOAD = 250 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def checked_repo(value):
    require(isinstance(value, str) and REPOSITORY.fullmatch(value), "Invalid GitHub owner/repository")
    require(value.lower() != UPSTREAM.lower(), "Use a separate custom release repository")
    return value


def checked_revision(value):
    text = str(value)
    require(re.fullmatch(r"[1-9]\d?", text), "Revision must be a whole number from 1 to 99")
    return int(text)


def tag_order(value):
    match = CUSTOM_TAG.fullmatch(value or "")
    require(match, "Custom release has an unrecognized tag format")
    return tuple(map(int, match[1].split("."))) + (int(match[2]),)


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Authenticated GitHub API request unexpectedly redirected")


class DownloadRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_download_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def check_download_url(url):
    parsed = urlsplit(url)
    require(parsed.scheme == "https" and parsed.hostname in DOWNLOAD_HOSTS
            and parsed.port in (None, 443) and not parsed.username and not parsed.password,
            "Download must remain on approved GitHub HTTPS hosts")


def api(path, method="GET", data=None, *, raw=None, missing_ok=False):
    require(path.startswith("/repos/"), "Unexpected GitHub API path")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "custom-tgx-release/1",
               "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    host = "https://api.github.com"
    payload = None
    if raw is not None:
        host = "https://uploads.github.com"
        headers["Content-Type"] = "application/octet-stream"
        payload = raw
    elif data is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(data).encode("utf-8")
    req = Request(host + path, data=payload, headers=headers, method=method)
    try:
        with build_opener(NoRedirect()).open(req, timeout=120) as response:
            body = response.read(8 * 1024 * 1024 + 1)
            require(len(body) <= 8 * 1024 * 1024, "GitHub API response too large")
            return json.loads(body)
    except HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        raise RuntimeError(f"GitHub API {method} request failed (HTTP {error.code})") from None


def download(url, path, expected_digest, expected_size=None):
    check_download_url(url)
    require(re.fullmatch(r"[a-fA-F0-9]{64}", expected_digest or ""), "Missing SHA-256 digest")
    if expected_size is not None:
        require(type(expected_size) is int and 0 < expected_size <= MAX_DOWNLOAD,
                "Invalid expected download size")
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    try:
        count = 0
        request = Request(url, headers={"User-Agent": "custom-tgx-release/1"})
        with build_opener(DownloadRedirect()).open(request, timeout=120) as response, partial.open("wb") as out:
            while block := response.read(1024 * 1024):
                count += len(block)
                require(count <= MAX_DOWNLOAD, "Download exceeded size limit")
                out.write(block)
        require(expected_size is None or count == expected_size, "Download size mismatch")
        require(sha256(partial) == expected_digest.lower(), "Downloaded file SHA-256 mismatch")
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)


def select_universal(release):
    require(isinstance(release, dict), "Missing upstream release")
    match = TAG.fullmatch(release.get("tag_name", ""))
    require(match and release.get("draft") is False and release.get("prerelease") is False,
            "Expected a stable upstream version tag")
    require(type(release.get("id")) is int and release["id"] > 0, "Invalid upstream release ID")
    version = match[1]
    filename = f"Telegram-X-{version}.apk"
    candidates = [asset for asset in release.get("assets", []) if asset.get("name") == filename]
    require(len(candidates) == 1, "Expected exactly one official universal APK asset")
    asset = candidates[0]
    digest_value = asset.get("digest")
    require(isinstance(digest_value, str), "Upstream APK lacks GitHub SHA-256 digest")
    digest = DIGEST.fullmatch(digest_value)
    require(digest and asset.get("state") == "uploaded", "Upstream APK lacks GitHub SHA-256 digest")
    expected_url = f"https://github.com/{UPSTREAM}/releases/download/v{version}/{filename}"
    require(asset.get("browser_download_url") == expected_url, "Unexpected upstream asset URL")
    require(type(asset.get("size")) is int and 0 < asset["size"] <= MAX_DOWNLOAD,
            "Invalid upstream APK size")
    return version, asset, digest[1].lower()


def write_outputs(values):
    output_file = os.environ.get("GITHUB_OUTPUT")
    for key, value in values.items():
        text = str(value)
        require("\n" not in text and "\r" not in text, "Invalid Actions output")
        if output_file:
            with open(output_file, "a", encoding="utf-8") as out:
                out.write(f"{key}={text}\n")
    print(json.dumps(values, indent=2))


def revision_for_release(tag, requested, event, overrides):
    require(isinstance(overrides, dict) and len(overrides) <= 100, "Invalid revision overrides")
    for upstream, revision in overrides.items():
        require(isinstance(upstream, str) and TAG.fullmatch(upstream), "Invalid override upstream tag")
        require(type(revision) is int, "Override revision must be an integer")
        checked_revision(revision)
    require(isinstance(tag, str) and TAG.fullmatch(tag), "Invalid selected upstream tag")
    if event in ("push", "schedule"):
        return checked_revision(overrides.get(tag, 1))
    return checked_revision(requested)


def discover(args):
    repository = checked_repo(args.repository)
    release = api(f"/repos/{UPSTREAM}/releases/latest")
    version, _, _ = select_universal(release)
    revisions = Path(__file__).resolve().parent.parent / ".github/custom-revisions.json"
    overrides = json.loads(revisions.read_text(encoding="utf-8")) if revisions.is_file() else {}
    revision = revision_for_release(release["tag_name"], args.revision, os.environ.get("GITHUB_EVENT_NAME", ""), overrides)
    tag = f"v{version}-custom.{revision}"
    existing = api(f"/repos/{repository}/releases/tags/{quote(tag, safe='')}", missing_ok=True)
    if existing:
        require(existing.get("draft") is False and existing.get("prerelease") is False,
                "A draft/prerelease already uses this tag; inspect it before retrying")
    latest = api(f"/repos/{repository}/releases/latest", missing_ok=True)
    newer_exists = latest is not None and tag_order(latest.get("tag_name")) >= tag_order(tag)
    write_outputs({"needs_build": str(not existing and not newer_exists).lower(),
                   "upstream_id": release["id"], "custom_tag": tag, "revision": revision})


def prepare(args):
    require(re.fullmatch(r"[1-9]\d*", args.release_id), "Invalid upstream release ID")
    release = api(f"/repos/{UPSTREAM}/releases/{args.release_id}")
    require(str(release.get("id")) == args.release_id, "Upstream release ID mismatch")
    _, asset, digest = select_universal(release)
    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "upstream-release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
    download(asset["browser_download_url"], out / "original.apk", digest, asset["size"])
    download(APKTOOL_URL, out / "apktool.jar", APKTOOL_SHA256)


def clean_secrets(_args=None):
    for filename in ("key.jks", "password.txt"):
        (Path(".ci/signing") / filename).unlink(missing_ok=True)


def check_compatibility(_args=None):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    java = os.environ.get('JAVA_HOME', '')
    require(java, 'JAVA_HOME is required')
    subprocess.run([sys.executable, 'scripts/build_custom.py', '--check-compatibility',
                    '--input-apk', '.ci/original.apk', '--release-json', '.ci/upstream-release.json',
                    '--output-dir', 'dist', '--work-dir', 'build-work', '--java-home', java,
                    '--android-jar', str(sdk / 'platforms/android-35/android.jar'),
                    '--build-tools', str(sdk / 'build-tools/35.0.0'), '--apktool', '.ci/apktool.jar'], check=True)


def summarize(_args=None):
    path = Path('dist/compatibility-report.json')
    if not path.is_file():
        text = 'No patch compatibility report was produced. Inspect the failed input, SDK, or build step in this run.\n'
    else:
        report = json.loads(path.read_text(encoding='utf-8'))
        status = report.get('status')
        require(status in ('recognized', 'review-required'), 'Invalid compatibility status')
        message = report.get('error') or 'Reviewed patch patterns matched. APK publication still requires all build, signing, and audit checks.'
        text = ('### Telegram X patch compatibility\n\n'
                + 'Upstream: `' + str(report.get('upstreamTag')) + '`\n\n'
                + 'Compatibility: **' + status + '**\n\n'
                + '<pre>' + html.escape(str(message)[:2000]) + '</pre>\n\n'
                + 'The maintenance artifact contains public upstream metadata, matching code excerpts, and any generated patch. '
                + 'An unfamiliar layout stops publication and leaves the current release available.\n')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as out:
            out.write(text)
    print(text)


def build(args):
    revision = checked_revision(args.revision)
    encoded_key = os.environ.pop("TGX_KEYSTORE_BASE64", "")
    password = os.environ.pop("TGX_KEYSTORE_PASSWORD", "")
    require(encoded_key and password, "Missing signing environment secrets")
    require("\n" not in password and "\r" not in password, "Signing password must be one line")
    try:
        key = base64.b64decode("".join(encoded_key.split()), validate=True)
    except ValueError:
        raise ValueError("Signing keystore is not valid base64") from None
    require(0 < len(key) < 1024 * 1024, "Invalid signing keystore size")
    secret_dir = Path(".ci/signing")
    secret_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    key_path = secret_dir / "key.jks"
    password_path = secret_dir / "password.txt"
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    java = os.environ.get("JAVA_HOME", "")
    require(java, "JAVA_HOME is required")
    android_jar = sdk / "platforms/android-35/android.jar"
    build_tools = sdk / "build-tools/35.0.0"
    require(android_jar.is_file() and build_tools.is_dir(), "Android SDK 35 components are missing")
    try:
        key_path.write_bytes(key)
        key_path.chmod(0o600)
        password_path.write_text(password, encoding="utf-8")
        password_path.chmod(0o600)
        subprocess.run([sys.executable, "scripts/build_custom.py",
                        "--input-apk", ".ci/original.apk",
                        "--release-json", ".ci/upstream-release.json",
                        "--output-dir", "dist", "--work-dir", "build-work",
                        "--java-home", java, "--android-jar", str(android_jar),
                        "--build-tools", str(build_tools), "--apktool", ".ci/apktool.jar",
                        "--keystore", str(key_path), "--keystore-password-file", str(password_path),
                        "--key-alias", "telegram-x-custom", "--revision", str(revision)], check=True)
        shutil.copyfile(".ci/upstream-release.json", "dist/upstream-release.json")
        validate_dist(Path("dist"))
    finally:
        clean_secrets()


def validate_dist(directory):
    metadata = json.loads((directory / "tgx-update.json").read_text(encoding="utf-8"))
    verification = json.loads((directory / "verification.json").read_text(encoding="utf-8"))
    require(metadata.get("schemaVersion") == 1 and metadata.get("packageName") == PACKAGE,
            "Unsupported update metadata schema or package")
    require(type(metadata.get("versionCode")) is int and 0 < metadata["versionCode"] < 2100000000,
            "Invalid update version code")
    require(re.fullmatch(r"\d+\.\d+\.\d+\.\d+-universal", metadata.get("versionName", "")),
            "Invalid update version name")
    filename = metadata.get("apkFileName", "")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+\.apk", filename) and not filename.startswith("."),
            "Unsafe APK filename in metadata")
    apk = directory / filename
    require(apk.is_file() and not apk.is_symlink(), "Expected built APK is missing")
    require(verification.get("success") is True and verification.get("apkSha256") == sha256(apk),
            "Build verification or APK digest failed")
    files = [apk, directory / "tgx-update.json", directory / "verification.json",
             directory / "upstream-release.json"]
    require(all(path.is_file() and not path.is_symlink() for path in files), "Incomplete release output")
    return metadata, files


def publish(args):
    repository = checked_repo(args.repository)
    tag_order(args.tag)
    require(re.fullmatch(r"[a-fA-F0-9]{40}", args.commit), "Expected full trusted build commit SHA")
    directory = Path(args.output_dir)
    metadata, files = validate_dist(directory)
    source = json.loads((directory / "upstream-release.json").read_text(encoding="utf-8"))
    version, _, _ = select_universal(source)
    require(metadata["versionName"] == version + "-universal" and args.tag.startswith(f"v{version}-custom."),
            "Release tag and built version differ")
    existing = api(f"/repos/{repository}/releases/tags/{quote(args.tag, safe='')}", missing_ok=True)
    require(existing is None, "Release tag already exists; refusing to overwrite it")
    latest = api(f"/repos/{repository}/releases/latest", missing_ok=True)
    require(latest is None or tag_order(latest.get("tag_name")) < tag_order(args.tag),
            "Refusing to publish an older custom release as latest")
    body = (f"Custom Telegram X {version}. Google Play and official-channel update prompts remain disabled.\n\n"
            f"Upstream release: https://github.com/{UPSTREAM}/releases/tag/v{version}\n\n"
            f"Build source commit: {args.commit}\n\n"
            "Install only if you trust this repository and its signing key. Android still asks for installation confirmation. "
            "See the repository README for notification limitations and update setup.\n")
    release = api(f"/repos/{repository}/releases", "POST", {
        "tag_name": args.tag, "target_commitish": args.commit,
        "name": f"Telegram X {version} (custom {args.tag.rsplit('.', 1)[1]})",
        "body": body, "draft": True, "prerelease": False, "make_latest": "false"})
    release_id = release.get("id")
    require(type(release_id) is int and release_id > 0, "Unexpected created release ID")
    expected = {path.name: (sha256(path), path.stat().st_size) for path in files}
    # Remain a draft on any error; clients only see complete, stable releases.
    for path in files:
        api(f"/repos/{repository}/releases/{release_id}/assets?{urlencode({'name': path.name})}",
            "POST", raw=path.read_bytes())
    for attempt in range(12):
        assets = api(f"/repos/{repository}/releases/{release_id}/assets?per_page=100")
        if uploaded_assets_match(assets, expected):
            published = api(f"/repos/{repository}/releases/{release_id}", "PATCH",
                            {"draft": False, "prerelease": False, "make_latest": "true"})
            require(published.get("draft") is False, "GitHub did not confirm publication")
            print(f"Published https://github.com/{repository}/releases/tag/{args.tag}")
            return
        if attempt < 11:
            time.sleep(5)
    raise RuntimeError("GitHub asset SHA-256 verification failed; release remains a draft")


def uploaded_assets_match(assets, expected):
    if not isinstance(assets, list) or len(assets) != len(expected):
        return False
    names = set()
    for asset in assets:
        name = asset.get("name")
        if name in names or name not in expected:
            return False
        names.add(name)
        digest, size = expected[name]
        if asset.get("state") != "uploaded" or asset.get("size") != size or asset.get("digest") != "sha256:" + digest:
            return False
    return names == set(expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("discover")
    command.add_argument("--repository", required=True)
    command.add_argument("--revision", default="1")
    command.set_defaults(func=discover)
    command = commands.add_parser("prepare")
    command.add_argument("--release-id", required=True)
    command.add_argument("--output-dir", default=".ci")
    command.set_defaults(func=prepare)
    command = commands.add_parser("build")
    command.add_argument("--revision", default="1")
    command.set_defaults(func=build)
    command = commands.add_parser('check-compatibility')
    command.set_defaults(func=check_compatibility)
    command = commands.add_parser('summarize')
    command.set_defaults(func=summarize)
    command = commands.add_parser("clean-secrets")
    command.set_defaults(func=clean_secrets)
    command = commands.add_parser("publish")
    command.add_argument("--repository", required=True)
    command.add_argument("--tag", required=True)
    command.add_argument("--commit", required=True)
    command.add_argument("--output-dir", default="dist")
    command.set_defaults(func=publish)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
