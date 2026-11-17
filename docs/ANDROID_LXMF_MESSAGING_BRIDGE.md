# Android LXMF messaging bridge

## Status

The Android app packages a Python bridge for native LXMF message submission and inbound delivery callbacks. It uses the Reticulum and LXMF forks pinned in `app/src/main/python/requirements.txt`.

The bridge attaches to the persistent `LXMRouter` during `transport_probe.start_runtime()`. It uses LXMF's own `LXMessage`, `LXMRouter.handle_outbound()`, and `register_delivery_callback()`; it does not send LXMF payloads through the retired Wi-Fi Direct prototype.

## Android integration

The Kotlin host:

- Stores outbound message bodies encrypted in `LocalStore` before submission.
- Persists `SUBMITTING` before crossing into the Python/LXMF bridge.
- Submits messages through `transport_probe.send_message` and leaves route/identity waits eligible for later retry.
- Polls `peek_events` on an IO dispatcher, persists valid inbound messages, and acknowledges the event prefix only after processing.
- Requires inbound LXMF signature validation and a destination matching the local delivery identity before persistence.
- Deduplicates inbound messages by LXMF message hash and reuses a contact conversation or stable unknown-sender request thread.
- Maps LXMF callbacks to honest local delivery states.
- Converts interrupted `SUBMITTING`, `SUBMITTED`, and `SENT` messages to `DELIVERY_UNKNOWN` on the next Android process start; it does not automatically resend them.

The bridge event queue and LXMF callback objects are process-local. The Android host currently polls while the app process is active; durable background delivery after the app is closed is not qualified.

## Python entry points

- `transport_probe.start_runtime(storage_path, display_name)`: starts the persistent Reticulum/LXMF runtime and registers inbound callbacks.
- `transport_probe.send_message(destination_hex, text, client_message_id)`: delegates to the LXMF bridge. The bridge requests a route and returns `WAITING_FOR_ROUTE` when no path is known; it does not block waiting for discovery.
- `transport_probe.peek_events(limit)`: reads pending callback events without removing them.
- `transport_probe.ack_events(count)`: removes the processed event prefix after Android persistence.
- `transport_probe.validate_contact_invite(invite_json)`: validates the public key against the Reticulum identity hash and LXMF destination.
- `transport_probe.create_contact_invite(display_name, player_id)`: exports public contact data only; it never exports private keys.
- `transport_probe.announce()`: submits an LXMF announce. This is not proof of peer receipt.

## Delivery semantics

The UI and storage must distinguish:

- `QUEUED`: encrypted and saved locally; not yet submitted.
- `WAITING_FOR_ROUTE`: no usable path is currently known.
- `WAITING_FOR_IDENTITY`: recipient identity material is not yet available.
- `SUBMITTING`: the app has persisted the ambiguous handoff boundary and is calling LXMF.
- `SUBMITTED`: LXMF accepted the outbound message; this is not recipient delivery.
- `SENT`: LXMF reports transmission; this is not recipient delivery proof.
- `DELIVERED`: LXMF reported a delivery proof.
- `FAILED` / `REJECTED`: LXMF reported failure or rejection.
- `DELIVERY_UNKNOWN`: the process lost the callback or crashed around submission; the app cannot determine whether the recipient received it. The message is retained and not automatically resent.

The app must never convert local queueing, an announce, or router acceptance into a claim of delivery.

## Contact identity

A validated invite establishes that its Reticulum public key matches the supplied Reticulum identity hash and LXMF destination. It does not establish that a self-reported display name belongs to a real-world person. Raw manually entered destinations remain unverified.

The Reticulum/LXMF identity is separate from the local Android device-signing key and from the UA Player/Node identifiers. Do not conflate transport identity with human identity.

## Acceptance work still required

1. Two physical Android devices: bidirectional invite exchange and message delivery over a reachable Reticulum path.
2. Negative invite tests: malformed JSON, wrong version, altered public key, mismatched identity hash, and mismatched LXMF destination.
3. Transport tests: no route, route recovery, unknown recipient identity, successful submission, delivery proof, rejection, and failure callback.
4. Inbound tests: invalid signature, wrong destination, duplicate hash, known contact, and unknown-sender request.
5. Process-restart tests at each outbound state, especially immediately before and after `handle_outbound()`.
6. Concurrent callback/database tests and verification that an unpersisted event is not acknowledged.
7. Upgrade tests preserving the installed preview's database, Reticulum identity, Android Keystore key, contacts, and history.

The current GitHub Actions job assembles the debug APK; it does not execute these transport or physical-device acceptance tests.

## Safety and compatibility

Do not uninstall an existing preview installation, clear app data, or replace the persistent Reticulum identity during testing. Release signing, background delivery, multi-device delegation/recovery, and the first stable-install migration remain separate blockers.
