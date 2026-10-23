# Personal Telegram X with GitHub and F-Droid updates

This project creates a custom-signed Telegram X APK from the official stable universal APK on [TGX-Android/Telegram-X](https://github.com/TGX-Android/Telegram-X/releases). It disables Telegram X's Google Play in-app update checks and its official Telegram-channel APK updater, and adds a GitHub Releases updater for this personal build.

The local bootstrap release is based on **0.29.1.1817**, Android 7.0 or later. Google Play Services, Firebase messaging, and the existing Telegram functions remain in the application. This modification disables update requests made by Telegram X; it cannot control notifications issued separately by the Google Play Store app.

## Install and use

Install the APK from the [latest custom release](https://github.com/Scouting2391/telegram-x-custom/releases/latest) over the previously provided custom APK. It uses the same package and personal signing key, with a higher Android version code. Keep the existing application installed. An official Telegram X installation with a different signing key cannot accept this APK as an update.

Starting with custom revision 4, fresh installs automatically use `Scouting2391/telegram-x-custom` with automatic checks enabled. There is no first-launch setup dialog or repository to enter. Older installs with an empty update channel adopt this default once; existing nonempty channels and disabled automatic-check preferences are preserved. The existing **Check for updates** and **Update automatically** actions still open **GitHub updates** if you want to check manually or change these settings.

Automatic checks run at most once every six hours while the app is open. A newer release produces an update prompt. Downloading happens inside the app; Android requires permission to install apps from Telegram X and asks you to confirm installation. Checks do not run as a permanent background service.

**Live update feed:** [Scouting2391/telegram-x-custom](https://github.com/Scouting2391/telegram-x-custom/releases/latest). This channel is built into new installs. For an older APK that still asks for setup, enter `Scouting2391/telegram-x-custom` and choose **Check now**, or install the latest custom APK over it. If the latest release is already installed, the app correctly reports that no newer update is available.

## F-Droid repository

The live [repository page](https://scouting2391.github.io/telegram-x-custom/) includes an Add repository link and a QR code. In F-Droid, open **Settings → Repositories → +** and paste:

```text
https://scouting2391.github.io/telegram-x-custom/fdroid/repo?fingerprint=C0A998D786254CE3DFB2A3CD12698153EEEE9E05A4293E7E8804145EBE750339
```

Search for **Telegram X Custom** after refreshing repositories. This feed contains the same signed APK as GitHub Releases and can update the existing personal build without uninstalling it. It is an independent binary repository, separate from F-Droid's official collection. Google Play update blocking remains in the APK. Google Play Services and Firebase are still included and are disclosed in the package's F-Droid anti-features.

`.github/workflows/fdroid.yml` refreshes the feed automatically after the trusted custom release workflow completes successfully, including its scheduled checks. It can also be run manually. The index is signed in the existing protected `signing` environment, with read-only repository access; a separate GitHub Pages job deploys only the verified public files and has no signing secrets. The existing certificate signs the repository index as well as the unchanged APK, so the pinned repository fingerprint is its SHA-256 fingerprint shown above. The previous private key backup remains necessary for both update channels.

The official `fdroidserver` 2.4.5 tools generate legacy and modern indexes. Before deployment, checks authenticate the signed entry, the v2 index's digest, package identity, version code, APK digest and size, and the expected APK signer. Public artifact uploads exclude configuration files, signing keys, passwords, and internal status logs. Deployment artifacts expire after one day. The first [F-Droid build and deployment](https://github.com/Scouting2391/telegram-x-custom/actions/runs/38661409494) passed all nine policy tests, and the live index and APK were independently downloaded and verified. Android/F-Droid client installation still requires a phone-side check.

GitHub Pages is configured to deploy through GitHub Actions. When reproducing this setup, enable that source in **Settings → Pages** and preserve the existing signing environment and certificate. The feed follows the latest verified custom release.

## Reproduce the GitHub release setup

The current repository and its private signing environment are already configured. These steps document the setup for maintenance or reproducing it in another repository.

1. Create a public repository under your GitHub account. Upload the contents of this project, including `.github/workflows/release.yml`, on its default branch. Do not upload the local signing backup or signing files.
2. In repository **Settings → Environments**, create an environment named `signing`. Restrict deployments to your default branch. Add its secrets:
   - `TGX_KEYSTORE_BASE64`: base64 encoding of the existing `telegram-x-custom.p12` file.
   - `TGX_KEYSTORE_PASSWORD`: the existing key password, without a trailing newline.
3. In repository **Settings → Actions → General**, allow the included actions and grant the release workflow the necessary repository write permission. The publisher job requests `contents: write`; the build job only requests read access.
4. Run **Actions → Build custom Telegram X update → Run workflow** on the default branch, using revision `1`. Inspect the resulting release and verification report.
5. The app defaults to `Scouting2391/telegram-x-custom`. When reproducing this project in a different repository, update `DEFAULT_REPOSITORY` in `GitHubUpdater.java`, or select that channel manually in **GitHub updates**. Use **Check now** to confirm that the feed is accessible.

Keep the existing signing key: generating another key prevents updates to the installed custom APK. The private local backup is supplied separately and is not included in this repository package.

The workflow checks official stable releases every six hours. It downloads the official universal APK, validates its GitHub SHA-256 digest and official signing certificate, applies the reviewed bytecode hooks, compiles the updater sources, signs with your personal key, and audits the result. A separate job uploads a draft release, verifies GitHub asset digests, then publishes it. Future upstream bytecode changes that no longer match the reviewed hooks stop the build for inspection.

The owner's local Codex maintenance task also checks every six hours for upstream changes that need repairs. It can inspect a failed build, adapt the patcher, run regression checks, and commit a validated repair. Changes to the patcher, updater, tests, or release workflow on `main` trigger the release pipeline automatically. This local task needs the computer and Codex app running; the routine GitHub workflow continues independently. Unknown changes can still require review, and the current release remains available when verification fails. See [MAINTENANCE.md](MAINTENANCE.md) for the task's scope and checks.

Failed patch matching produces a public `maintenance-report` Actions artifact with bounded code excerpts and a job summary. To inspect the current upstream APK without publishing, run the release workflow with **Check compatibility with the latest official APK without publishing** enabled. That mode checks authenticated inputs and patch matching without using signing secrets; it does not claim that an APK has been built or tested on a phone.

Revision `1` is the normal scheduled build for a new upstream version. For a correction to the same upstream version, set its exact upstream tag to revision `2` through `99` in `.github/custom-revisions.json` and commit it, or run the workflow manually with that revision. The override applies only to that upstream tag; manual runs retain their explicitly selected revision. An older or already published revision is skipped. Upstream version code plus this revision yields the custom version code, which is currently `1817301` for 0.29.1.1817 revision 1.

GitHub schedules can be delayed and are subject to GitHub's scheduled-workflow inactivity rules. The initial [hosted build and publication](https://github.com/Scouting2391/telegram-x-custom/actions/runs/38482088328) completed successfully on October 13, 2026. Its published APK and metadata were independently downloaded and verified against the existing personal signer.

## What the app accepts

The latest stable GitHub release must have exactly one APK and a `tgx-update.json` asset. The workflow supplies both. The app checks HTTPS GitHub download hosts, asset sizes and SHA-256 digests, the APK's package, newer Android version code, version name, compatible minimum Android version, and the signing certificate against the installed app. Drafts, prereleases, official APKs signed by another key, and damaged downloads are rejected. No GitHub token or signing private key is included in the app.

Example metadata:

```json
{
  "schemaVersion": 1,
  "packageName": "org.thunderdog.challegram",
  "versionCode": 1817301,
  "versionName": "0.29.1.1817-universal",
  "apkFileName": "Telegram-X-0.29.1.1817-github-r1.apk",
  "minSdkVersion": 24
}
```

## Notification error

The reported failure ends in **Firebase Installations Service is unavailable. Please try again later.** The earlier `FCM Registration failed!` text is a wrapper. This build exposes the deeper cause in notification settings while preserving the complete shareable error report. It does not suppress failures or replace Firebase credentials.

Custom revision 2 adds a diagnostic suffix such as `[Last Firebase failure: HTTP 403; PERMISSION_DENIED; API_KEY_ANDROID_APP_BLOCKED]` or `[Last Firebase failure: DNS lookup failed (UnknownHostException)]`. This records the SDK's initial error, which can otherwise be hidden by its retry limiter. Only HTTP status numbers, an explicit allowlist of status/reason codes, and known transport categories are retained. Keys, tokens, installation IDs, response messages, metadata, and URLs are excluded from the added summary. The diagnostic lasts at most fifteen minutes in process memory and is not uploaded automatically by this helper. Existing app error-reporting behavior remains in place.

Custom revision 3 captures the HTTP status before the SDK's optional error-body logger, so responses with absent or unreadable bodies are included. Successful HTTP responses clear earlier failures. When no recent failure was captured, the generic error explicitly includes `[Firebase diagnostics v3: no recent HTTP/transport failure captured]`. This distinguishes the new diagnostics from older builds without assuming a network or authentication cause. The GitHub updates dialog also shows the installed build number; revision 3 of this release is **1817303**.

This is a diagnostic update. It does not establish that notification delivery is fixed. Install it through **GitHub updates → Check now**, retry push registration, and inspect the new first error line to determine the repair. Firebase's network requests, authentication, certificate checks, and retry/backoff behavior are preserved.

Custom revision 4 retains these diagnostics and removes first-launch updater setup. It includes 13 additional preference checks covering fresh installs, upgrades with an empty channel, preservation of existing channels and disabled checks, and one-time initialization. Android's installation permission and confirmation still apply when installing an update.

The supplied log does not prove a signing restriction or identify an HTTP response. Notifications arriving now do not confirm successful Firebase registration: Telegram X can also receive messages while its Telegram connection is active. Use the existing retry action if the error persists. Reliable push delivery with the application idle must be checked on the phone.

## Less intrusive registration warnings

Custom revision 5 gives temporary Firebase registration failures a **15-minute grace period** in the main Settings and Notifications screens. Brief failures remain in the original token state, logs, and shareable diagnostics, without immediately displaying the prominent warning. Retrying does not restart this period; successful registration resets it. The small timestamp/temporary-status preference survives an app restart and contains no error text, messages, tokens, or account identifiers.

After the grace period, the next settings refresh, resume, or token-state update can display a compact warning instead of the long troubleshooting checklist. The existing notification-settings action still opens **Try again** and **Share error details**, including during the grace period. No new background monitoring service or timer is added.

Known configuration errors, Android permission/channel blocks, and recorded notification-display failures stay immediately visible. A blocked chat category also retains its warning when a temporary registration error is quiet. Message-send failure indicators, Firebase requests, retry/backoff behavior, and the real registration state are preserved. This is a presentation change, not a repair to Firebase registration or proof of delivery: the app cannot reliably identify an incoming push that never reached it. Phone behavior still requires a device test.

## Build and verification

Requirements: Python 3.11 or later, JDK 21, Android SDK platform 35 or later, build-tools 35 or later, and the pinned Apktool 3.0.3 JAR. CI validates the fixed Android SDK components provided by the hosted runner. Helpers use Android framework APIs and target minimum API 24.

```sh
python -m unittest discover -s tests -v
python tests/run_updater_tests.py --java-home "$JAVA_HOME" --android-jar "$ANDROID_HOME/platforms/android-35/android.jar"
python scripts/build_custom.py \
  --input-apk original.apk --release-json upstream-release.json \
  --output-dir dist --work-dir build-work \
  --java-home "$JAVA_HOME" --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" --apktool apktool.jar \
  --keystore /private/telegram-x-custom.p12 --keystore-password-file /private/password.txt \
  --revision 1
```

Use a fresh build work directory. This is a patch of the official compiled APK plus new Java helper code, rather than a complete recompilation of Telegram X's native dependencies. This repository supplies the helper source and patching procedure. The build produces `dist/bytecode-changes.patch`; the release assets include upstream metadata and the verification report. The upstream full application source remains available at its release tag. GPL-3.0 licensing applies; see `LICENSE` and the upstream project for additional component notices.

Local and hosted validation of revision 2 passed 46 updater trust checks, 45 diagnostic checks, and seven release-policy tests. The final APK audit checks all original classes, limits changed methods to the intended hooks, verifies resources and native libraries stayed byte-identical, and verifies Android v2/v3 signatures and alignment. The [revision 2 hosted build and publication](https://github.com/Scouting2391/telegram-x-custom/actions/runs/38515562992) passed, and the public download was independently verified against the existing personal signer. Revision 2 adds reviewed hooks for Firebase's existing HTTP log string and discarded IOException categories. Firebase registration and background delivery require a phone test.

The [revision 3 hosted build and publication](https://github.com/Scouting2391/telegram-x-custom/actions/runs/38517518743) passed 46 updater trust checks, 58 diagnostic checks, seven release-policy tests, and the same APK audit. The public APK was independently downloaded and verified with version code **1817303** and the existing personal signer. Its SHA-256 is `42d1a69107eaee76d0a796e3057ccc073995eff33939161ce38b5df85d7ee9c7`. After installing, confirm **Installed build: 1817303** in GitHub updates. If the warning remains, retry registration and share the first `#firebase_error` line, including its bracketed text, promptly after retrying.
