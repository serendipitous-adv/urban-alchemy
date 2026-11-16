# Urban Alchemy Messenger (Android)

Native Kotlin and Jetpack Compose prototype for the standalone, offline-first messenger MVP.

## Implemented in this prototype

- Creates a persistent EC device identity in Android Keystore and displays its SHA-256 fingerprint.
- Creates local conversations and displays their message history.
- Stores message content encrypted at rest using AES-256-GCM with a key held in Android Keystore.
- Displays locally stored messages and their honest `QUEUED` state.
- Requires no account, internet connection, Reticulum node, or backend service for these local features.

## Important limitations

This is an early local prototype, not yet a secure person-to-person messaging release.

- The message encryption key is local to this installation. It is not end-to-end encryption between two users.
- There is no contact exchange, peer authentication, network transport, delivery acknowledgement, or reply path yet.
- `QUEUED` means saved on this device only. It does not mean sent or delivered.
- A second Android device is needed to validate real device-to-device transport, but is not needed to test local conversation and storage behavior.

## Build

Requirements: JDK 17, Android SDK 35, and Gradle 8.9.

From this directory:

```sh
gradle --no-daemon :app:assembleDebug
```

The debug APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

The repository CI workflow builds the APK and uploads it as the `urban-alchemy-messenger-debug` artifact when the build succeeds.

## First-device test

1. Install and launch the debug APK.
2. Confirm a local identity fingerprint appears.
3. Create a conversation and send a test message.
4. Confirm its state is `queued`, not delivered.
5. Close and reopen the app; confirm the conversation and message remain.
6. Repeat while in airplane mode to verify these local features do not require connectivity.

Do not use real secrets or sensitive conversations in this development build.
