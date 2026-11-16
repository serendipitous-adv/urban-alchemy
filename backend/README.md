# Urban Alchemy Alpha — backend foundation

This is the first implementation slice: a local FastAPI API, SQLite persistence, immutable quest-version records, participation attempts, transactional SCAF ledger posting, and transport-independent persona signing primitives.

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

Run the domain and identity tests:

```bash
cd backend
python -m pytest
```

The SQLite file defaults to `backend/urban-alchemy.sqlite3` when started from this directory. Override it with `UA_DATABASE_PATH`.

## Important security status

**Development only.** Authentication, passkeys, sessions, CSRF protection, moderation, and authorization scopes are not implemented yet. The API has intentionally been documented to bind to localhost. Do not expose it to a LAN, the public internet, or a production deployment. Player IDs are currently accepted as development identifiers, not proof of identity. Persona signing keys are currently in-memory primitives; secure key storage and account recovery are not implemented.

## Identity and transport boundary

- A UA persona has a stable random identifier and an independently generated Ed25519 signing key.
- Persona signing keys are independent of Reticulum identities, browser/passkey credentials, and the offline master recovery key.
- A signed key-rotation statement can preserve persona continuity when the current key is available. It does not itself establish recovery authority or solve a compromised/lost-key recovery dispute.
- Signed event envelopes use a canonical JSON representation, a stable content-derived event ID, an explicit schema identifier, and Ed25519 signatures.
- Event ingestion requires a separately trusted persona-to-public-key binding. A self-presented public key is not enough to establish identity or authority.
- The local event store is append-only and deduplicates by event ID.
- The transport-neutral sync engine exports bounded event batches, requires explicit acknowledgement before advancing outbound cursors, verifies the authenticated peer's trusted persona keys on receipt, and atomically stores received events with the receive cursor.
- Sync cursors are durable per peer and direction. Repeated batches are safe; malformed, unordered, oversized, untrusted, or cursor-regressing batches are rejected.
- A shared transport contract now separates peer synchronization from network specifics. Its coordinator requires an authenticated peer session, checks transport/address consistency, and advances outbound cursors only after a matching durable-receipt acknowledgement.
- The transport contract is not itself a live network adapter. Reticulum and internet implementations still need to supply real authenticated sessions, framing, encryption, reconnect behavior, and delivery acknowledgements.
- The sync engine does not exchange trust, grant membership, or resolve domain conflicts. Peer invitations and persistent trust scopes remain a separate layer.
- Reticulum identity binding, WebAuthn/passkey login, secure key storage, offline master recovery, and recovery challenges are separate future layers.
- These primitives are transport-independent: the same signed UA event can be carried over Reticulum, an internet relay, removable media, or another authorized transport.

## Domain invariants in this slice

- SCAF is integer-only and internal: 10 to an eligible participant and 5 to the quest creator.
- Quest identity is stable; each publication creates a separate version row.
- An accepted attempt references the exact version it accepted.
- A player can accept a given quest once, regardless of later versions.
- Creator self-acceptance is rejected.
- Completion state and both ledger entries are written in one SQLite transaction.
- Completion retries return the existing result without issuing duplicate ledger entries.
- Balances are calculated from ledger entries, not stored as mutable account state.

The current schema and service layer are a foundation, not yet the full alpha. Internet/Reticulum transport adapters, passkey authentication, frontend, moderation, migrations, secure key custody, trust/invitation exchange, domain-specific conflict handling, and live multi-node transport integration remain subsequent slices.
