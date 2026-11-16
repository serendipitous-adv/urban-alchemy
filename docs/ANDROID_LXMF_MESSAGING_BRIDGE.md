# Android LXMF messaging bridge

## Status

This branch adds the first Android-packaged Python bridge for native LXMF
message submission and inbound delivery callbacks. It is built against the
exact Reticulum and LXMF forks already pinned in
`app/src/main/python/requirements.txt`.

The bridge is attached to the existing persistent `LXMRouter` during
`transport_probe.start_runtime()`. It uses LXMF's own `LXMessage`,
`LXMRouter.handle_outbound()`, and `register_delivery_callback()`; it does
not send LXMF payloads through the Wi-Fi Direct socket protocol.

## Python entry points exposed through Chaquopy

- `transport_probe.start_runtime(storage_path, display_name)`: starts the
  persistent RNS/LXMF runtime and registers the inbound callback.
- `transport_probe.send_message(destination_hex, text, client_message_id)`:
  submits an opportunistic LXMF message when a destination route and identity
  are already known. If route discovery is needed, it requests a path and
  returns `WAITING_FOR_ROUTE` without blocking the Android UI.
- `transport_probe.drain_events(limit)`: drains inbound message events and
  outbound delivery-state callbacks for Kotlin to persist.

Inbound event fields include LXMF message hash, source/destination hashes,
title, content, timestamp, signature-validation state and transport-encryption
metadata. Outbound events include the stable local message ID, LXMF hash,
LXMF state and progress.

## Important boundaries

This is a bridge milestone, not yet the live-messaging MVP. The Kotlin UI and
SQLite outbox/inbox are not wired to call `send_message` or poll
`drain_events` in this commit. The bridge event queue is currently in memory;
Kotlin must persist every inbound event before considering it consumed.
Messages that return `WAITING_FOR_ROUTE` must remain queued in SQLite and be
retried after a later route/announce refresh.

The bridge uses opportunistic LXMF delivery. It does not claim router
acceptance means recipient delivery. The UI must distinguish at least:
`QUEUED`, `WAITING_FOR_ROUTE`, `SUBMITTED_TO_LXMF`, `SENT`,
`DELIVERED`, `FAILED`, and `REJECTED`. Only LXMF's delivery callback
should produce `DELIVERED`; announce submission and local queueing are not
delivery proofs.

Peer authentication and UA Player/Node binding remain a separate identity
layer. LXMF's cryptographic destination and message signature must be
validated and displayed distinctly from a user's optional Player persona.
Do not treat a raw typed destination as a verified human identity.

## Acceptance work still required

1. Add Kotlin Chaquopy calls on an IO dispatcher for send and event polling.
2. Persist inbound events transactionally in `LocalStore`, deduplicating by
   LXMF message hash.
3. Persist the local client message ID to LXMF hash mapping and update statuses
   from LXMF callbacks.
4. Keep route-unavailable messages queued and retry without blocking the UI.
5. Add runtime lifecycle/restart handling; never start a second Reticulum
   instance in the same process.
6. Add tests for invalid destination, no route, send callback, inbound callback,
   duplicate inbound hash, process restart, and two-device delivery.
7. Run the Android debug build and inspect device logs before publishing an APK.

## Safety and compatibility

No existing preview installation, app-private database, Reticulum identity or
signing key is modified by this branch. Do not uninstall the installed preview
to test it. Release signing and the first stable-install data migration remain
separate blockers.
