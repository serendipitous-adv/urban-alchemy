# Android embedded Reticulum runtime spike

This branch adds Chaquopy to the standalone Android app and packages Python for
`arm64-v8a`. The Python dependency pins follow the currently published
[Reticulum for Android reference project](https://github.com/torlando-tech/reticulum-android),
which embeds RNS and LXMF using Chaquopy.

## Scope of this spike

- Package the Python runtime and pinned RNS/LXMF dependencies into the APK.
- Keep the existing Kotlin local store and transport-neutral interface unchanged.
- Include an import-only smoke test (`transport_probe.check_runtime()`).
- Do not start RNS, create a network identity, announce, or send messages yet.

## Why these dependency pins

The reference Android project currently pins Android-oriented Reticulum and LXMF
fork commits and uses a maintained `cryptography` package. These pins are explicit
so CI builds are repeatable. They are not assumed to be protocol changes; review
the referenced forks and their licenses before changing or shipping them.

## Build acceptance

1. GitHub Actions resolves Chaquopy and all Python dependencies for Python 3.11 / ARM64.
2. `:app:assembleDebug` completes and produces the preview APK.
3. The next device test calls `check_runtime()` and confirms RNS, LXMF, and
   cryptography import successfully.
4. Only after that test should the next change initialize a persistent RNS/LXMF
   identity in app-private storage.

A successful Gradle build alone does not prove the Python modules load on the phone.
No transport or message-delivery capability is enabled by this spike.
