# Urban Alchemy Messenger (Android)

Native Kotlin and Jetpack Compose prototype for the standalone, offline-first messenger MVP.

## Implemented in this prototype

- Persistent EC device identity in Android Keystore; the private key stays on-device.
- Local conversations and message history.
- Message bodies encrypted at rest using AES-256-GCM with a key held in Android Keystore.
- Durable local outgoing messages shown honestly as `QUEUED`.
- Local contact book with names and 32-character hexadecimal LXMF destination hashes.
- Conversations can be created as local-only threads or started from a saved contact.
- Contact destinations are marked **unverified**; saving an address does not authenticate its owner.
- No account, internet connection, Reticulum node, or backend service is required for local features.

## Important limitations

This is an early local prototype, not yet a secure person-to-person messaging release.

- Contact addresses are stored locally; there is not yet a QR exchange, challenge-response identity verification, or cryptographic binding to the contact.
- The local message-encryption key is installation-specific. This is not end-to-end encryption between two users.
- There is no network transport, delivery acknowledgement, or reply path yet.
- `QUEUED` means saved on this device only. It does not mean sent or delivered.
- A second Android device is needed to validate real device-to-device transport, but is not needed to test local contact and storage behavior.

## Build

Requirements: JDK 17, Android SDK 35, and Gradle 8.9.

From this directory:

```sh
gradle --no-daemon :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The repository CI workflow builds the APK and uploads it as the `urban-alchemy-messenger-debug` artifact when successful.

## First-device test

1. Install and launch the debug APK.
2. Confirm the local identity fingerprint remains stable after restart.
3. Create a local conversation and send a test message.
4. Add a contact using a 32-character hexadecimal LXMF destination.
5. Start a conversation with that contact; confirm the recipient address is visible and marked unverified.
6. Force-stop/reopen the app; confirm contacts, conversations, and messages remain.
7. Confirm messages still show `queued`, not sent or delivered.

Do not use real secrets or sensitive conversations in this development build.
