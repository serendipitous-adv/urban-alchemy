# Identity implementation audit — Android messenger

Audit base: `feat/android-messenger-lxmf-runtime-init` (the base of the identity architecture branch).

## Current implementation facts

- `transport_probe.py` creates or loads a persistent Reticulum identity at `filesDir/reticulum/delivery_identity`, registers an LXMF delivery identity, and returns the 32-character LXMF destination.
- The runtime currently keeps the Reticulum identity and LXMF router in process memory after startup. The identity file is persistent; the Python runtime lifecycle is foreground/activity-oriented.
- `MainActivity` separately calls `DeviceIdentity.getOrCreate()`. That component creates an Android Keystore P-256 signing key and displays a SHA-256-derived fingerprint.
- I1 adds a version-4 `ua_local_identity` SQLite singleton with stable locally generated Player ID and Node ID UUIDs. The first successful Reticulum startup binds the public RNS identity hash, public key bytes, and LXMF destination. Private RNS key bytes are never returned to Kotlin or stored in this table.
- On subsequent starts, the binding is checked for exact continuity. A changed RNS identity, public key, or LXMF destination is rejected rather than silently replacing the node identity. This is a single-device local binding, not account recovery or multi-device authorization.
- The Android Keystore key and Reticulum identity are intentionally separate keypairs with different purposes. The displayed device fingerprint is not currently a Reticulum identity hash and must not be presented as one.
- `LocalStore` is SQLite schema version 3. Contacts currently store only `id`, `name`, `destination` (32-character LXMF hash), and `created_at`. There is no public Reticulum identity, signed identity card, or verified/unverified state in the schema.
- Adding a contact validates only the syntax of the LXMF destination. The app documentation correctly says this does not authenticate the contact.
- The LXMF runtime milestone explicitly does not yet connect queued chat messages to LXMF send/receive, map delivery proofs to message records, or perform contact identity verification.

## Implications

1. Do not equate the current Android Keystore fingerprint with the Reticulum identity.
2. Do not label a saved destination as a verified player or node.
3. The first identity integration should expose a public Reticulum identity representation from the already-persistent RNS identity, and derive/check the LXMF delivery destination from it using Reticulum/LXMF APIs. Do not hand-roll the derivation.
4. The local player/node binding needs explicit semantics. For the single-device MVP, a locally generated Player ID and Node ID can be persisted and bound to the Reticulum identity. This is a local bootstrap, not yet portable account recovery or multi-device authorization.
5. Contact verification should be a challenge/response or signed identity-card exchange. A contact becomes verified only after signature verification and destination derivation checks; a typed/pasted LXMF hash remains unverified.
6. Preserve the current Android Keystore key for its existing local signing/session use until a reviewed migration plan exists. Do not silently replace it with the RNS identity or copy either private key into the other key store.

## Implementation sequence

### I1 — identity binding and public identity export
- [x] Add version-4 local Player ID and Node ID records with UUIDs generated once and retained across app restarts.
- [x] Expose the public Reticulum identity bytes/hash and LXMF destination from the embedded runtime; never expose private key bytes.
- [x] Persist the binding and reject identity/destination drift on later runtime starts.
- [ ] Run Android build and migration tests proving existing contacts, conversations, and messages survive. The code change is committed, but runtime/build verification remains outstanding.

### I2 — verified contacts
- Extend contacts with optional public Reticulum identity material and an explicit verification state.
- Keep legacy contacts as `UNVERIFIED`.
- Add a signed identity card and challenge/response exchange; only the cryptographic verifier may promote a contact to `VERIFIED`.
- Test tampering, wrong destination derivation, replay, and mismatched identity cases.

### I3 — LXMF messaging integration
- Connect durable outbox and authenticated inbox to the LXMF router.
- Preserve native LXMF delivery semantics separately from UA application receipts.
- Mark application delivery only after validating the signed UA receipt and committing the incoming message.

### I4 — multi-device player authorization
- Add root/player authorization of distinct node identities, scoped delegation, revocation, and recovery.
- Do not claim multi-device identity continuity before those mechanisms are implemented and tested.

## Explicitly out of scope for this audit

This audit does not change runtime behavior, local database schema, key material, or message transport. It records verified code facts and the safe implementation order for review before code changes.
