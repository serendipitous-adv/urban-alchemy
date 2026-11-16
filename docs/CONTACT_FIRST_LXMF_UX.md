# Contact-first UX with LXMF underneath

## Product requirement

Urban Alchemy Messenger must feel familiar to someone who has used a conventional
messaging app: add a contact, open a conversation, send a message. LXMF and
Reticulum are the messaging infrastructure, not concepts the user must learn.

**Design principle: make LXMF seamless for the user, while keeping LXMF central
to the implementation.**

This is a foundational product and architecture requirement for the Android
messenger, not a later visual-polish task.

## User-facing model

Keep three distinct concepts in the data model:

- **Contact** — a relationship in the user's address book: display name, optional
  avatar, notes, and relationship/request state.
- **Player** — an optional Urban Alchemy persona/profile associated with a
  contact. A Player identity is not inferred from a display name.
- **LXMF identity/destination** — the cryptographic messaging identity and
  destination used by Reticulum/LXMF. It is transport metadata, not the
  contact's human-facing identity.

A contact must survive route loss, offline periods, transport restarts, and
destination rediscovery. A route is ephemeral network state; it must never be
the primary key or the definition of a contact.

## Familiar add-contact flow

The primary action is **Add contact**. The normal path should be:

1. User chooses Add contact.
2. User shares or scans an Urban Alchemy invite (QR/deep link), or uses a
   short human-readable invite code when available.
3. The app resolves the invite locally/through Reticulum and shows a preview:
   display name, optional Player profile, and identity verification state.
4. User confirms Add contact (or accepts an incoming request).
5. A conversation can be opened immediately. If the peer is unreachable, the
   message remains queued and the app explains that it will send when a route
   becomes available.

Do not make a 32-character LXMF destination hash the primary contact-creation
experience. A raw destination may remain available under an Advanced/technical
details affordance for diagnostics and power users, never as the normal
workflow.

## Identity, trust, and requests

- Bind the contact's LXMF destination to its Reticulum cryptographic identity
  only after the relevant identity material is resolved and validated.
- Bind a UA Player ID/profile separately, with an explicit proof/association
  mechanism. Never assume a matching display name proves identity.
- Distinguish **saved**, **request received**, **identity known**, and
  **verified Player association**. Do not collapse these into one "verified"
  boolean.
- Unknown inbound LXMF senders should appear as message requests, not silently
  become trusted contacts.
- Accepting a request creates/links a contact; blocking or declining must not
  damage the underlying transport runtime.
- Never expose private keys. Sharing an invite shares only the intended public
  identity/destination material.

## Messaging behavior

- The composer always addresses a Contact; the app resolves the current LXMF
  destination underneath.
- Queue outgoing messages durably before attempting transport.
- A missing route triggers background/non-blocking path discovery and retry.
  It must not produce a confusing technical error or require the user to
  re-enter the message.
- Show honest, familiar statuses: Sending, Sent, Delivered, Failed, and
  Waiting for connection (or similarly plain language). Map these to actual
  LXMF events; never label a locally queued or router-submitted message as
  Delivered.
- Persist inbound messages before removing them from the bridge event queue;
  deduplicate by LXMF message hash.
- Reticulum/LXMF lifecycle and event polling must not block Compose UI or start
  duplicate runtimes.

## Technical architecture

- LXMF remains the sole messaging transport for this feature. Do not silently
  fall back to the Wi-Fi Direct socket protocol.
- Contact IDs and Player IDs are stable app-level identifiers. LXMF destination
  hashes and Reticulum identity hashes are separately stored, indexed
  attributes that can be updated through a validated identity-change flow.
- Model reachability/last-seen/route state separately from contact identity.
- Keep a local contact record usable without network access.
- Keep transport diagnostics (Reticulum status, destination hashes, announce,
  route details) in an optional Advanced section.
- Preserve the existing SQLite database and persistent Reticulum identity
  through schema migrations and app updates.

## Current implementation gap

The current Android Contacts screen still asks the user to enter a raw
32-character LXMF destination. The current bridge provides native LXMF send
and receive entry points, but Kotlin has not yet connected the composer,
durable outbox, inbound persistence, or callback status updates. The current
screen therefore does not yet satisfy this contact-first requirement.

## Acceptance criteria

1. A normal user can add a person without knowing what LXMF or a destination
   hash is (QR/invite flow).
2. The contact remains in the address book when the peer is offline or has no
   currently known route.
3. A conversation can be started from the contact record; outgoing messages
   are durably queued and retried automatically.
4. Incoming unknown senders are presented as requests with clear trust state.
5. The UI never claims delivery without an LXMF delivery callback.
6. Contact, Player, and LXMF identity are distinct but can be explicitly linked.
7. Advanced users can inspect/copy transport details without exposing them in
   the default contact workflow.
8. Existing app data and Reticulum identity survive upgrades.
9. The normal path is tested on two devices, including no-route/recovery and
   inbound request scenarios.
