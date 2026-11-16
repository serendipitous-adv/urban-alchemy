# Urban Alchemy identity architecture

Status: proposed architecture for the Android messenger MVP; no identity migration is implied by this document.

## Purpose

Urban Alchemy should build player and node identity on the Reticulum/LXMF foundation already being brought into the Android app. LXMF is not merely an optional chat transport. Reticulum supplies the cryptographic identity and authenticated network primitives; LXMF supplies the native messaging protocol and delivery destinations. UA supplies the social meaning and authorization rules.

This document records the intended boundary before the peer-socket and encrypted-message branches are integrated.

## The three layers

1. **Reticulum identity — cryptographic identity.** A Reticulum identity keypair proves control of a key and signs identity-bound data. Reticulum destinations are derived from an identity and destination aspects.
2. **LXMF — messaging identity and delivery.** An LXMF delivery destination is derived from the Reticulum identity using the standard `lxmf.delivery` destination. LXMF handles message encoding and direct or explicitly configured propagated delivery. An LXMF hash by itself is an address, not proof that a human is trustworthy or authorized.
3. **UA identity — social identity and authority.** UA records describe players, nodes, personas, guilds, memberships, roles, and reputation. Those records must be signed by, or cryptographically bound to, the appropriate Reticulum identity. UA must not invent a replacement cryptographic identity or custom message cipher.

## Identity objects

### Player

A **Player ID** is a stable UA identifier for a participant. It is bound to a player-controlled Reticulum root identity. The root identity signs the player's public profile and authorizes the device/node identities that may act for that player.

A player is not identified solely by a display name, LXMF destination hash, phone number, or installation ID.

### Node

A **Node ID** identifies one running UA installation or service: for example, a phone, personal computer, community node, Guild House, or steward process. Each node has its own Reticulum identity and its own LXMF delivery destination. The node publishes a signed UA node profile describing its type, capabilities, and the player or organization that authorizes it.

A node is not automatically a player. A community node or steward may exist without being owned by one individual player.

### Device authorization

A player root identity authorizes node identities using signed, scoped, expiring or revocable delegation records. Each device keeps its own private key. Do not copy one device's private identity into another device to make them appear to be the same node.

For the first single-device MVP, the player-to-node binding may be a locally created, explicitly recorded binding. Multi-device delegation, recovery, and revocation are a subsequent milestone; do not claim them until implemented and tested.

### Personas and guilds

Personas are UA profiles or roles signed by the player identity; they do not need separate Reticulum keys by default. A persona can be selected for presentation while the underlying player and node cryptographic identities remain verifiable.

A guild or other collective has its own UA organizational identity and membership/authority records. It may later have a dedicated Reticulum identity and LXMF destination for its node or service. Membership and role claims must be signed and scoped; knowing a guild's LXMF address does not grant membership or authority.

## Messaging and transport rule

- LXMF is the native UA messaging protocol and the default message path.
- UA message envelopes, invitations, and receipts may add application semantics inside authenticated LXMF messages; they must not replace Reticulum identity verification or LXMF delivery semantics.
- Any alternate carrier, including Wi-Fi Direct, must preserve the same Reticulum identity, signature, authorization, replay, and receipt checks. Prefer carrying Reticulum traffic over an alternate Reticulum interface. A raw socket carrying UA frames must not silently become a second identity or cryptographic system.
- Transport-independent application interfaces are acceptable for testability and future carriers, but they must not demote LXMF to an optional afterthought or make a transport socket the source of identity.
- Preserve honest delivery states: an announce is not peer verification; a submitted LXMF message is not an application receipt; only a verified UA receipt can mean the recipient application committed the message.

## MVP sequence

1. Start Reticulum and load/create the persistent app-private Reticulum identity; derive and display the corresponding LXMF delivery destination. Keep private material out of the renderer, logs, and exported contact cards.
2. Persist a versioned UA identity binding that records the Player ID, Node ID, public Reticulum identity, derived LXMF destination, and binding signature.
3. Exchange a signed identity card or invitation. The receiver verifies the signature and independently re-derives the LXMF destination from the supplied public Reticulum identity before marking the contact verified.
4. Route the first real message through LXMF and map its native status to the existing durable outbox without overstating delivery.
5. Add player-root delegation, additional devices, key rotation, recovery, and revocation only after the single-device identity and messaging path is qualified.

## Security and migration constraints

- Never treat a destination hash or announce as a human identity proof.
- Never expose private Reticulum keys to Kotlin UI state, React/Compose presentation, logs, clipboard, or ordinary backups.
- Do not reuse the Android Keystore vault key as a Reticulum identity key. They have different purposes and lifecycles.
- Do not break existing local conversations, queued messages, or stable signing/package identity while adding identity bindings.
- Key loss and recovery remain explicit limitations until a tested recovery design exists. Do not imply that LXMF automatically provides account recovery.
- Preserve existing contact approval, consent, workspace membership, signed receipts, and device authorization. A valid cryptographic signature is necessary but not sufficient for application authorization.

## Consequence for the current peer-transport work

The transport-neutral socket work can remain useful as a bounded carrier abstraction, and Wi-Fi Direct can remain a connectivity mechanism. However, the current proposal to make Reticulum/LXMF merely an optional adapter is not the intended UA architecture. Before that branch is accepted as the production messaging foundation, its relationship to Reticulum identity and LXMF delivery must be reconciled with this model.

## Acceptance criteria

- The same persistent Reticulum identity produces the same LXMF destination after process restart.
- The app can show the public identity/address without exposing private key material.
- An imported identity card is rejected if its signature is invalid or its claimed LXMF destination does not derive from its public Reticulum identity.
- A verified contact is distinct from an unverified saved address.
- A real LXMF message can be sent and received, and a UA signed receipt is verified before the UI reports application delivery.
- A Wi-Fi Direct/socket path cannot bypass identity verification, contact approval, or receipt verification.
