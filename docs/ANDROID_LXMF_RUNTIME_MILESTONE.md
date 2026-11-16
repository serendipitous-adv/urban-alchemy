# Android LXMF runtime milestone

## What this milestone does

The standalone Android app now has an explicit Reticulum/LXMF startup action. It uses the Chaquopy Python runtime already packaged in the APK and keeps the Reticulum delivery identity separate from the existing Android Keystore device identity.

On first start, the app:
- creates app-private storage for Reticulum configuration and LXMF router state;
- creates and persists a dedicated Reticulum identity;
- registers an LXMF delivery destination;
- starts Reticulum with a local AutoInterface for same-network discovery;
- displays the public 32-character LXMF destination and allows copying it.

On later starts, it loads the same identity and should display the same address. The private identity file remains in the app's private files directory and is not displayed, exported, or sent to contacts.

The app acquires Android's Wi-Fi multicast lock while the activity is alive so Reticulum AutoInterface can receive local discovery packets. This is a foreground prototype only; background reliability and long-running service lifecycle are not implemented.

## Announce behavior

Announcing is a separate user action. It asks LXMF to announce the local delivery destination. 'Announce submitted' is not proof that another peer received the announce or that a route exists.

The initial configuration does not create a public TCP listener, internet relay, or Urban Alchemy server. Local discovery requires a compatible Reticulum peer on a reachable local network. Wi-Fi being connected to the internet alone does not guarantee two peers share a Reticulum route.

## Not yet implemented

- Sending queued Android messages through LXMF.
- Receiving LXMF messages into the Android local store.
- Mapping LXMF message hashes and delivery proofs to local message records.
- Peer/contact identity verification.
- Background transport service and restart recovery.
- Two-device interoperability acceptance testing.

The chat composer therefore remains local-only. Messages continue to display QUEUED; this milestone must not be described as live messaging.

## Device validation

1. Install the signed stable APK over the previous stable build (do not uninstall).
2. Open the app and choose **Start transport**.
3. Confirm it reports Reticulum started and displays a 32-character LXMF address.
4. Copy the address for a later independent LXMF peer test.
5. Force-stop and reopen the app, start transport again, and confirm the address is identical. This validates persistence of the transport identity.
6. If startup fails, capture the exact error shown in the app; do not delete the app's private data or identity file.
7. Only after the identity persistence test, choose **Announce**. A submitted announce is not a delivery or peer-discovery success.

A real message exchange requires a second LXMF-compatible endpoint (for example, Sideband or the UA CLI) on a shared Reticulum network. It is a separate acceptance test from startup and identity persistence.
