# Urban Alchemy Messenger (Android)

Native Kotlin and Jetpack Compose messenger prototype using Reticulum/LXMF for transport.

The messenger is intended to be immediately useful as a familiar contact-and-conversation tool. It does not require people to understand Urban Alchemy, join a community, or participate in quests. Any wider UA/Serendipity experiences remain optional and can be discovered organically.

## Implemented

- Persistent Android device-signing identity in Android Keystore; private key remains on-device.
- Separate persistent Reticulum delivery identity and LXMF destination.
- Local contacts and reusable conversations.
- Share/import contact invites as the primary add-contact flow; raw LXMF address entry is available as an advanced, explicitly unverified option.
- Invite validation checks that the Reticulum public key matches the stated identity hash and LXMF destination. A display name is self-reported and does not establish a real-world identity.
- LXMF send/receive integration through the embedded Reticulum runtime; no parallel Wi-Fi Direct messaging protocol.
- Encrypted local message storage using AES-256-GCM with an Android Keystore-held key.
- Durable outbox and inbound message history, LXMF-hash deduplication, signature/destination checks before inbound persistence, and LXMF delivery-state callbacks.
- Conservative crash recovery: messages interrupted at or after LXMF submission become `DELIVERY_UNKNOWN` and are not automatically resent, avoiding silent duplicate delivery.
- Private local threads whose notes are marked `LOCAL_ONLY` and never transmitted.
- Inbox-first UI and pull-to-refresh for local conversation/contact data.

## Transport and privacy boundaries

- Reticulum is configured with local AutoInterface discovery. A compatible peer must be reachable through the configured Reticulum network; this build does not configure a public relay or internet gateway.
- The runtime and polling loop are currently tied to the active Android app process. Reliable background delivery after the app is closed is not yet qualified.
- `QUEUED` means stored locally. `SUBMITTED` means handed to LXMF. Only an LXMF delivery proof may produce `DELIVERED`.
- `DELIVERY_UNKNOWN` means the app cannot establish whether a message reached the recipient; it is retained and not automatically resent.
- Local AES-GCM encryption protects the on-device message database. It is distinct from LXMF's transport encryption and does not by itself establish a verified human identity.
- A verified contact invite binds a public key to an LXMF destination. It does not prove that the self-reported name belongs to a particular person.
- No account or backend service is required for the local features.

## Build

Requirements: JDK 17, Android SDK 35, and Gradle 8.9.

From this directory:

```sh
gradle --no-daemon :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow assembles the debug APK for pull requests and uploads it as a short-lived artifact.

## MVP qualification still required

A successful CI build proves compilation and packaging, not live messaging. Before calling this a live-messaging MVP, test with two physical Android devices:

1. Install the preview build without uninstalling an existing installation or clearing app data.
2. Confirm each device's Reticulum identity and LXMF destination persist across app restarts.
3. Exchange contact invites in both directions; reject malformed or tampered invites; confirm names remain explicitly self-reported.
4. Send and receive messages in both directions over a reachable Reticulum path.
5. Verify inbound signatures, destination matching, duplicate suppression, and conversation reuse.
6. Interrupt connectivity, restore the route, and confirm queued messages retry without blocking the UI.
7. Kill/restart the app around submission and delivery callbacks; verify uncertain messages are never silently resent or shown as delivered.
8. Confirm the installed app retains its local database, identity, contacts, and message history through upgrades.

Background delivery, multi-device identity recovery, release signing, and a stable public release remain separate work. Do not uninstall the preview app or reset its app-private data during testing.
