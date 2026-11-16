# Identity implementation audit — Android messenger

Audit scope: current identity branch `feat/ua-player-node-identity-architecture`, stacked on `feat/android-messenger-lxmf-runtime-init`.

## Current implementation facts

- `transport_probe.py` creates or loads a persistent Reticulum identity at app-private `filesDir/reticulum/delivery_identity`, registers an LXMF delivery identity, and returns the 32-character LXMF destination.
- The runtime keeps the Reticulum identity and LXMF router in process memory after startup. The identity file is persistent; the Python runtime lifecycle remains process/activity-oriented.
- `MainActivity` separately calls `DeviceIdentity.getOrCreate()`. That component creates an Android Keystore P-256 signing key and displays a SHA-256-derived fingerprint.
- I1 adds a version-4 `ua_local_identity` SQLite singleton with stable locally generated Player ID and Node ID UUIDs. The first successful Reticulum startup binds the public RNS identity hash, public key bytes, and LXMF destination. Private RNS key bytes are never returned to Kotlin or stored in this table.
- On subsequent starts, the binding is checked for exact continuity. A changed RNS identity, public key, or LXMF destination is rejected rather than silently replacing the node identity. This is a single-device local binding, not account recovery or multi-device authorization.
- The Android Keystore key and Reticulum identity are intentionally separate keypairs with different purposes. The displayed device fingerprint is not currently a Reticulum identity hash and must not be presented as one.
- `LocalStore` is SQLite schema version 4. The v4 migration creates `ua_local_identity`; it does not rebuild or delete existing contacts, conversations, or messages. Contacts still store only `id`, `name`, `destination` (32-character LXMF hash), and `created_at`. There is no public Reticulum identity, signed identity card, or verified/unverified state in the contact schema.
- Adding a contact validates only the syntax of the LXMF destination. It does not authenticate the contact; the UI labels saved destinations as unverified.
- The LXMF runtime milestone still does not connect queued chat messages to LXMF send/receive, map delivery proofs to message records, or perform contact identity verification.
- Pull-to-refresh reloads local conversations and contacts from SQLite. It is not a network sync.

## Implications

1. Do not equate the current Android Keystore fingerprint with the Reticulum identity.
2. Do not label a saved destination as a verified player or node.
3. The current I1 implementation exposes the public Reticulum identity hash/key and derived LXMF delivery destination from the persistent RNS identity. Build and restart verification are still required before treating this as qualified.
4. The local player/node binding is a single-device bootstrap, not portable account recovery or multi-device authorization.
5. Contact verification should be a challenge/response or signed identity-card exchange. A contact becomes verified only after signature verification and destination derivation checks; a typed/pasted LXMF hash remains unverified.
6. Preserve the current Android Keystore key for its existing local signing/session use until a reviewed migration plan exists. Do not silently replace it with the RNS identity or copy either private key into the other key store.

## Implementation sequence

### I1 — identity binding and public identity export
- [x] Add version-4 local Player ID and Node ID records with UUIDs generated once and retained across app restarts.
- [x] Expose the public Reticulum identity bytes/hash and LXMF destination from the embedded runtime; never expose private key bytes.
- [x] Persist the binding and reject identity/destination drift on later runtime starts.
- [ ] Complete Android compilation, install, and migration/restart tests proving existing contacts, conversations, and messages survive. Code is committed; runtime/build verification is not yet complete.

### I2 — verified contacts
- [ ] Extend contacts with optional public Reticulum identity material and an explicit verification state.
- [ ] Keep legacy contacts as `UNVERIFIED`.
- [ ] Add a signed identity card and challenge/response exchange; only the cryptographic verifier may promote a contact to `VERIFIED`.
- [ ] Test tampering, wrong destination derivation, replay, and mismatched identity cases.

### I3 — LXMF messaging integration
- [ ] Connect durable outbox and authenticated inbox to the LXMF router.
- [ ] Preserve native LXMF delivery semantics separately from UA application receipts.
- [ ] Mark application delivery only after validating the signed UA receipt and committing the incoming message.

### I4 — multi-device player authorization
- [ ] Add root/player authorization of distinct node identities, scoped delegation, revocation, and recovery.
- [ ] Do not claim multi-device identity continuity before those mechanisms are implemented and tested.

## UX / MVP qualification notes

- The home screen is one scrollable list containing identity, transport, diagnostics, conversations, and conversation creation.
- Pulling down from the top reloads local conversations and contacts from SQLite; no network refresh is implied.
- Contacts use one scrollable list and clearly state that saved LXMF destinations are unverified.
- Chat input uses keyboard-aware layout; queued local messages are not represented as sent over LXMF.
- Source-level checks have been performed, but compilation, installation, migration, restart, and physical gesture tests remain outstanding.

## Explicit limitations

This audit records the current code state and remaining qualification work. I1 changes the SQLite schema and runtime identity export as described above. It does not implement verified contacts, LXMF message send/receive, UA signed application receipts, multi-device delegation, key recovery, or revocation.
