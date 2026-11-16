# Android release signing (persistent key)

This repository already has a release-signing workflow. It expects two GitHub Actions repository secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded PKCS#12 keystore bytes.
- `ANDROID_KEYSTORE_PASSWORD`: password for both the PKCS#12 store and the `urban-alchemy` key alias.

The Gradle release configuration uses:
- Store type: `pkcs12`
- Key alias: `urban-alchemy`
- Application ID: `org.urbanalchemy.messenger.stable`

## Critical: preserve the signing key

The keystore is the app's long-term Android signing identity. Keep at least two secure offline backups. Never commit the keystore, its password, or a base64 copy to Git. Do not regenerate it for later releases: Android will reject an update signed by a different key.

If a stable app has already been distributed with another signing key, stop and resolve the signing lineage before publishing updates.

## 1. Generate the keystore once

On a trusted computer with JDK 17 installed, run:

```sh
keytool -genkeypair -v \
  -keystore urban-alchemy-release.p12 \
  -storetype PKCS12 \
  -alias urban-alchemy \
  -keyalg RSA -keysize 3072 -validity 10000
```

Choose a strong password when prompted. The current Gradle configuration uses the same password for the store and key. Keep the file and password private. Make two encrypted backups before proceeding.

Verify the alias and certificate:

```sh
keytool -list -v -keystore urban-alchemy-release.p12 -storetype PKCS12 -alias urban-alchemy
```

## 2. Add GitHub Actions secrets

In the GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret**.

Create `ANDROID_KEYSTORE_PASSWORD` with the password chosen above.

Create `ANDROID_KEYSTORE_BASE64` with the base64 encoding of the complete PKCS#12 file. On macOS/Linux:

```sh
base64 < urban-alchemy-release.p12 | tr -d '\n'
```

On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("urban-alchemy-release.p12"))
```

Paste the command output as the secret value. Do not paste it into an issue, pull request, chat, or source file.

## 3. Build and verify

Run the **Android Messenger** workflow using `workflow_dispatch` on the intended release branch (not a pull-request event). With both secrets present, the `signed-release` job should assemble and upload `urban-alchemy-messenger-stable`.

Before installing, verify the APK package ID and signing certificate fingerprint. Compare the fingerprint against the certificate in the backed-up keystore. Do not uninstall an existing app to work around a signature mismatch.

## Current package distinction

Debug builds use `org.urbanalchemy.messenger.preview`. Release builds use `org.urbanalchemy.messenger.stable`. They are separate Android apps with separate private data. A stable release will not inherit the preview app's database or Reticulum identity automatically. Do not describe the stable package as an in-place update of preview.

## Current workflow limitation

The workflow only builds a signed release on non-pull-request events. Pull-request builds intentionally produce unsigned/debug preview APKs. The release job is skipped when the keystore secret is absent.
