# Urban Alchemy Alpha — backend foundation

This is the first implementation slice: a local FastAPI API, SQLite persistence, immutable quest-version records, participation attempts, transactional SCAF ledger posting, persona signing, signed events, resumable synchronization, a shared transport contract, and signed peer invitations with scoped trust grants.

## Run locally

Requires Python 3.12 or newer.

```bash
cd backend
python -m venv .venv
# Windows PowerShell: .venv\\Scripts\\Activate.ps1
# macOS/Linux: source .venv/bin/activate
python -m pip install -e '.[test]'
python -m uvicorn ua_alpha.main:app --host 127.0.0.1 --port 8000
```

Open http://127.0.0.1:8000/docs for the development API and http://127.0.0.1:8000/health for its health check.

Run the tests:

```bash
cd backend
python -m pytest
```

The SQLite file defaults to `backend/urban-alchemy.sqlite3` when started from this directory. Override it with `UA_DATABASE_PATH`.

## Important security status

**Development only.** Authentication, passkeys, sessions, CSRF protection, moderation, and application authorization are not implemented in the HTTP API. The API is intended for localhost development. Do not expose it to a LAN, the public internet, or production. Player IDs are development identifiers, not proof of identity. Persona signing keys are currently in-memory primitives; secure key storage and account recovery are not implemented.

## Identity, invitations, trust, and transport

- A UA persona has a stable random identifier and an independently generated Ed25519 signing key.
- Persona signing keys are independent of Reticulum identities, browser/passkey credentials, and the offline master recovery key.
- Signed key-rotation statements can preserve persona continuity when the current key is available. They do not establish recovery authority or solve compromised/lost-key disputes.
- Signed event envelopes use canonical JSON, content-derived event IDs, explicit schema identifiers, and Ed25519 signatures. Ingestion requires a separately trusted persona-to-public-key binding.
- The local event store is append-only and deduplicates by event ID.
- The sync engine exports bounded batches, requires explicit acknowledgement before advancing outbound cursors, verifies trusted persona keys on receipt, and atomically stores received events with the receive cursor.
- Sync cursors are durable per peer and direction. Repeated batches are safe; malformed, unordered, oversized, untrusted, or cursor-regressing batches are rejected.
- A shared transport contract separates synchronization from network specifics. The coordinator requires an authenticated session with distinct local and remote peer identities, checks transport/address consistency, and advances outbound cursors only after a matching receipt acknowledgement.
- Signed peer invitations bind inviter identity/key, optional recipient persona, exact sorted scopes, issue/expiry times, and a random nonce. Invitations are transport-independent; delivery alone never grants trust.
- Acceptance requires an explicit local action, a previously trusted inviter key, recipient matching, valid signature, and unexpired invitation. A recipient-local receipt prevents replay; a persistent grant stores only the invitation's exact scopes.
- Unknown inviter keys are rejected. Fingerprint verification / explicit trust-on-first-contact is a separate user-mediated step, not automatic key acceptance.
- Trust grants can be revoked. Revocation stops future scope checks; it does not erase data already received or copies held by peers.
- Scope names are deliberately extensible. Initial examples include `events:receive`, `events:send`, `directory:read`, and `activity:publish`; this slice does not yet wire every scope into application operations.
- The transport contract is not a live network adapter. Reticulum and internet implementations still need real authenticated sessions, framing, encryption, reconnect behavior, and delivery acknowledgements.
- Reticulum identity binding, WebAuthn/passkey login, secure key storage, offline master recovery, signed acceptance receipts, and recovery challenges remain future layers.
- These primitives are transport-independent: the same signed UA event can be carried over Reticulum, an internet relay, removable media, or another authorized transport.

## Domain invariants in this slice

- SCAF is integer-only and internal: 10 to an eligible participant and 5 to the quest creator.
- Quest identity is stable; each publication creates a separate immutable version row.
- An accepted attempt references the exact version it accepted.
- A player can accept a given quest once, regardless of later versions.
- Creator self-acceptance is rejected.
- Completion state and both ledger entries are written in one SQLite transaction.
- Completion retries return the existing result without issuing duplicate ledger entries.
- Balances are calculated from ledger entries, not stored as mutable account state.

The current schema and service layer are a foundation, not yet the full alpha. Internet/Reticulum adapters, passkey authentication, frontend, moderation, migrations, secure key custody, scope enforcement at each application boundary, signed acceptance receipts, domain-specific conflict handling, and live multi-node transport integration remain subsequent slices.
