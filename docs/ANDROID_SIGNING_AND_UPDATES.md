# Android signing and update policy

## Permanent signing identity

All distributable stable APKs must use one private Android keystore. The keystore
must be backed up securely and must never be committed to Git, sent in chat, or
placed in the APK. Losing it means Android will reject future in-place updates.

The current preview APK was signed by GitHub's temporary debug key. Its signing
identity is not recoverable from the repository. Android cannot update that
installation using a newly generated key.

To avoid overwriting the existing preview, the release application ID is
`org.urbanalchemy.messenger.stable`; debug builds retain
`org.urbanalchemy.messenger.preview`. The first stable installation is therefore
a one-time separate install. All later stable releases will keep the stable
application ID and use the same permanent keystore, so they can update in place.
The existing preview remains installed as a fallback. No app data migration is
implemented by this signing change.

## Create the keystore (Windows PowerShell)

Run this on a trusted computer with Java's `keytool` available:

```powershell
keytool -genkeypair -v -keystore urban-alchemy-release.jks -alias urban-alchemy -keyalg RSA -keysize 2048 -validity 10000
```

Choose strong passwords and store them in a password manager. Keep at least two
secure backups of `urban-alchemy-release.jks`. Never upload the raw keystore to
GitHub or share it in chat.

Encode the keystore for the GitHub Actions secret:

```powershell
$bytes = [System.IO.File]::ReadAllBytes(".\urban-alchemy-release.jks")
$b64 = [Convert]::ToBase64String($bytes)
Set-Clipboard $b64
```

In the repository's GitHub page, open **Settings → Secrets and variables → Actions**
and create these repository secrets:

- `ANDROID_KEYSTORE_BASE64`: paste the clipboard contents.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_ALIAS`: `urban-alchemy`.
- `ANDROID_KEY_PASSWORD`: the key password.

Do not put any password or keystore contents in a chat message, issue, commit, or
workflow log. The workflow only restores the keystore on the ephemeral build
runner and uploads the signed APK.

## Release process

Run the **Android Messenger** workflow manually after the secrets are configured.
It builds the debug preview and, when signing is configured, a signed stable APK.
Download only the `urban-alchemy-messenger-stable` artifact for installation.
The first stable install is separate from the old preview; subsequent stable
versions must increment `versionCode` and use the same application ID and key.

## Verification before claiming update support

1. Confirm the signed APK package ID is `org.urbanalchemy.messenger.stable`.
2. Confirm its signing certificate fingerprint and retain that fingerprint in
   release notes (never publish the private key).
3. Install the first stable build without uninstalling the preview.
4. Install a later build over the stable build and verify Android accepts it and
   local contacts/conversations persist.
5. Keep the keystore and its backups safe. Do not rotate signing keys casually.
