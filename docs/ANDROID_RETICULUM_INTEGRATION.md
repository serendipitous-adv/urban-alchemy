# Android Messenger: Reticulum/LXMF Integration Plan

**Status:** Adapter boundary approved for implementation; Android runtime packaging is a validation gate.
**Scope:** Add Reticulum/LXMF transport to the existing offline-first Android messenger without replacing its local identity, contacts, conversations, or encrypted message store.

## Decision

Use the official Reticulum Network Stack (RNS) and LXMF protocol for mesh transport. Keep the Android conversation and persistence layers transport-neutral. The first Android integration will embed the Python RNS/LXMF runtime inside the APK if the required Android ARM64 dependencies can be packaged safely. It must not require a user-installed Termux environment in the final standalone MVP.

A temporary Termux/localhost bridge may be used only as a development diagnostic if needed; it is not the product architecture and must not be mistaken for standalone support.

## Boundaries

- **Android local store:** source of truth for conversations, contacts, and message history; creates a durable local `QUEUED` record before attempting transport.
- **Kotlin transport contract:** receives an already-persisted outbound message and emits lifecycle updates and inbound messages.
- **RNS:** routing and transport interfaces.
- **LXMF:** encrypted human-message delivery and delivery proofs.
- **Reticulum identity:** separate from the app's existing device identity and any future UA persona identity. Never silently equate these keys.
- **Contact destination:** a 32-character LXMF destination hash is an address, not proof of the named person's identity.

## Delivery semantics

The UI and database must distinguish:
- `QUEUED`: saved only in the Android local database; no transport claim.
- `ROUTER_QUEUED`: accepted by the local LXMF router; not proof of transmission or receipt.
- `SENT`: LXMF reports transmission/network acceptance; not proof the recipient received it.
- `DELIVERED`: a valid LXMF delivery proof was received from the destination.
- `FAILED`: the transport reports a terminal failure; retain the local message and allow an explicit retry.

Do not mark a message delivered because a socket write succeeded, a route was found, or an announce was sent. Incoming messages must be persisted before being displayed. Updates must be idempotent by the stable local message ID / LXMF message hash mapping.

## Runtime packaging decision gate

Chaquopy is a candidate for embedding Python because it integrates Python modules into the normal Android Gradle build and supports Android ARM64. Before enabling it in the production app:
1. Prove that the selected RNS and LXMF releases install for the exact Python/Android ABI used by CI.
2. Verify that the cryptographic backend is an Android-compatible, maintained native build.
3. Do not silently fall back to Reticulum's pure-Python cryptographic primitives for a production build.
4. Verify persistent RNS identity and LXMF router storage live under app-private persistent storage.
5. Verify RNS interface sockets, app lifecycle, foreground execution, and Android battery restrictions on a real phone.
6. Keep all Python dependencies pinned and record their licenses and versions.

If the packaging gate fails, stop and report the exact missing wheel/build constraint before choosing another runtime strategy. Do not substitute an internet relay or a Termux dependency without an explicit product decision.

## Implementation sequence

### R0 — Transport contract (this change)
- Define transport-neutral outbound, inbound, status, and lifecycle types in Kotlin.
- Preserve the existing local-only behavior and existing `QUEUED` semantics.
- No UI or database behavior changes.

### R1 — Android runtime packaging spike
- Add the embedded Python runtime in an isolated build branch.
- Package pinned RNS/LXMF dependencies for Android ARM64.
- Add a diagnostic that initializes RNS, creates/loads a persistent LXMF delivery identity, and returns the public 32-character destination.
- Build and install the APK; verify the same destination survives force-stop/restart.
- Do not enable sending in the UI yet.

### R2 — LXMF adapter
- Implement start/stop, announce, outbound send, inbound callback, and delivery-status callback.
- Persist outbound state transitions and inbound messages through the existing local store.
- Keep transport callbacks off the UI thread and make retries safe.
- Add clear permission/network/route errors without losing queued messages.

### R3 — First real network exchange
- Test with an independent LXMF endpoint (Sideband or another compatible client).
- Record the exact Reticulum interfaces and network configuration.
- Verify both directions and an actual delivery proof. A path lookup or a message remaining queued is not success.
- Test restart and recipient offline/reconnect behavior separately.

### R4 — Opportunistic propagation
- Test LXMF propagation-node behavior with the recipient offline.
- Only then expose propagation-related states and claims in the UI.
- Forwarding nodes must not receive plaintext message bodies; no custom UA relay protocol is introduced here.

## Non-goals for this slice

- No changes to the current database schema or UI.
- No migration or replacement of the app's existing local device identity.
- No UA persona-to-Reticulum identity binding.
- No contact verification or global directory.
- No claim of background reliability or store-and-forward until device and network tests demonstrate it.

## Acceptance rule

A successful Android build proves only compilation. A stable local LXMF identity requires device testing. A sent/delivered state requires a real independent LXMF endpoint and observed protocol status. A delivery proof is not evidence that a human read the message.
