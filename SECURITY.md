# Signing and release trust

Keep the personal keystore, password, and `PRIVATE-signing-key-backup.zip` private. The repository and APK contain only a public certificate digest. Store the CI signing material as GitHub environment secrets, not files, variables, workflow text, or release assets.

Anyone with the signing key can publish APKs accepted as updates by this installation. Trust repository maintainers and review changes to the default branch and workflows before allowing them to use the signing environment. The workflow has no pull-request trigger, does not execute a manual run from another branch, and separates signing from the publisher's repository write token.

The build verifies the official upstream certificate and digest, stops when the known patch shapes change, checks the custom certificate against `signer-sha256.txt`, and audits classes and ZIP contents. The updater also checks the installed certificate, package and versions before opening Android's installer. Android performs its own package installation checks.

The build temporarily writes the signing key and password under `.ci/signing`, removes both in a `finally` block and an unconditional cleanup step, and uploads only named public output files. Never broaden artifact paths to include `.ci`, the work directory, or signing material. Do not add untrusted build steps with access to signing secrets.

The app's update source must be a public GitHub repository. Private repository authentication is intentionally not stored in the application. A loss of the signing key requires a new installation rather than a seamless update; preserve the existing private backup.
