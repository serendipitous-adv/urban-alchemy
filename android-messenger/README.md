# Urban Alchemy Messenger (Android)

Native Kotlin/Jetpack Compose client for the standalone, offline-first messenger MVP.

## Current scaffold

- Creates a persistent EC device keypair in Android Keystore.
- Displays a short SHA-256 identity fingerprint.
- Persists local conversation records in SQLite.
- Requires no account, internet connection, Reticulum node, or backend service.

This is an early development scaffold, not yet a secure messaging release. Message encryption, contact verification, message composition, outbound queue processing, and radio transports are not implemented yet.

## Build

Open this directory in Android Studio with Android SDK 35 and JDK 17, or build with Gradle:

```sh
./gradlew :app:assembleDebug
```

A Gradle wrapper is not included in this first scaffold. Generate it with a compatible Gradle installation before command-line builds.

## Device testing

The first available phone can verify identity persistence and local conversation storage across app restarts. A second Android device is required to validate real Wi-Fi Direct or Bluetooth exchange.
